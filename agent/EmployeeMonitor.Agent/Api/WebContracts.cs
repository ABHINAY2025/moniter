namespace EmployeeMonitor.Agent.Api;

/// <summary>Effective web-access policy for this device: domain lists to block/allow/monitor + a sync revision hash.</summary>
public sealed record EffectiveWebPolicy(
    string Revision,
    IReadOnlyList<string> Block,
    IReadOnlyList<string> Allow,
    IReadOnlyList<string> Monitor);

/// <summary>A domain-level web-access observation reported by the device (the server sets the enforcement result).</summary>
public sealed record WebAccessEventInput(
    Guid ClientEventId,
    string Domain,
    string? Browser,
    string EventType,
    string Source,
    DateTimeOffset OccurredAt);

public sealed record WebAccessBatch(IReadOnlyList<WebAccessEventInput> Events);

public sealed record WebAccessIngestResult(int Accepted, int Duplicates, int Rejected);

/// <summary>Device -> server: how web enforcement is going on this machine.</summary>
public sealed record WebEnforcementReport(
    string Mechanism,
    bool Healthy,
    string? AppliedRevision,
    string? Browsers,
    string? Error);
