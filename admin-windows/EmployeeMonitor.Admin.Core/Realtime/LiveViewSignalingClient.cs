using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Admin.Core.Realtime;

/// <summary>Connects to {baseUrl}/ws/live-view?sessionId=… with the admin's access token and reads frames.</summary>
public sealed class LiveViewSignalingClient : ILiveViewSignaling
{
    private readonly string _baseUrl;
    private readonly SessionState _session;
    private readonly ILogger<LiveViewSignalingClient> _logger;
    private readonly Func<ClientWebSocket>? _socketFactory;

    private CancellationTokenSource? _cts;
    private Task? _loop;
    private RealtimeStatus _status = RealtimeStatus.Disconnected;
    private volatile ClientWebSocket? _socket;
    private readonly SemaphoreSlim _sendGate = new(1, 1);

    public LiveViewSignalingClient(string baseUrl, SessionState session, ILogger<LiveViewSignalingClient> logger,
        Func<ClientWebSocket>? socketFactory = null)
    {
        _baseUrl = baseUrl;
        _session = session;
        _logger = logger;
        _socketFactory = socketFactory;
    }

    public RealtimeStatus Status => _status;
    public event Action<byte[]>? FrameReceived;
    public event Action<byte[], int, int>? AudioReceived;
    public event Action<string>? Stopped;
    public event Action<RealtimeStatus>? StatusChanged;
    public event Action? ControlGranted;
    public event Action? ControlDenied;
    public event Action<string>? ClipboardReceived;

    public Task StartAsync(Guid sessionId, CancellationToken ct = default)
    {
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _loop = Task.Run(() => RunAsync(sessionId, _cts.Token));
        return Task.CompletedTask;
    }

    public async Task StopAsync()
    {
        _cts?.Cancel();
        if (_loop is not null)
        {
            try
            {
                await _loop;
            }
            catch (Exception)
            {
            }
        }
        _loop = null;
        SetStatus(RealtimeStatus.Disconnected);
    }

    private async Task RunAsync(Guid sessionId, CancellationToken ct)
    {
        try
        {
            if (_session.AccessToken is not { } token)
            {
                return;
            }
            SetStatus(RealtimeStatus.Connecting);
            var uri = BuildWsUri(sessionId);
            using var socket = _socketFactory?.Invoke() ?? new ClientWebSocket();
            socket.Options.SetRequestHeader("Authorization", "Bearer " + token);
            await socket.ConnectAsync(uri, ct);
            _socket = socket;
            SetStatus(RealtimeStatus.Connected);
            await ReceiveLoop(socket, ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
        }
        catch (Exception ex)
        {
            _logger.LogDebug("Live-view signaling ended: {Message}", ex.Message);
        }
        _socket = null;
        SetStatus(RealtimeStatus.Disconnected);
    }

    public async Task SendAsync(string json, CancellationToken ct = default)
    {
        var socket = _socket;
        if (socket is null || socket.State != WebSocketState.Open)
        {
            return;
        }
        await _sendGate.WaitAsync(ct);
        try
        {
            await socket.SendAsync(Encoding.UTF8.GetBytes(json), WebSocketMessageType.Text, true, ct);
        }
        catch (Exception ex)
        {
            _logger.LogDebug("Live-view send failed: {Message}", ex.Message);
        }
        finally
        {
            _sendGate.Release();
        }
    }

    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[64 * 1024];
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
                    return;
                }
                builder.Append(Encoding.UTF8.GetString(buffer, 0, result.Count));
            }
            while (!result.EndOfMessage);

            Dispatch(builder.ToString());
        }
    }

    private void Dispatch(string message)
    {
        try
        {
            using var doc = JsonDocument.Parse(message);
            string type = doc.RootElement.TryGetProperty("type", out var t) ? t.GetString() ?? "" : "";
            switch (type)
            {
                case "FRAME":
                    if (doc.RootElement.TryGetProperty("data", out var d) && d.GetString() is { Length: > 0 } b64)
                    {
                        FrameReceived?.Invoke(Convert.FromBase64String(b64));
                    }
                    break;
                case "AUDIO":
                    if (doc.RootElement.TryGetProperty("data", out var ad) && ad.GetString() is { Length: > 0 } ab64)
                    {
                        int sampleRate = doc.RootElement.TryGetProperty("sampleRate", out var sr) ? sr.GetInt32() : 48000;
                        int channels = doc.RootElement.TryGetProperty("channels", out var ch) ? ch.GetInt32() : 2;
                        AudioReceived?.Invoke(Convert.FromBase64String(ab64), sampleRate, channels);
                    }
                    break;
                case "CONTROL_GRANTED":
                    ControlGranted?.Invoke();
                    break;
                case "CONTROL_DENIED":
                    ControlDenied?.Invoke();
                    break;
                case "CLIPBOARD":
                    if (doc.RootElement.TryGetProperty("text", out var clip) && clip.GetString() is { } clipText)
                    {
                        ClipboardReceived?.Invoke(clipText);
                    }
                    break;
                case "STOP":
                    Stopped?.Invoke(doc.RootElement.TryGetProperty("reason", out var r) ? r.GetString() ?? "ENDED" : "ENDED");
                    break;
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug("Unparseable live-view message: {Message}", ex.Message);
        }
    }

    private void SetStatus(RealtimeStatus status)
    {
        if (_status != status)
        {
            _status = status;
            StatusChanged?.Invoke(status);
        }
    }

    private Uri BuildWsUri(Guid sessionId)
    {
        var uri = new Uri(_baseUrl);
        var scheme = uri.Scheme == "https" ? "wss" : "ws";
        return new Uri($"{scheme}://{uri.Authority}/ws/live-view?sessionId={sessionId}");
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
