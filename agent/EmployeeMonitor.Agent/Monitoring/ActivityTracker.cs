using EmployeeMonitor.Agent.Platform;

namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>Coarse activity state reported to the backend. Mirrors the server's ActivityStatus.</summary>
public enum ActivityState
{
    Active,
    Idle,
    Locked,
}

/// <summary>
/// Pure state machine deciding ACTIVE vs IDLE from input idle time, suspended while the workstation is locked.
/// It only ever observes the <em>duration</em> since last input, never input content. All transitions are
/// returned to the caller so they can be emitted as session events; the machine itself has no side effects.
/// </summary>
public sealed class ActivityTracker
{
    private bool _locked;

    public ActivityState Current { get; private set; } = ActivityState.Active;

    /// <summary>
    /// Re-evaluates ACTIVE/IDLE from the latest idle sample. Returns the new state if it changed (so a
    /// transition event can be emitted), otherwise null. No-op while locked.
    /// </summary>
    public ActivityState? Poll(TimeSpan idleTime, TimeSpan idleThreshold)
    {
        if (_locked)
        {
            return null;
        }
        var desired = idleTime >= idleThreshold ? ActivityState.Idle : ActivityState.Active;
        if (desired == Current)
        {
            return null;
        }
        Current = desired;
        return Current;
    }

    /// <summary>Workstation locked: hold LOCKED and stop idle accounting until unlock.</summary>
    public void OnLocked() => (_locked, Current) = (true, ActivityState.Locked);

    /// <summary>
    /// Workstation unlocked: resume and re-establish ACTIVE/IDLE from the current idle sample. Always returns
    /// the resulting state so a fresh activity event is emitted after the lock interval.
    /// </summary>
    public ActivityState OnUnlocked(TimeSpan idleTime, TimeSpan idleThreshold)
    {
        _locked = false;
        Current = idleTime >= idleThreshold ? ActivityState.Idle : ActivityState.Active;
        return Current;
    }

    /// <summary>Server-facing status string (matches ActivityStatus on the backend).</summary>
    public string StatusString => Current switch
    {
        ActivityState.Active => "ACTIVE",
        ActivityState.Idle => "IDLE",
        ActivityState.Locked => "LOCKED",
        _ => "ACTIVE",
    };
}
