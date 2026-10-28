using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>Paged, searchable device inventory with live presence from the realtime stream.</summary>
public partial class DevicesViewModel : ObservableObject, IDisposable
{
    private const int PageSize = 50;
    private readonly IAdminApi _api;
    private readonly IRealtimeClient _realtime;
    private readonly IUiDispatcher _ui;
    private bool _subscribed;

    public DevicesViewModel(IAdminApi api, IRealtimeClient realtime, IUiDispatcher ui)
    {
        _api = api;
        _realtime = realtime;
        _ui = ui;
    }

    public ObservableCollection<DeviceRowViewModel> Devices { get; } = new();

    [ObservableProperty] private string _searchText = "";
    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private int _page;
    [ObservableProperty] private int _totalPages;
    [ObservableProperty] private long _totalElements;

    public void Attach()
    {
        if (!_subscribed)
        {
            _realtime.PresenceReceived += OnPresence;
            _subscribed = true;
        }
    }

    [RelayCommand]
    public async Task LoadAsync()
    {
        IsLoading = true;
        Error = null;
        try
        {
            var result = await _api.GetDevicesAsync(SearchText, Page, PageSize);
            Devices.Clear();
            foreach (var d in result.Content)
            {
                Devices.Add(new DeviceRowViewModel(d));
            }
            TotalPages = result.TotalPages;
            TotalElements = result.TotalElements;
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not load devices: " + ex.Message;
        }
        finally
        {
            IsLoading = false;
        }
    }

    [RelayCommand]
    private Task SearchAsync()
    {
        Page = 0;
        return LoadAsync();
    }

    [RelayCommand(CanExecute = nameof(CanNext))]
    private Task NextPageAsync()
    {
        Page++;
        return LoadAsync();
    }

    [RelayCommand(CanExecute = nameof(CanPrev))]
    private Task PrevPageAsync()
    {
        Page--;
        return LoadAsync();
    }

    private bool CanNext() => Page + 1 < TotalPages;
    private bool CanPrev() => Page > 0;

    /// <summary>Mints a fresh enrollment token for onboarding a new device. Returns null and sets Error on failure.</summary>
    public async Task<EnrollmentTokenCreated?> CreateDeviceTokenAsync(int maxUses = 5, int expiresInHours = 72)
    {
        Error = null;
        try
        {
            return await _api.CreateEnrollmentTokenAsync("Added from admin app", maxUses, expiresInHours);
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not create enrollment token: " + ex.Message;
            return null;
        }
    }

    private void OnPresence(PresenceEvent evt) => _ui.Post(() =>
    {
        var row = Devices.FirstOrDefault(r => r.DeviceId == evt.DeviceId);
        row?.Apply(evt);
    });

    partial void OnPageChanged(int value) => NotifyPaging();
    partial void OnTotalPagesChanged(int value) => NotifyPaging();

    private void NotifyPaging()
    {
        NextPageCommand.NotifyCanExecuteChanged();
        PrevPageCommand.NotifyCanExecuteChanged();
    }

    public void Dispose()
    {
        if (_subscribed)
        {
            _realtime.PresenceReceived -= OnPresence;
            _subscribed = false;
        }
    }
}
