using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using EmployeeMonitor.Agent.Monitoring;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Agent;

/// <summary>
/// Maintains a signaling connection to the backend and, while an authorized live-view session is active for this
/// device, streams JPEG frames (the frame-relay transport; WebRTC OFFER/ANSWER/ICE would flow over the same
/// channel). Capture is gated by the live-view policy flag and never runs while the workstation is locked. The
/// backend is the sole authority on whether a session exists - the agent only captures when told to by a START.
/// </summary>
public sealed class LiveViewClient
{
    private readonly Uri _wsUri;
    private readonly Monitoring.IScreenCaptureProvider _capture;
    private readonly ILogger _logger;
    private readonly Func<bool> _liveViewAllowed;
    private readonly Func<bool> _isLocked;
    private readonly int _frameIntervalMs;
    private readonly Func<ClientWebSocket>? _socketFactory;
    private readonly Monitoring.ISystemAudioCapture? _audio;
    private readonly Monitoring.IRemoteInputInjector? _input;
    private readonly Monitoring.ISystemClipboard? _clipboard;

    private string? _deviceToken;
    private volatile string? _activeSessionId;
    private volatile bool _controlGranted;
    private volatile bool _consentPending;
    private volatile string? _lastClipboard;
    private readonly SemaphoreSlim _sendGate = new(1, 1);

    // System-audio streaming: capture pushes chunks onto this queue; the send loop drains it.
    private readonly ConcurrentQueue<string> _audioQueue = new();
    private volatile bool _audioRunning;
    private const int MaxAudioQueue = 40;

    public LiveViewClient(string baseUrl, Monitoring.IScreenCaptureProvider capture, ILogger logger,
        Func<bool> liveViewAllowed, Func<bool> isLocked, int fps = 2, Func<ClientWebSocket>? socketFactory = null,
        Monitoring.ISystemAudioCapture? audio = null, Monitoring.IRemoteInputInjector? input = null,
        Monitoring.ISystemClipboard? clipboard = null)
    {
        _wsUri = BuildWsUri(baseUrl);
        _capture = capture;
        _logger = logger;
        _liveViewAllowed = liveViewAllowed;
        _isLocked = isLocked;
        _frameIntervalMs = Math.Max(200, 1000 / Math.Max(1, fps));
        _socketFactory = socketFactory;
        _audio = audio;
        _input = input;
        _clipboard = clipboard;
    }

    public bool Active => _activeSessionId is not null;

    public void Configure(string deviceToken) => _deviceToken = deviceToken;

    // ---------- Testable control logic ----------

