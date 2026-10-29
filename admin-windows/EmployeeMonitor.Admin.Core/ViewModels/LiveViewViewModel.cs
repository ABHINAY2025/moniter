using System.Text.Json;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>
/// Drives an authorized live-view session: asks the backend to start one, connects the signaling channel, and
/// surfaces incoming frames. The employee's device shows a "Live screen: Active" indicator for the duration, and
/// the session is time-limited and audited server-side.
/// </summary>
public partial class LiveViewViewModel : ObservableObject
{
    private readonly IAdminApi _api;
    private readonly ILiveViewSignaling _signaling;
    private readonly IUiDispatcher _ui;
    private bool _subscribed;

    public LiveViewViewModel(IAdminApi api, ILiveViewSignaling signaling, IUiDispatcher ui)
    {
        _api = api;
        _signaling = signaling;
        _ui = ui;
    }

    [ObservableProperty] private bool _isLive;
    [ObservableProperty] private string _statusText = "Idle";
    [ObservableProperty] private string _deviceCode = "";
    [ObservableProperty] private string _employeeName = "";
    [ObservableProperty] private int _frameCount;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private int _maxDurationSeconds;
    [ObservableProperty] private bool _muted;
    [ObservableProperty] private double _volume = 100;   // 0..100, the admin's listening level
    [ObservableProperty] private bool _isControlling;
    [ObservableProperty] private bool _controlPending;
    [ObservableProperty] private int _keysSent;

    // Per-device unattended-control setting (the inverse of the policy's requireControlConsent).
    [ObservableProperty] private bool _unattendedControl;
    [ObservableProperty] private bool _controlPolicyLoaded;
    [ObservableProperty] private string? _controlPolicyStatus;

    private long _controlDeviceId;
    private PolicySettingsDto? _controlSettings;
    private bool _suppressControlSave;

    public Guid? SessionId { get; private set; }

    /// <summary>Latest decoded JPEG frame. The view renders it; the VM stays UI-framework-agnostic.</summary>
    public event Action<byte[]>? FrameUpdated;

    /// <summary>A chunk of live system audio (16-bit PCM, sample rate, channels). The view plays it.</summary>
    public event Action<byte[], int, int>? AudioUpdated;

    /// <summary>The remote machine's clipboard text; the view mirrors it into this PC's clipboard.</summary>
    public event Action<string>? ClipboardUpdated;

    /// <summary>Sends this PC's clipboard text to the remote machine (while controlling).</summary>
    public Task SendClipboardAsync(string text)
    {
        if (!IsControlling || string.IsNullOrEmpty(text))
        {
            return Task.CompletedTask;
        }
        return _signaling.SendAsync(JsonSerializer.Serialize(new
        {
            type = "CLIPBOARD",
            sessionId = SessionId?.ToString(),
            text,
        }));
    }

    [RelayCommand]
    private void ToggleMute() => Muted = !Muted;

    // ---------- Remote control (admin-assisted input) ----------

    [RelayCommand]
    private async Task RequestControlAsync()
    {
        if (!IsLive || IsControlling || ControlPending)
        {
            return;
        }
        ControlPending = true;
        StatusText = "Waiting for the employee to allow control…";
        await _signaling.SendAsync(JsonSerializer.Serialize(new
        {
            type = "CONTROL_REQUEST",
            sessionId = SessionId?.ToString(),
            admin = "An administrator",
        }));
    }

    [RelayCommand]
    private async Task ReleaseControlAsync()
    {
        if (!IsControlling && !ControlPending)
        {
            return;
        }
        IsControlling = false;
        ControlPending = false;
        StatusText = IsLive ? "Live" : StatusText;
        await _signaling.SendAsync(JsonSerializer.Serialize(new
        {
            type = "CONTROL_STOP",
            sessionId = SessionId?.ToString(),
        }));
    }

    public Task SendMouseMoveAsync(double nx, double ny) =>
        SendInputAsync(new { type = "INPUT", kind = "move", x = nx, y = ny });

    public Task SendMouseButtonAsync(string button, bool down, double nx, double ny) =>
        SendInputAsync(new { type = "INPUT", kind = down ? "down" : "up", button, x = nx, y = ny });

    public Task SendWheelAsync(int delta, double nx, double ny) =>
        SendInputAsync(new { type = "INPUT", kind = "wheel", delta, x = nx, y = ny });

    public Task SendKeyAsync(int virtualKey, bool down)
    {
        if (down && IsControlling)
        {
            KeysSent++;   // visible counter so we can confirm the admin is capturing keystrokes
        }
        return SendInputAsync(new { type = "INPUT", kind = down ? "keydown" : "keyup", key = virtualKey });
    }

    private Task SendInputAsync(object message)
    {
        if (!IsControlling)
        {
            return Task.CompletedTask;
        }
        return _signaling.SendAsync(JsonSerializer.Serialize(message));
    }

    // ---------- Per-device unattended control (admin opt-out of the consent prompt for this one device) ----------

