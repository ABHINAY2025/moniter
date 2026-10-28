using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Monitoring;
using Microsoft.Extensions.Logging;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Captures the default playback device's output mix via WASAPI loopback (NAudio) and emits ~100 ms chunks of
/// 16-bit PCM. This is the system audio - what is coming out of the speakers - NOT the microphone, which the
/// platform never records. Capture is driven entirely by the live-view client and only runs during an authorized,
/// user-visible session.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsSystemAudioCapture : ISystemAudioCapture
{
    private readonly ILogger<WindowsSystemAudioCapture> _log;
    private readonly object _gate = new();
    private WasapiLoopbackCapture? _capture;
    private Action<byte[], int, int>? _onChunk;
    private MemoryStream _acc = new();
    private int _sampleRate;
    private int _channels;
    private int _bytesPerChunk;
    private WaveFormat? _format;

    public WindowsSystemAudioCapture(ILogger<WindowsSystemAudioCapture> log) => _log = log;

    public bool IsSupported => true;

    public void Start(Action<byte[], int, int> onChunk)
    {
        lock (_gate)
        {
            Stop_NoLock();
            _onChunk = onChunk;
            try
            {
                _capture = new WasapiLoopbackCapture();   // default render device
                _format = _capture.WaveFormat;
                _sampleRate = _format.SampleRate;
                _channels = _format.Channels;
                _bytesPerChunk = Math.Max(3200, _sampleRate * _channels * 2 / 10);   // ~100 ms of 16-bit PCM
                _acc = new MemoryStream();
                _capture.DataAvailable += OnData;
                _capture.StartRecording();
                _log.LogInformation("System audio capture started ({Rate} Hz, {Ch} ch, {Enc}).",
                    _sampleRate, _channels, _format.Encoding);
            }
            catch (Exception ex)
            {
                _log.LogWarning(ex, "Could not start system audio capture; live view will be silent.");
                Stop_NoLock();
            }
        }
    }

    private void OnData(object? sender, WaveInEventArgs e)
    {
        if (_format is null || e.BytesRecorded <= 0)
        {
            return;
        }
        byte[] pcm16 = ToPcm16(e.Buffer, e.BytesRecorded, _format);
        if (pcm16.Length == 0)
        {
            return;
        }
        Action<byte[], int, int>? cb;
        byte[]? chunk = null;
        lock (_gate)
        {
            _acc.Write(pcm16, 0, pcm16.Length);
            if (_acc.Length >= _bytesPerChunk)
            {
                chunk = _acc.ToArray();
                _acc.SetLength(0);
            }
            cb = _onChunk;
        }
        if (chunk is not null)
        {
            cb?.Invoke(chunk, _sampleRate, _channels);
        }
    }

    /// <summary>Converts the captured buffer (usually 32-bit IEEE float, sometimes 16-bit PCM) to 16-bit PCM.</summary>
    private static byte[] ToPcm16(byte[] buffer, int bytes, WaveFormat format)
    {
        if (format.Encoding == WaveFormatEncoding.IeeeFloat && format.BitsPerSample == 32)
        {
            int samples = bytes / 4;
            var outBytes = new byte[samples * 2];
            for (int i = 0; i < samples; i++)
            {
                float f = BitConverter.ToSingle(buffer, i * 4);
                int s = (int)(f * 32767f);
                if (s > short.MaxValue) s = short.MaxValue;
                else if (s < short.MinValue) s = short.MinValue;
                outBytes[i * 2] = (byte)(s & 0xFF);
                outBytes[i * 2 + 1] = (byte)((s >> 8) & 0xFF);
            }
            return outBytes;
        }
        if (format.Encoding == WaveFormatEncoding.Pcm && format.BitsPerSample == 16)
        {
            var outBytes = new byte[bytes];
            Buffer.BlockCopy(buffer, 0, outBytes, 0, bytes);
            return outBytes;
        }
        // Unsupported capture format - skip rather than emit noise.
        return Array.Empty<byte>();
    }

    public void Stop()
    {
        lock (_gate)
        {
            Stop_NoLock();
        }
    }

    private void Stop_NoLock()
    {
        if (_capture is not null)
        {
            try
            {
                _capture.DataAvailable -= OnData;
                _capture.StopRecording();
                _capture.Dispose();
            }
            catch
            {
                // ignore teardown errors
            }
            _capture = null;
        }
        _onChunk = null;
        _format = null;
    }

    public void Dispose() => Stop();
}
