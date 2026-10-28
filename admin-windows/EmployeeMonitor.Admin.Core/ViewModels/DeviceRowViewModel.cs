using CommunityToolkit.Mvvm.ComponentModel;
using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>One row in the devices / dashboard table. Presence fields update live from realtime deltas.</summary>
public partial class DeviceRowViewModel : ObservableObject
{
    public long DeviceId { get; }
    public string DeviceCode { get; }
    public string Hostname { get; }
    public long? EmployeeId { get; private set; }

    [ObservableProperty] private string _employeeName;
    [ObservableProperty] private string? _department;
    [ObservableProperty] private string _status;
    [ObservableProperty] private bool _online;
    [ObservableProperty] private DateTimeOffset? _lastSeenAt;
    [ObservableProperty] private string _deviceStatus;
    [ObservableProperty] private string? _currentApplication;
    [ObservableProperty] private string? _network;
    [ObservableProperty] private string? _zscalerStatus;

    public DeviceRowViewModel(DeviceDto device)
    {
        DeviceId = device.Id;
        DeviceCode = device.DeviceCode;
        Hostname = device.Hostname;
        EmployeeId = device.Employee?.Id;
        _employeeName = device.Employee?.FullName ?? "(unassigned)";
        _department = device.Employee?.Department;
        _status = device.Presence.Status;
        _online = device.Presence.Online;
        _lastSeenAt = device.LastSeenAt;
        _deviceStatus = device.Status;
        _currentApplication = device.Presence.CurrentApplication;
        _network = device.Presence.Network;
        _zscalerStatus = device.Presence.ZscalerStatus;
    }

    public void Apply(PresenceEvent evt)
    {
        Status = evt.Status ?? (evt.Type == "DEVICE_OFFLINE" ? "OFFLINE" : Status);
        Online = !string.Equals(Status, "OFFLINE", StringComparison.OrdinalIgnoreCase);
        LastSeenAt = evt.At;
        if (!Online)
        {
            CurrentApplication = null;
        }
    }
}
