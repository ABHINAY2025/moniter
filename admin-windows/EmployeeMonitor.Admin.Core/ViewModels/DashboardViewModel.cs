using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>
/// Headline counts and a live device table. The REST snapshot seeds state; realtime deltas update rows and
/// recompute the employee-level counts the same way the server does (best status per employee). On reconnect
/// the snapshot is refetched to close any gap.
/// </summary>
public partial class DashboardViewModel : ObservableObject, IDisposable
{
    private readonly IAdminApi _api;
    private readonly IRealtimeClient _realtime;
    private readonly IUiDispatcher _ui;
    private long _totalEmployees;
    private bool _subscribed;

    public DashboardViewModel(IAdminApi api, IRealtimeClient realtime, IUiDispatcher ui)
    {
        _api = api;
        _realtime = realtime;
        _ui = ui;
    }

    public ObservableCollection<DeviceRowViewModel> Devices { get; } = new();

    [ObservableProperty] private long _totalEmployeesDisplay;
    [ObservableProperty] private long _online;
    [ObservableProperty] private long _idle;
    [ObservableProperty] private long _locked;
    [ObservableProperty] private long _offline;
    [ObservableProperty] private long _activeDevices;
    [ObservableProperty] private long _onlineDevices;
    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private string _connectionStatus = "Disconnected";

    public void Attach()
    {
        if (_subscribed)
        {
            return;
        }
        _realtime.PresenceReceived += OnPresence;
        _realtime.StatusChanged += OnRealtimeStatus;
        _subscribed = true;
    }

    [RelayCommand]
    public async Task LoadAsync()
    {
        IsLoading = true;
        Error = null;
        try
        {
            var summary = await _api.GetDashboardSummaryAsync();
            var devices = await _api.GetDevicesAsync(null, 0, 200);

            _totalEmployees = summary.TotalEmployees;
            TotalEmployeesDisplay = summary.TotalEmployees;
            ActiveDevices = summary.ActiveDevices;

            Devices.Clear();
            foreach (var d in devices.Content.OrderBy(d => d.Hostname))
            {
                Devices.Add(new DeviceRowViewModel(d));
            }
            RecomputeCounts();
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not load the dashboard: " + ex.Message;
        }
        finally
        {
            IsLoading = false;
        }
    }

    private void OnPresence(PresenceEvent evt) => _ui.Post(() =>
    {
        var row = Devices.FirstOrDefault(r => r.DeviceId == evt.DeviceId);
        if (row is not null)
        {
            row.Apply(evt);
            RecomputeCounts();
        }
        // A device not in the current snapshot (newly enrolled) will appear on the next reconnect/refresh.
    });

    private void OnRealtimeStatus(RealtimeStatus status) => _ui.Post(async void () =>
    {
        ConnectionStatus = status.ToString();
        if (status == RealtimeStatus.Connected)
        {
            await LoadAsync();
        }
    });

    /// <summary>Employee-level counts mirror the server: an employee's state is the best among their devices.</summary>
    private void RecomputeCounts()
    {
        OnlineDevices = Devices.Count(d => d.Online);

        var best = new Dictionary<long, int>();
        foreach (var d in Devices.Where(d => d.EmployeeId is not null))
        {
            int rank = StatusRank(d.Status);
            long id = d.EmployeeId!.Value;
            if (!best.TryGetValue(id, out var cur) || rank > cur)
            {
                best[id] = rank;
            }
        }
        Online = best.Values.Count(r => r == 3);
        Idle = best.Values.Count(r => r == 2);
        Locked = best.Values.Count(r => r == 1);
        Offline = Math.Max(0, _totalEmployees - Online - Idle - Locked);
    }

    private static int StatusRank(string status) => status.ToUpperInvariant() switch
    {
        "ACTIVE" => 3,
        "IDLE" => 2,
        "LOCKED" => 1,
        _ => 0,
    };

    public void Dispose()
    {
        if (_subscribed)
        {
            _realtime.PresenceReceived -= OnPresence;
            _realtime.StatusChanged -= OnRealtimeStatus;
            _subscribed = false;
        }
    }
}
