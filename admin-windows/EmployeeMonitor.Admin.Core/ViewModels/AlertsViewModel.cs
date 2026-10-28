using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>
/// App-wide feed of unacknowledged suspicious-activity alerts. Loaded once at sign-in, then kept live from the
/// realtime stream. The nav badge binds to <see cref="UnacknowledgedCount"/>; the alerts page binds to the list.
/// </summary>
public partial class AlertsViewModel : ObservableObject
{
    private readonly IAdminApi _api;
    private readonly IRealtimeClient _realtime;
    private readonly IUiDispatcher _ui;
    private bool _subscribed;

    public AlertsViewModel(IAdminApi api, IRealtimeClient realtime, IUiDispatcher ui)
    {
        _api = api;
        _realtime = realtime;
        _ui = ui;
    }

    public ObservableCollection<AlertRowViewModel> Alerts { get; } = new();

    [ObservableProperty] private int _unacknowledgedCount;
    [ObservableProperty] private bool _hasAlerts;
    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private string? _error;

    public void Start()
    {
        if (!_subscribed)
        {
            _realtime.AlertReceived += OnAlert;
            _subscribed = true;
        }
    }

    public void Stop()
    {
        if (_subscribed)
        {
            _realtime.AlertReceived -= OnAlert;
            _subscribed = false;
        }
        Alerts.Clear();
        UnacknowledgedCount = 0;
        HasAlerts = false;
    }

    [RelayCommand]
    public async Task LoadAsync()
    {
        IsLoading = true;
        Error = null;
        try
        {
            var result = await _api.GetAlertsAsync(unacknowledged: true, 0, 100);
            Alerts.Clear();
            foreach (var a in result.Content)
            {
                Alerts.Add(new AlertRowViewModel(a));
            }
            UnacknowledgedCount = (int)result.TotalElements;
            HasAlerts = Alerts.Count > 0;
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not load alerts: " + ex.Message;
        }
        finally
        {
            IsLoading = false;
        }
    }

    private void OnAlert(AlertDto a) => _ui.Post(() =>
    {
        Alerts.Insert(0, new AlertRowViewModel(a));
        UnacknowledgedCount++;
        HasAlerts = true;
    });

    [RelayCommand]
    private async Task AcknowledgeAsync(AlertRowViewModel? row)
    {
        if (row is null)
        {
            return;
        }
        try
        {
            await _api.AcknowledgeAlertAsync(row.Id);
            Alerts.Remove(row);
            if (UnacknowledgedCount > 0)
            {
                UnacknowledgedCount--;
            }
            HasAlerts = Alerts.Count > 0;
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not acknowledge alert: " + ex.Message;
        }
    }
}
