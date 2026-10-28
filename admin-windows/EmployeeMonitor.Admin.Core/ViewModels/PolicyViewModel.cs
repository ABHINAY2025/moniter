using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>
/// Edits the shared default monitoring policy (applies to every device unless a device override exists).
/// Numeric fields are doubles so they bind directly to NumberBox; they are cast back to ints when saving.
/// </summary>
public partial class PolicyViewModel : ObservableObject
{
    private readonly IAdminApi _api;

    public PolicyViewModel(IAdminApi api) => _api = api;

    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private bool _isSaving;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private string? _statusMessage;
    [ObservableProperty] private string _revision = "";

    // Monitoring categories
    [ObservableProperty] private bool _sessionMonitoring;
    [ObservableProperty] private bool _applicationMonitoring;
    [ObservableProperty] private bool _websiteMonitoring;
    [ObservableProperty] private bool _usbMonitoring;
    [ObservableProperty] private bool _networkMonitoring;
    [ObservableProperty] private bool _zscalerMonitoring;
    [ObservableProperty] private bool _screenshots;
    [ObservableProperty] private bool _liveView;

    // Numeric settings (doubles for NumberBox binding; ranges enforced by the backend)
    [ObservableProperty] private double _idleThresholdSeconds = 300;
    [ObservableProperty] private double _liveViewMaxMinutes = 30;
    [ObservableProperty] private double _heartbeatIntervalSeconds = 30;
    [ObservableProperty] private double _policySyncIntervalSeconds = 300;
    [ObservableProperty] private double _retentionDays = 90;
    [ObservableProperty] private double _screenshotRetentionDays = 30;
    [ObservableProperty] private int _screenshotIntervalSeconds = 900;

    // Monitoring window
    [ObservableProperty] private string _monitoringStart = "09:00";
    [ObservableProperty] private string _monitoringEnd = "18:00";
    [ObservableProperty] private string _timezone = "UTC";
    [ObservableProperty] private bool _workMonday;
    [ObservableProperty] private bool _workTuesday;
    [ObservableProperty] private bool _workWednesday;
    [ObservableProperty] private bool _workThursday;
    [ObservableProperty] private bool _workFriday;
    [ObservableProperty] private bool _workSaturday;
    [ObservableProperty] private bool _workSunday;

    /// <summary>Screenshot interval in minutes — the backend only accepts 5, 10, 15 or 30.</summary>
    public IReadOnlyList<int> ScreenshotIntervalMinutesOptions { get; } = new[] { 5, 10, 15, 30 };

    public int ScreenshotIntervalMinutes
    {
        get => ScreenshotIntervalSeconds <= 0 ? 15 : ScreenshotIntervalSeconds / 60;
        set => ScreenshotIntervalSeconds = value * 60;
    }

    partial void OnScreenshotIntervalSecondsChanged(int value) => OnPropertyChanged(nameof(ScreenshotIntervalMinutes));

    [RelayCommand]
    public async Task LoadAsync()
    {
        IsLoading = true;
        Error = null;
        StatusMessage = null;
        try
        {
            var p = await _api.GetDefaultPolicyAsync();
            Apply(p.Settings);
            Revision = p.Revision;
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not load the policy: " + ex.Message;
        }
        finally
        {
            IsLoading = false;
        }
    }

    [RelayCommand]
    public async Task SaveAsync()
    {
        IsSaving = true;
        Error = null;
        StatusMessage = null;
        try
        {
            var days = BuildWorkDays();
            if (days.Count == 0)
            {
                Error = "Select at least one work day.";
                return;
            }

            var settings = new PolicySettingsDto(
                SessionMonitoring, (int)IdleThresholdSeconds, ApplicationMonitoring, WebsiteMonitoring,
                UsbMonitoring, NetworkMonitoring, ZscalerMonitoring, Screenshots, ScreenshotIntervalSeconds,
                LiveView, (int)LiveViewMaxMinutes, (int)HeartbeatIntervalSeconds, (int)PolicySyncIntervalSeconds,
                MonitoringStart, MonitoringEnd, days, Timezone, (int)RetentionDays, (int)ScreenshotRetentionDays);

            var updated = await _api.UpdateDefaultPolicyAsync(settings);
            Apply(updated.Settings);
            Revision = updated.Revision;
            StatusMessage = "Saved. Agents apply the new policy within about 30 seconds.";
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not save the policy: " + ex.Message;
        }
        finally
        {
            IsSaving = false;
        }
    }

    private void Apply(PolicySettingsDto s)
    {
        SessionMonitoring = s.SessionMonitoring;
        ApplicationMonitoring = s.ApplicationMonitoring;
        WebsiteMonitoring = s.WebsiteMonitoring;
        UsbMonitoring = s.UsbMonitoring;
        NetworkMonitoring = s.NetworkMonitoring;
        ZscalerMonitoring = s.ZscalerMonitoring;
        Screenshots = s.Screenshots;
        LiveView = s.LiveView;
        IdleThresholdSeconds = s.IdleThresholdSeconds;
        ScreenshotIntervalSeconds = s.ScreenshotIntervalSeconds;
        LiveViewMaxMinutes = s.LiveViewMaxMinutes;
        HeartbeatIntervalSeconds = s.HeartbeatIntervalSeconds;
        PolicySyncIntervalSeconds = s.PolicySyncIntervalSeconds;
        RetentionDays = s.RetentionDays;
        ScreenshotRetentionDays = s.ScreenshotRetentionDays;
        MonitoringStart = s.MonitoringStart;
        MonitoringEnd = s.MonitoringEnd;
        Timezone = s.Timezone;

        var d = new HashSet<string>(s.WorkDays ?? Array.Empty<string>(), StringComparer.OrdinalIgnoreCase);
        WorkMonday = d.Contains("MONDAY");
        WorkTuesday = d.Contains("TUESDAY");
        WorkWednesday = d.Contains("WEDNESDAY");
        WorkThursday = d.Contains("THURSDAY");
        WorkFriday = d.Contains("FRIDAY");
        WorkSaturday = d.Contains("SATURDAY");
        WorkSunday = d.Contains("SUNDAY");
    }

    private List<string> BuildWorkDays()
    {
        var days = new List<string>();
        if (WorkMonday) days.Add("MONDAY");
        if (WorkTuesday) days.Add("TUESDAY");
        if (WorkWednesday) days.Add("WEDNESDAY");
        if (WorkThursday) days.Add("THURSDAY");
        if (WorkFriday) days.Add("FRIDAY");
        if (WorkSaturday) days.Add("SATURDAY");
        if (WorkSunday) days.Add("SUNDAY");
        return days;
    }
}
