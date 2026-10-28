using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>One domain-level web-access observation in the activity feed.</summary>
public sealed class WebEventRowViewModel
{
    public long Id { get; }
    public string Domain { get; }
    public string EventType { get; }
    public string? EnforcementResult { get; }
    public string Subject { get; }
    public DateTimeOffset OccurredAt { get; }

    public WebEventRowViewModel(WebAccessEventDto e)
    {
        Id = e.Id;
        Domain = e.Domain;
        EventType = e.EventType;
        EnforcementResult = e.EnforcementResult;
        OccurredAt = e.OccurredAt;
        string who = string.IsNullOrWhiteSpace(e.EmployeeName) ? "(unassigned)" : e.EmployeeName!;
        Subject = string.IsNullOrWhiteSpace(e.DeviceCode) ? who : $"{who} · {e.DeviceCode}";
    }

    /// <summary>A short human label for the outcome, driven by the server-authoritative result then event type.</summary>
    public string ResultLabel => (EnforcementResult ?? EventType) switch
    {
        "BLOCKED" or "ACCESS_BLOCKED" => "Blocked",
        "ALLOWED" or "ACCESS_ALLOWED" => "Allowed",
        "MONITORED" or "NAVIGATION_OBSERVED" => "Observed",
        "ENFORCEMENT_ERROR" => "Enforcement error",
        _ => EventType,
    };

    public bool IsBlocked =>
        EnforcementResult == "BLOCKED" || EventType is "ACCESS_BLOCKED";
}
