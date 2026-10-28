using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using EmployeeMonitor.Admin.Core.Api;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Admin.Core.Realtime;

/// <summary>
/// Connects to {baseUrl}/ws/events with the current access token and raises <see cref="PresenceReceived"/> for
/// each delta. Reconnects automatically with capped backoff; the dashboard refreshes its REST snapshot on each
/// reconnect to close any gap.
/// </summary>
public sealed class RealtimeClient : IRealtimeClient
{
    private readonly Uri _wsUri;
    private readonly SessionState _session;
    private readonly ILogger<RealtimeClient> _logger;
    private readonly Func<ClientWebSocket> _socketFactory;

    private CancellationTokenSource? _cts;
    private Task? _loop;
    private RealtimeStatus _status = RealtimeStatus.Disconnected;

    public RealtimeClient(string baseUrl, SessionState session, ILogger<RealtimeClient> logger,
        Func<ClientWebSocket>? socketFactory = null)
    {
        _wsUri = BuildWsUri(baseUrl);
        _session = session;
        _logger = logger;
        _socketFactory = socketFactory ?? (() => new ClientWebSocket());
    }

    public RealtimeStatus Status => _status;
    public event Action<PresenceEvent>? PresenceReceived;
    public event Action<AlertDto>? AlertReceived;
    public event Action<WebAccessEventDto>? WebAccessEventReceived;
    public event Action<RealtimeStatus>? StatusChanged;

    public Task StartAsync(CancellationToken ct = default)
    {
        if (_loop is not null)
        {
            return Task.CompletedTask;
        }
        _cts = CancellationTokenSource.CreateLinkedTokenSource(ct);
        _loop = Task.Run(() => RunAsync(_cts.Token));
        return Task.CompletedTask;
    }

    public async Task StopAsync()
    {
        if (_cts is null)
        {
            return;
        }
        _cts.Cancel();
        try
        {
            if (_loop is not null)
            {
                await _loop;
            }
        }
        catch (Exception)
        {
            // ignore shutdown errors
        }
        _loop = null;
        SetStatus(RealtimeStatus.Disconnected);
    }

    private async Task RunAsync(CancellationToken ct)
    {
        var backoff = TimeSpan.FromSeconds(1);
        while (!ct.IsCancellationRequested)
        {
            try
            {
                if (_session.AccessToken is not { } token)
                {
                    await Task.Delay(TimeSpan.FromSeconds(1), ct);
                    continue;
                }
                SetStatus(RealtimeStatus.Connecting);
                using var socket = _socketFactory();
                socket.Options.SetRequestHeader("Authorization", "Bearer " + token);
                await socket.ConnectAsync(_wsUri, ct);
                SetStatus(RealtimeStatus.Connected);
                backoff = TimeSpan.FromSeconds(1);
                await ReceiveLoop(socket, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogDebug("Realtime connection dropped: {Message}", ex.Message);
            }
            SetStatus(RealtimeStatus.Disconnected);
            try
            {
                await Task.Delay(backoff, ct);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            backoff = TimeSpan.FromSeconds(Math.Min(30, backoff.TotalSeconds * 2));
        }
    }

    private async Task ReceiveLoop(ClientWebSocket socket, CancellationToken ct)
    {
        var buffer = new byte[8192];
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

            Dispatch(builder.ToString());
        }
    }

    private void Dispatch(string message)
    {
        try
        {
            using var doc = JsonDocument.Parse(message);
            if (!doc.RootElement.TryGetProperty("type", out var typeEl))
            {
                return;
            }
            var type = typeEl.GetString();
            if (type is null or "CONNECTED")
            {
                return;
            }
            if (!doc.RootElement.TryGetProperty("payload", out var payload))
            {
                return;
            }
            if (type == "ALERT")
            {
                var alert = ParseAlert(payload);
                if (alert is not null)
                {
                    AlertReceived?.Invoke(alert);
                }
                return;
            }
            if (type == "WEB_EVENT")
            {
                WebAccessEventReceived?.Invoke(ParseWebEvent(payload));
                return;
            }
            var evt = new PresenceEvent(
                type,
                payload.TryGetProperty("deviceId", out var d) && d.ValueKind == JsonValueKind.Number ? d.GetInt64() : 0,
                payload.TryGetProperty("employeeId", out var e) && e.ValueKind == JsonValueKind.Number ? e.GetInt64() : null,
                payload.TryGetProperty("status", out var s) ? s.GetString() : null,
                payload.TryGetProperty("previousStatus", out var p) ? p.GetString() : null,
                payload.TryGetProperty("at", out var a) && a.ValueKind == JsonValueKind.String
                    ? a.GetDateTimeOffset() : DateTimeOffset.UtcNow);
            PresenceReceived?.Invoke(evt);
        }
        catch (Exception ex)
        {
            _logger.LogDebug("Could not parse realtime message: {Message}", ex.Message);
        }
    }

    private static AlertDto? ParseAlert(JsonElement p)
    {
        if (!p.TryGetProperty("id", out var idEl) || idEl.ValueKind != JsonValueKind.Number)
        {
            return null;
        }
        string? Str(string name) => p.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() : null;
        long? Num(string name) => p.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number
            ? v.GetInt64() : null;
        return new AlertDto(
            idEl.GetInt64(),
            Str("category") ?? "",
            Str("severity") ?? "WARNING",
            Str("message") ?? "",
            Num("deviceId"),
            Num("employeeId"),
            Str("deviceCode"),
            Str("employeeName"),
            p.TryGetProperty("createdAt", out var c) && c.ValueKind == JsonValueKind.String
                ? c.GetDateTimeOffset() : DateTimeOffset.UtcNow,
            p.TryGetProperty("acknowledged", out var ack) && ack.ValueKind == JsonValueKind.True,
            null);
    }

    private static WebAccessEventDto ParseWebEvent(JsonElement p)
    {
        string? Str(string name) => p.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.String
            ? v.GetString() : null;
        long? Num(string name) => p.TryGetProperty(name, out var v) && v.ValueKind == JsonValueKind.Number
            ? v.GetInt64() : null;
        return new WebAccessEventDto(
            0,
            Num("deviceId"),
            Str("deviceCode"),
            Num("employeeId"),
            null,
            Str("domain") ?? "",
            null,
            Str("eventType") ?? "ACCESS_BLOCKED",
            Str("enforcementResult") ?? "BLOCKED",
            null,
            null,
            p.TryGetProperty("occurredAt", out var o) && o.ValueKind == JsonValueKind.String
                ? o.GetDateTimeOffset() : DateTimeOffset.UtcNow);
    }

    private void SetStatus(RealtimeStatus status)
    {
        if (_status != status)
        {
            _status = status;
            StatusChanged?.Invoke(status);
        }
    }

    private static Uri BuildWsUri(string baseUrl)
    {
        var uri = new Uri(baseUrl);
        var scheme = uri.Scheme == "https" ? "wss" : "ws";
        return new Uri($"{scheme}://{uri.Authority}/ws/events");
    }

    public async ValueTask DisposeAsync() => await StopAsync();
}