    /// <summary>Applies a control message. Returns a READY ack to send on START, otherwise null.</summary>
    public string? OnControlMessage(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            string type = doc.RootElement.TryGetProperty("type", out var t) ? t.GetString() ?? "" : "";
            string? sessionId = doc.RootElement.TryGetProperty("sessionId", out var s) ? s.GetString() : null;
            switch (type)
            {
                case "START" when sessionId is not null:
                    _activeSessionId = sessionId;
                    _logger.LogInformation("Live view started (session {Session})", sessionId);
                    return $"{{\"type\":\"READY\",\"sessionId\":\"{sessionId}\"}}";
                case "STOP":
                    if (_activeSessionId is not null)
                    {
                        _logger.LogInformation("Live view stopped (session {Session})", _activeSessionId);
                    }
                    _activeSessionId = null;
                    _controlGranted = false;
                    return null;
                default:
                    return null;
            }
        }
        catch (Exception)
        {
            return null;
        }
    }

    /// <summary>Builds a FRAME message, or null when not streaming / not permitted / locked / capture failed.</summary>
    public string? BuildFrameMessage()
    {
        var session = _activeSessionId;
        if (session is null || !_liveViewAllowed() || _isLocked())
        {
            return null;
        }
        Monitoring.ScreenCapture? frame;
        try
        {
            frame = _capture.Capture();
        }
        catch (Exception ex)
        {
            _logger.LogDebug("Live-view capture failed: {Message}", ex.Message);
            return null;
        }
        if (frame is null)
        {
            return null;
        }
        return JsonSerializer.Serialize(new
        {
            type = "FRAME",
            sessionId = session,
            contentType = frame.ContentType,
            data = Convert.ToBase64String(frame.Bytes),
        });
    }

    // ---------- Connection loop ----------

    public async Task RunAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(1);
        while (!ct.IsCancellationRequested)
        {
            if (_deviceToken is null)
            {
                await Delay(TimeSpan.FromSeconds(1), ct);
                continue;
            }
            try
            {
                using var socket = _socketFactory?.Invoke() ?? new ClientWebSocket();
                socket.Options.SetRequestHeader("Authorization", "Bearer " + _deviceToken);
                await socket.ConnectAsync(_wsUri, ct);
                backoff = TimeSpan.FromSeconds(1);
                await Task.WhenAll(ReceiveLoop(socket, ct), SendFrames(socket, ct), SendAudio(socket, ct),
                    SyncClipboard(socket, ct));
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogDebug("Live-view signaling dropped: {Message}", ex.Message);
            }
            _activeSessionId = null;
            _controlGranted = false;
            await Delay(backoff, ct);
            backoff = TimeSpan.FromSeconds(Math.Min(30, backoff.TotalSeconds * 2));
        }
    }

    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[16 * 1024];
        var builder = new StringBuilder();
        while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
        {
            builder.Clear();
            WebSocketReceiveResult result;
            do
            {
                result = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await socket.CloseAsync(WebSocketCloseStatus.NormalClosure, "bye", CancellationToken.None);
                    return;
                }
                builder.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
            }
            while (!result.EndOfMessage);

            string text = builder.ToString();
            string ack = OnControlMessage(text) ?? "";
            if (ack.Length > 0)
            {
                await SendTextSafe(socket, ack, ct);
            }
            HandleRemoteMessage(socket, text, ct);
        }
    }

    // ---------- Remote control (admin-assisted input), gated by explicit user consent ----------

    private void HandleRemoteMessage(ClientWebSocket socket, string json, CancellationToken ct)
    {
        if (_input is null)
        {
            return;
        }
        try
        {
            using var doc = JsonDocument.Parse(json);
            var root = doc.RootElement;
            string type = root.TryGetProperty("type", out var t) ? t.GetString() ?? "" : "";
            switch (type)
            {
                case "CONTROL_REQUEST":
                    StartConsent(socket, root, ct);
                    break;
                case "CONTROL_STOP":
                    if (_controlGranted)
                    {
                        _logger.LogInformation("Remote control released.");
                    }
                    _controlGranted = false;
                    break;
                case "INPUT":
                    if (_controlGranted && !_isLocked())
                    {
                        ApplyInput(root);
                    }
                    break;
                case "CLIPBOARD":
                    if (_controlGranted && _clipboard is not null
                        && root.TryGetProperty("text", out var clipEl) && clipEl.GetString() is { } clip)
                    {
                        _lastClipboard = clip;   // remember so the poll loop doesn't echo it back
                        try { _clipboard.SetText(clip); } catch { }
                    }
                    break;
            }
        }
        catch (Exception)
        {
            // ignore malformed control messages
        }
    }

    private void StartConsent(ClientWebSocket socket, JsonElement root, CancellationToken ct)
    {
        var session = _activeSessionId;
        if (session is null || _controlGranted || _consentPending)
        {
            return;
        }
        if (!_liveViewAllowed())
        {
            _ = ReplyControl(socket, "CONTROL_DENIED", session, ct);
            return;
        }
        string admin = root.TryGetProperty("admin", out var a) ? a.GetString() ?? "An administrator" : "An administrator";
        _consentPending = true;
        _ = Task.Run(() =>
        {
            bool allow = false;
            try
            {
                allow = _input!.RequestConsent(admin);
            }
            catch (Exception ex)
            {
                _logger.LogDebug("Consent prompt failed: {Message}", ex.Message);
            }
            _controlGranted = allow;
            _consentPending = false;
            _ = ReplyControl(socket, allow ? "CONTROL_GRANTED" : "CONTROL_DENIED", session, ct);
            _logger.LogInformation("Remote control {Result} (session {Session})", allow ? "granted" : "denied", session);
        });
    }

    private async Task ReplyControl(ClientWebSocket socket, string type, string session, CancellationToken ct)
    {
        try
        {
            await SendTextSafe(socket, JsonSerializer.Serialize(new { type, sessionId = session }), ct);
        }
        catch (Exception)
        {
            // best-effort
        }
    }

    private void ApplyInput(JsonElement root)
    {
        if (_input is null || !root.TryGetProperty("kind", out var k))
        {
            return;
        }
        string kind = k.GetString() ?? "";
        double x = root.TryGetProperty("x", out var xv) ? xv.GetDouble() : 0;
        double y = root.TryGetProperty("y", out var yv) ? yv.GetDouble() : 0;
        switch (kind)
        {
            case "move":
                _input.MoveMouse(x, y);
                break;
            case "down":
                _input.MouseButton(Button(root), true, x, y);
                break;
            case "up":
                _input.MouseButton(Button(root), false, x, y);
                break;
            case "wheel":
                _input.MouseWheel(root.TryGetProperty("delta", out var d) ? d.GetInt32() : 0, x, y);
                break;
            case "keydown":
                _input.KeyEvent(KeyCode(root), true);
                break;
            case "keyup":
                _input.KeyEvent(KeyCode(root), false);
                break;
        }
    }

    private static string Button(JsonElement root) =>
        root.TryGetProperty("button", out var b) ? b.GetString() ?? "left" : "left";

    private static int KeyCode(JsonElement root) =>
        root.TryGetProperty("key", out var k) && k.TryGetInt32(out var v) ? v : 0;

    private async Task SendFrames(ClientWebSocket socket, CancellationToken ct)
    {
        while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
        {
            string? frame = BuildFrameMessage();
            if (frame is not null)
            {
                await SendTextSafe(socket, frame, ct);
            }
            await Delay(TimeSpan.FromMilliseconds(_frameIntervalMs), ct);
        }
    }

    // Streams the system audio (WASAPI loopback) while a session is live. Capture is started/stopped to match the
    // session so the microphone is never involved and nothing records when no one is viewing.
    private async Task SendAudio(ClientWebSocket socket, CancellationToken ct)
    {
        if (_audio is null)
        {
            return;
        }
        try
        {
            while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                ManageAudioCapture();
                while (_audioQueue.TryDequeue(out var msg))
                {
                    await SendTextSafe(socket, msg, ct);
                }
                await Delay(TimeSpan.FromMilliseconds(30), ct);
            }
        }
        finally
        {
            StopAudioCapture();
        }
    }

    private void ManageAudioCapture()
    {
        if (_audio is null)
        {
            return;
        }
        bool shouldRun = _activeSessionId is not null && _liveViewAllowed() && !_isLocked();
        if (shouldRun && !_audioRunning)
        {
            _audioRunning = true;
            try
            {
                _audio.Start(OnAudioChunk);
            }
            catch (Exception ex)
            {
                _audioRunning = false;
                _logger.LogDebug("Audio capture start failed: {Message}", ex.Message);
            }
        }
        else if (!shouldRun && _audioRunning)
        {
            StopAudioCapture();
        }
    }

    private void StopAudioCapture()
    {
        if (_audio is not null && _audioRunning)
        {
            _audioRunning = false;
            try
            {
                _audio.Stop();
            }
            catch (Exception)
            {
                // ignore
            }
        }
        while (_audioQueue.TryDequeue(out _))
        {
        }
    }

    // Mirrors this machine's text clipboard to the admin while control is granted (copy here -> paste there). The
    // admin->here direction is handled in HandleRemoteMessage. Baselines on each grant so it only sends real changes.
    private async Task SyncClipboard(ClientWebSocket socket, CancellationToken ct)
    {
        if (_clipboard is null)
        {
            return;
        }
        bool baselined = false;
        try
        {
            while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var session = _activeSessionId;
                if (_controlGranted && session is not null)
                {
                    string? text = null;
                    try { text = _clipboard.GetText(); } catch { }
                    if (!baselined)
                    {
                        _lastClipboard = text;
                        baselined = true;
                    }
                    else if (!string.IsNullOrEmpty(text) && text != _lastClipboard)
                    {
                        _lastClipboard = text;
                        await SendTextSafe(socket,
                            JsonSerializer.Serialize(new { type = "CLIPBOARD", sessionId = session, text }), ct);
                    }
                }
                else
                {
                    baselined = false;
                }
                await Delay(TimeSpan.FromMilliseconds(700), ct);
            }
        }
        catch (Exception)
        {
            // best-effort
        }
    }

    private void OnAudioChunk(byte[] pcm16, int sampleRate, int channels)
    {
        var session = _activeSessionId;
        if (session is null || _audioQueue.Count >= MaxAudioQueue)
        {
            return;   // not streaming, or the viewer can't keep up - drop this chunk
        }
        _audioQueue.Enqueue(JsonSerializer.Serialize(new
        {
            type = "AUDIO",
            sessionId = session,
            sampleRate,
            channels,
            data = Convert.ToBase64String(pcm16),
        }));
    }

    // WebSockets allow only one outstanding send at a time; the receive (ack) and frame loops share this gate.
    private async Task SendTextSafe(ClientWebSocket socket, string text, CancellationToken ct)
    {
        await _sendGate.WaitAsync(ct);
        try
        {
            await socket.SendAsync(Encoding.UTF8.GetBytes(text), WebSocketMessageType.Text, true, ct);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    private static async Task Delay(TimeSpan delay, CancellationToken ct)
    {
        try
        {
            await Task.Delay(delay, ct);
        }
        catch (OperationCanceledException)
        {
        }
    }

    private static Uri BuildWsUri(string baseUrl)
    {
        var uri = new Uri(baseUrl);
        var scheme = uri.Scheme == "https" ? "wss" : "ws";
        return new Uri($"{scheme}://{uri.Authority}/ws/live-view");
    }
}
