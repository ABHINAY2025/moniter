using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>
/// Consolidated per-employee view for a given day: identity, devices, and the Milestone 4 monitoring categories
/// (application usage, website domains, USB events, network events, Zscaler status). All data is already
/// row-scoped server-side to what the signed-in user may see.
/// </summary>
public partial class EmployeeDetailViewModel : ObservableObject
{
    private readonly IAdminApi _api;

    public EmployeeDetailViewModel(IAdminApi api)
    {
        _api = api;
    }

    public long EmployeeId { get; private set; }

    [ObservableProperty] private string _title = "Employee";
    [ObservableProperty] private EmployeeDto? _employee;
    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private DateOnly _date = DateOnly.FromDateTime(DateTime.UtcNow);
    [ObservableProperty] private long _applicationsTotalSeconds;
    [ObservableProperty] private string _zscalerCurrent = "UNKNOWN";

    public string Timezone { get; set; } = "UTC";

    public ObservableCollection<DeviceRowViewModel> Devices { get; } = new();
    public ObservableCollection<UsageSlice> Applications { get; } = new();
    public ObservableCollection<UsageSlice> Websites { get; } = new();
    public ObservableCollection<UsbEventDto> UsbEvents { get; } = new();
    public ObservableCollection<NetworkEventDto> NetworkEvents { get; } = new();
    public ObservableCollection<ZscalerEventDto> ZscalerHistory { get; } = new();
    public ObservableCollection<ScreenshotMetadataDto> Screenshots { get; } = new();

    [RelayCommand]
    public async Task LoadAsync()
    {
        IsLoading = true;
        Error = null;
        try
        {
            var detail = await _api.GetEmployeeAsync(EmployeeId);
            Employee = detail.Employee;
            Title = detail.Employee.FullName;
            Replace(Devices, detail.Devices.Select(d => new DeviceRowViewModel(d)));

            var apps = await _api.GetApplicationsAsync(EmployeeId, Date, Timezone);
            ApplicationsTotalSeconds = apps.TotalSeconds;
            Replace(Applications, apps.Items);

            Replace(Websites, (await _api.GetWebsitesAsync(EmployeeId, Date, Timezone)).Items);
            Replace(UsbEvents, (await _api.GetUsbEventsAsync(EmployeeId, Date, Timezone)).Content);
            Replace(NetworkEvents, (await _api.GetNetworkEventsAsync(EmployeeId, Date, Timezone)).Content);

            var z = await _api.GetZscalerStatusAsync(EmployeeId);
            ZscalerCurrent = z.CurrentStatus;
            Replace(ZscalerHistory, z.History);

            Replace(Screenshots, (await _api.GetScreenshotsAsync(EmployeeId, Date, Timezone)).Content);
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not load employee details: " + ex.Message;
        }
        finally
        {
            IsLoading = false;
        }
    }

    public void Initialize(long employeeId)
    {
        EmployeeId = employeeId;
    }

    /// <summary>Fetches the full image bytes for the secure viewer (authorized + audited server-side).</summary>
    public Task<byte[]> GetScreenshotBytesAsync(long screenshotId) => _api.GetScreenshotBytesAsync(screenshotId);

    private static void Replace<T>(ObservableCollection<T> target, IEnumerable<T> items)
    {
        target.Clear();
        foreach (var item in items)
        {
            target.Add(item);
        }
    }
}