    /// <summary>
    /// Loads the device's effective policy so the admin can toggle unattended control for this device alone. The
    /// toggle writes a device override; a visible "being controlled" banner is always shown regardless.
    /// </summary>
    public async Task LoadControlPolicyAsync(long deviceId)
    {
        _controlDeviceId = deviceId;
        try
        {
            var p = await _api.GetDevicePolicyAsync(deviceId);
            _controlSettings = p.Settings;
            _suppressControlSave = true;
            UnattendedControl = !p.Settings.RequireControlConsent;
            _suppressControlSave = false;
            ControlPolicyLoaded = true;
            ControlPolicyStatus = null;
        }
        catch (Exception ex)
        {
            ControlPolicyLoaded = false;
            ControlPolicyStatus = (ex as ApiException)?.Message ?? ("Couldn't load control settings: " + ex.Message);
        }
    }

    partial void OnUnattendedControlChanged(bool value)
    {
        if (_suppressControlSave)
        {
            return;   // reflecting a freshly loaded policy, not a user action
        }
        _ = SaveControlPolicyAsync(value);
    }

    private async Task SaveControlPolicyAsync(bool unattended)
    {
        if (_controlSettings is null || _controlDeviceId == 0)
        {
            return;
        }
        try
        {
            ControlPolicyStatus = "Saving…";
            var updated = _controlSettings with { RequireControlConsent = !unattended };
            var resp = await _api.UpdateDevicePolicyAsync(_controlDeviceId, updated);
            _controlSettings = resp.Settings;
            ControlPolicyStatus = unattended
                ? "Unattended control enabled for this device — applies within about 30 seconds."
                : "Consent will be required for this device — applies within about 30 seconds.";
        }
        catch (Exception ex)
        {
            // Revert the toggle to the saved state so the UI reflects reality.
            _suppressControlSave = true;
            UnattendedControl = !(_controlSettings?.RequireControlConsent ?? true);
            _suppressControlSave = false;
            ControlPolicyStatus = (ex as ApiException)?.Message ?? ("Couldn't save: " + ex.Message);
        }
    }

    public async Task StartAsync(long deviceId)
    {
        Error = null;
        FrameCount = 0;
        try
        {
            var session = await _api.StartLiveViewAsync(deviceId);
            SessionId = session.SessionId;
            DeviceCode = session.DeviceCode;
            EmployeeName = session.Employee?.FullName ?? "(unassigned)";
            MaxDurationSeconds = session.MaxDurationSeconds;
            IsLive = true;
            StatusText = "Connecting…";
            Subscribe();
            await _signaling.StartAsync(session.SessionId);
        }
        catch (ApiException ex)
        {
            Error = ex.Message;
            IsLive = false;
            StatusText = "Idle";
        }
        catch (Exception ex)
        {
            Error = "Could not start live view: " + ex.Message;
            IsLive = false;
        }
    }

    private void Subscribe()
    {
        if (_subscribed)
        {
            return;
        }
        _signaling.FrameReceived += OnFrame;
        _signaling.AudioReceived += OnAudio;
        _signaling.Stopped += OnStopped;
        _signaling.StatusChanged += OnStatus;
        _signaling.ControlGranted += OnControlGranted;
        _signaling.ControlDenied += OnControlDenied;
        _signaling.ClipboardReceived += OnClipboard;
        _subscribed = true;
    }

    private void Unsubscribe()
    {
        if (_subscribed)
        {
            _signaling.FrameReceived -= OnFrame;
            _signaling.AudioReceived -= OnAudio;
            _signaling.Stopped -= OnStopped;
            _signaling.StatusChanged -= OnStatus;
            _signaling.ControlGranted -= OnControlGranted;
            _signaling.ControlDenied -= OnControlDenied;
            _signaling.ClipboardReceived -= OnClipboard;
            _subscribed = false;
        }
    }

    private void OnClipboard(string text) => _ui.Post(() => ClipboardUpdated?.Invoke(text));

    private void OnControlGranted() => _ui.Post(() =>
    {
        ControlPending = false;
        IsControlling = true;
        StatusText = "You are controlling this PC";
    });

    private void OnControlDenied() => _ui.Post(() =>
    {
        ControlPending = false;
        IsControlling = false;
        StatusText = "Live";
        Error = "The employee declined the remote-control request.";
    });

    private void OnFrame(byte[] bytes) => _ui.Post(() =>
    {
        FrameCount++;
        StatusText = "Live";
        FrameUpdated?.Invoke(bytes);
    });

    // Audio arrives on a background thread; forward it straight to the player (no UI marshalling needed).
    private void OnAudio(byte[] pcm, int sampleRate, int channels) => AudioUpdated?.Invoke(pcm, sampleRate, channels);

    private void OnStopped(string reason) => _ui.Post(() =>
    {
        IsLive = false;
        StatusText = "Stopped (" + reason + ")";
    });

    private void OnStatus(RealtimeStatus status) => _ui.Post(() =>
    {
        if (status == RealtimeStatus.Disconnected && IsLive)
        {
            StatusText = "Disconnected";
        }
    });

    [RelayCommand]
    public async Task StopAsync()
    {
        try
        {
            if (SessionId is { } id)
            {
                await _api.StopLiveViewAsync(id);
            }
        }
        catch (Exception)
        {
            // ignore; server may already have ended it
        }
        finally
        {
            await _signaling.StopAsync();
            Unsubscribe();
            IsLive = false;
            IsControlling = false;
            ControlPending = false;
            StatusText = "Stopped";
            SessionId = null;
        }
    }
}
