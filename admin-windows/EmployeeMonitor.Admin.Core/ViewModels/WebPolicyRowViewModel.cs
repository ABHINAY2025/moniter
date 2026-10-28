using CommunityToolkit.Mvvm.ComponentModel;
using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>One web-access policy in the policies list, with its domain rules split by action for the editor.</summary>
public sealed class WebPolicyRowViewModel : ObservableObject
{
    public long Id { get; }
    public string Name { get; }
    public string? Description { get; }
    public bool Enabled { get; }
    public int Priority { get; }
    public int Revision { get; }
    public long AssignmentCount { get; }

    public IReadOnlyList<string> BlockDomains { get; }
    public IReadOnlyList<string> AllowDomains { get; }
    public IReadOnlyList<string> MonitorDomains { get; }

    /// <summary>Non-domain (category) rules, preserved verbatim so editing the domain lists never drops them.</summary>
    public IReadOnlyList<WebRuleDto> CategoryRules { get; }

    public WebPolicyRowViewModel(WebPolicyDto dto)
    {
        Id = dto.Id;
        Name = dto.Name;
        Description = dto.Description;
        Enabled = dto.Enabled;
        Priority = dto.Priority;
        Revision = dto.Revision;
        AssignmentCount = dto.AssignmentCount;

        BlockDomains = Domains(dto, "BLOCK");
        AllowDomains = Domains(dto, "ALLOW");
        MonitorDomains = Domains(dto, "MONITOR");
        CategoryRules = dto.Rules
            .Where(r => string.IsNullOrWhiteSpace(r.Domain) && !string.IsNullOrWhiteSpace(r.Category))
            .ToList();
    }

    private static List<string> Domains(WebPolicyDto dto, string action) =>
        dto.Rules
            .Where(r => string.Equals(r.Action, action, StringComparison.OrdinalIgnoreCase)
                        && !string.IsNullOrWhiteSpace(r.Domain))
            .Select(r => r.Domain!.Trim())
            .ToList();

    public string RuleSummary
    {
        get
        {
            var s = $"{BlockDomains.Count} blocked · {AllowDomains.Count} allowed · {MonitorDomains.Count} monitored";
            return CategoryRules.Count > 0 ? $"{s} · {CategoryRules.Count} category" : s;
        }
    }

    /// <summary>A short note naming the category rules, shown read-only in the editor so they are never silently lost.</summary>
    public string? CategoryNote => CategoryRules.Count == 0
        ? null
        : "Also keeps category rules: " + string.Join(", ",
            CategoryRules.Select(r => $"{r.Category} ({r.Action})"));

    public string StatusLabel => Enabled ? "Enabled" : "Disabled";

    public string AssignmentSummary => AssignmentCount switch
    {
        0 => "Not assigned",
        1 => "1 assignment",
        _ => $"{AssignmentCount} assignments",
    };
}
