namespace EmployeeMonitor.Admin.Core.Realtime;

/// <summary>
/// Admin side of the live-view signaling channel. Connects to a specific authorized session and raises a
/// <see cref="FrameReceived"/> for each JPEG frame (frame-relay transport) and <see cref="Stopped"/> when the
/// session ends. Abstracted so the view model is unit-testable without a real socket.
/// </summary>
public interface ILiveViewSignaling : IAsyncDisposable
{
    RealtimeStatus Status { get; }
    event Action<byte[]>? FrameReceived;

    /// <summary>A chunk of system audio: 16-bit PCM bytes, its sample rate, and channel count.</summary>
    event Action<byte[], int, int>? AudioReceived;
    event Action<string>? Stopped;
    event Action<RealtimeStatus>? StatusChanged;

    /// <summary>The employee accepted / declined the remote-control request.</summary>
    event Action? ControlGranted;
    event Action? ControlDenied;

    /// <summary>The remote machine's clipboard text changed (clipboard sync).</summary>
    event Action<string>? ClipboardReceived;

    Task StartAsync(Guid sessionId, CancellationToken ct = default);
    Task StopAsync();

    /// <summary>Sends a raw signaling message to the agent (control request, input, release).</summary>
    Task SendAsync(string json, CancellationToken ct = default);
}
