using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.Realtime;

public enum RealtimeStatus
{
    Disconnected,
    Connecting,
    Connected,
}

/// <summary>Streams presence deltas from the backend WebSocket. Abstracted so view models can be tested.</summary>
public interface IRealtimeClient : IAsyncDisposable
{
    RealtimeStatus Status { get; }
    event Action<PresenceEvent>? PresenceReceived;
    event Action<AlertDto>? AlertReceived;
    event Action<WebAccessEventDto>? WebAccessEventReceived;
    event Action<RealtimeStatus>? StatusChanged;

    Task StartAsync(CancellationToken ct = default);
    Task StopAsync();
}
