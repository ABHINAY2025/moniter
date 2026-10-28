using NAudio.Wave;

namespace EmployeeMonitor.Admin.Infrastructure;

/// <summary>
/// Plays the live system audio streamed from an agent. Chunks are 16-bit PCM; the player (re)initialises when the
/// sample rate or channel count changes. Muting sets the output volume to zero while the buffer keeps draining, so
/// there is no backlog and unmuting resumes at the live point.
/// </summary>
public sealed class AudioPlayer : IDisposable
{
    private readonly object _gate = new();
    private WaveOutEvent? _output;
    private BufferedWaveProvider? _buffer;
    private int _sampleRate;
    private int _channels;
    private bool _muted;
    private float _volume = 1f;

    public bool IsMuted
    {
        get => _muted;
        set
        {
            _muted = value;
            ApplyVolume();
        }
    }

    /// <summary>Playback level 0..1 for the admin (does not change the employee's own volume).</summary>
    public float Volume
    {
        get => _volume;
        set
        {
            _volume = Math.Clamp(value, 0f, 1f);
            ApplyVolume();
        }
    }

    private void ApplyVolume()
    {
        lock (_gate)
        {
            if (_output is not null)
            {
                _output.Volume = _muted ? 0f : _volume;
            }
        }
    }

    public void Enqueue(byte[] pcm16, int sampleRate, int channels)
    {
        if (pcm16.Length == 0)
        {
            return;
        }
        lock (_gate)
        {
            if (_output is null || _sampleRate != sampleRate || _channels != channels)
            {
                Reset(sampleRate, channels);
            }
            try
            {
                _buffer!.AddSamples(pcm16, 0, pcm16.Length);
            }
            catch
            {
                // overflow is discarded (DiscardOnBufferOverflow); any other error just drops this chunk
            }
        }
    }

    private void Reset(int sampleRate, int channels)
    {
        DisposeInternal();
        _sampleRate = sampleRate;
        _channels = channels;
        var format = new WaveFormat(sampleRate, 16, channels);
        _buffer = new BufferedWaveProvider(format)
        {
            BufferDuration = TimeSpan.FromSeconds(3),
            DiscardOnBufferOverflow = true,
        };
        _output = new WaveOutEvent { DesiredLatency = 200, Volume = _muted ? 0f : _volume };
        _output.Init(_buffer);
        _output.Play();
    }

    public void Stop()
    {
        lock (_gate)
        {
            DisposeInternal();
        }
    }

    private void DisposeInternal()
    {
        try
        {
            _output?.Stop();
            _output?.Dispose();
        }
        catch
        {
            // ignore teardown errors
        }
        _output = null;
        _buffer = null;
    }

    public void Dispose() => Stop();
}
