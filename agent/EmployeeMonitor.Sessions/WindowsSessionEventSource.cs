using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Platform;
using Microsoft.Extensions.Logging;
using Microsoft.Win32;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Session/power transitions from the supported <see cref="SystemEvents"/> API, which runs its own hidden-window
/// message pump. Delivers lock, unlock, login, logout, sleep, resume and shutdown. Reliable in an interactive
/// user session; a Session-0 service needs the per-session helper described in docs/agent.md.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsSessionEventSource : ISystemEventSource
{
    private readonly ILogger<WindowsSessionEventSource> _logger;
    private bool _started;

    public event Action<SystemSignal>? Raised;

    public WindowsSessionEventSource(ILogger<WindowsSessionEventSource> logger)
    {
        _logger = logger;
    }

    public void Start()
    {
        if (_started)
        {
            return;
        }
        SystemEvents.SessionSwitch += OnSessionSwitch;
        SystemEvents.PowerModeChanged += OnPowerModeChanged;
        SystemEvents.SessionEnding += OnSessionEnding;
        _started = true;
        _logger.LogInformation("Subscribed to Windows session and power events");
    }

    private void OnSessionSwitch(object sender, SessionSwitchEventArgs e)
    {
        SystemSignal? signal = e.Reason switch
        {
            SessionSwitchReason.SessionLock => SystemSignal.Lock,
            SessionSwitchReason.SessionUnlock => SystemSignal.Unlock,
            SessionSwitchReason.SessionLogon or SessionSwitchReason.ConsoleConnect
                or SessionSwitchReason.RemoteConnect => SystemSignal.Login,
            SessionSwitchReason.SessionLogoff or SessionSwitchReason.ConsoleDisconnect
                or SessionSwitchReason.RemoteDisconnect => SystemSignal.Logout,
            _ => null,
        };
        if (signal is { } s)
        {
            Emit(s);
        }
    }

    private void OnPowerModeChanged(object sender, PowerModeChangedEventArgs e)
    {
        switch (e.Mode)
        {
            case PowerModes.Suspend:
                Emit(SystemSignal.Sleep);
                break;
            case PowerModes.Resume:
                Emit(SystemSignal.Resume);
                break;
        }
    }

    private void OnSessionEnding(object sender, SessionEndingEventArgs e) => Emit(SystemSignal.Shutdown);

    private void Emit(SystemSignal signal)
    {
        _logger.LogDebug("Windows signal: {Signal}", signal);
        try
        {
            Raised?.Invoke(signal);
        }
        catch (Exception ex)
        {
            _logger.LogWarning(ex, "Session-event handler threw for {Signal}", signal);
        }
    }

    public void Dispose()
    {
        if (!_started)
        {
            return;
        }
        SystemEvents.SessionSwitch -= OnSessionSwitch;
        SystemEvents.PowerModeChanged -= OnPowerModeChanged;
        SystemEvents.SessionEnding -= OnSessionEnding;
        _started = false;
    }
}
