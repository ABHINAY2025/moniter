namespace EmployeeMonitor.Agent.Platform;

/// <summary>Wall clock, injectable so time-based logic is unit-testable.</summary>
public interface ISystemClock
{
    DateTimeOffset UtcNow { get; }
}

public sealed class SystemClock : ISystemClock
{
    public DateTimeOffset UtcNow => DateTimeOffset.UtcNow;
}

/// <summary>Reports how long since the last user input in this session. Content of input is never read.</summary>
public interface IInputMonitor
{
    /// <summary>Time since the last keyboard/mouse input, or <see cref="TimeSpan.Zero"/> if unavailable.</summary>
    TimeSpan GetIdleTime();
}

/// <summary>Encrypted local storage for the device credential (DPAPI on Windows).</summary>
public interface ISecureStore
{
    void Save(string plaintext);
    string? Load();
    void Clear();
}

/// <summary>Facts about the host gathered at enrollment.</summary>
public interface ISystemInfo
{
    string Hostname { get; }
    string OsVersion { get; }
    string? MacAddress { get; }
    string? PrimaryIpAddress { get; }
    /// <summary>Stable per-machine id (Windows MachineGuid) used to recognise re-installs.</summary>
    string? HardwareId { get; }
}

/// <summary>
/// Source of OS session/power transitions. Implementations raise <see cref="Raised"/> on lock, unlock, login,
/// logout, sleep, resume and shutdown. The orchestrator subscribes and never polls these.
/// </summary>
public interface ISystemEventSource : IDisposable
{
    event Action<SystemSignal>? Raised;
    void Start();
}

/// <summary>Raw OS signals, mapped to session-event types by the orchestrator.</summary>
public enum SystemSignal
{
    Lock,
    Unlock,
    Login,
    Logout,
    Sleep,
    Resume,
    Shutdown,
}
