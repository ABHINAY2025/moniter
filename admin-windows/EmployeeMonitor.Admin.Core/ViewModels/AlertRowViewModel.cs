using CommunityToolkit.Mvvm.ComponentModel;
using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>One suspicious-activity alert in the alerts feed.</summary>
public partial class AlertRowViewModel : ObservableObject
{
    public long Id { get; }
    public string Category { get; }
    public string Severity { get; }
    public string Message { get; }
    public string Subject { get; }
    public DateTimeOffset CreatedAt { get; }

    [ObservableProperty] private bool _acknowledged;

    public AlertRowViewModel(AlertDto a)
    {
        Id = a.Id;
        Category = a.Category;
        Severity = a.Severity;
        Message = a.Message;
        CreatedAt = a.CreatedAt;
        _acknowledged = a.Acknowledged;
        string who = string.IsNullOrWhiteSpace(a.EmployeeName) ? "(unassigned)" : a.EmployeeName!;
        Subject = string.IsNullOrWhiteSpace(a.DeviceCode) ? who : $"{who} · {a.DeviceCode}";
    }

    /// <summary>A short human label for the rule that fired.</summary>
    public string CategoryLabel => Category switch
    {
        "USB_CONNECTED" => "USB device",
        "FLAGGED_WEBSITE" => "Flagged site",
        "OFFLINE_DURING_WORK" => "Offline in work hours",
        _ => Category,
    };
}
