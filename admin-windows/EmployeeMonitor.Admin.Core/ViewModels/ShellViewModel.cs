using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>App-level state after sign-in: current user, realtime connection status, navigation gating, logout.</summary>
public partial class ShellViewModel : ObservableObject
{
    private readonly IAdminApi _api;
    private readonly IRealtimeClient _realtime;
    private readonly SessionState _session;
    private readonly IUiDispatcher _ui;

    public ShellViewModel(IAdminApi api, IRealtimeClient realtime, SessionState session, IUiDispatcher ui)
    {
        _api = api;
        _realtime = realtime;
        _session = session;
        _ui = ui;
        _realtime.StatusChanged += s => _ui.Post(() => ConnectionStatus = s.ToString());
    }

    [ObservableProperty] private string _userName = "";
    [ObservableProperty] private string _roles = "";
    [ObservableProperty] private string _connectionStatus = "Disconnected";

    public bool CanManageUsers => _session.User?.Has("USER_MANAGE") ?? false;
    public bool CanReadAudit => _session.User?.Has("AUDIT_READ") ?? false;
    public bool CanReadDevices => _session.User?.Has("DEVICE_READ") ?? false;

    public event Action? LoggedOut;

    public async Task OnSignedInAsync()
    {
        var user = _session.User;
        UserName = user?.FullName ?? user?.Username ?? "";
        Roles = user is null ? "" : string.Join(", ", user.Roles);
        OnPropertyChanged(nameof(CanManageUsers));
        OnPropertyChanged(nameof(CanReadAudit));
        OnPropertyChanged(nameof(CanReadDevices));
        await _realtime.StartAsync();
    }

    [RelayCommand]
    private async Task LogoutAsync()
    {
        await _realtime.StopAsync();
        await _api.LogoutAsync();
        LoggedOut?.Invoke();
    }
}
