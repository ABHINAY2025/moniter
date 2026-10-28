namespace EmployeeMonitor.Admin.Core.Api;

// Response shapes from the backend (camelCase JSON). Only the fields the admin app uses are modelled.

public sealed record TokenResponse(
    string TokenType, string AccessToken, DateTimeOffset AccessTokenExpiresAt,
    string RefreshToken, DateTimeOffset RefreshTokenExpiresAt, UserInfo User);

public sealed record UserInfo(
    long Id, string Username, string FullName, string? Email, long? EmployeeId,
    IReadOnlyList<string> Roles, IReadOnlyList<string> Permissions)
{
    public bool Has(string permission) => Permissions.Contains(permission);
}

public sealed record PageResponse<T>(IReadOnlyList<T> Content, int Page, int Size, long TotalElements, int TotalPages);

public sealed record DepartmentDto(long Id, string Name);

public sealed record EmployeeDto(
    long Id, string EmployeeCode, string FullName, string? Email,
    DepartmentDto? Department, string? JobTitle, string Status);

/// <summary>Fields for creating an employee. EmployeeCode/FullName/Email are required by the backend.</summary>
public sealed record CreateEmployeeInput(string EmployeeCode, string FullName, string Email,
    long? DepartmentId = null, string? JobTitle = null);

public sealed record EmployeeSummaryDto(long Id, string EmployeeCode, string FullName, string? Department);

public sealed record PresenceDto(string Status, bool Online, DateTimeOffset? LastHeartbeatAt,
    string? CurrentApplication = null, string? Network = null, string? ZscalerStatus = null);

// ---- Milestone 4 monitoring ----

public sealed record UsageSlice(string Label, long? TotalSeconds, long? Sessions);

public sealed record UsageReport(long EmployeeId, string Date, string Timezone, long TotalSeconds,
    IReadOnlyList<UsageSlice> Items);

public sealed record UsbEventDto(long Id, string EventType, string? DeviceClass, string? VendorId, string? ProductId,
    string? SerialNumber, string? Description, DateTimeOffset OccurredAt);

public sealed record NetworkEventDto(long Id, string EventType, string? ConnectionType, string? NetworkName,
    string? IpAddress, DateTimeOffset OccurredAt);

public sealed record ZscalerEventDto(long Id, string Status, string? Detail, DateTimeOffset OccurredAt);

public sealed record ZscalerStatusDto(string CurrentStatus, DateTimeOffset? Since, IReadOnlyList<ZscalerEventDto> History);

public sealed record EmployeeDetailDto(EmployeeDto Employee, IReadOnlyList<DeviceDto> Devices);

public sealed record ScreenshotMetadataDto(long Id, DateTimeOffset CapturedAt, string ContentType,
    int? Width, int? Height, int SizeBytes);

public sealed record LiveViewSessionDto(Guid SessionId, long DeviceId, string DeviceCode, EmployeeSummaryDto? Employee,
    string Status, DateTimeOffset StartedAt, int MaxDurationSeconds, string SignalingPath);

public sealed record DeviceDto(
    long Id, string DeviceCode, string Hostname, string? OsVersion, string? AgentVersion,
    string? IpAddress, string Status, EmployeeSummaryDto? Employee, long? PolicyId,
    DateTimeOffset? LastSeenAt, PresenceDto Presence);

/// <summary>Result of minting a device enrollment token (the plaintext token is shown once).</summary>
public sealed record EnrollmentTokenCreated(long Id, string Token, string? Description, int MaxUses,
    DateTimeOffset ExpiresAt);

/// <summary>Server-controlled monitoring policy delivered to agents. Mirrors the backend PolicySettings record.</summary>
public sealed record PolicySettingsDto(
    bool SessionMonitoring,
    int IdleThresholdSeconds,
    bool ApplicationMonitoring,
    bool WebsiteMonitoring,
    bool UsbMonitoring,
    bool NetworkMonitoring,
    bool ZscalerMonitoring,
    bool Screenshots,
    int ScreenshotIntervalSeconds,
    bool LiveView,
    int LiveViewMaxMinutes,
    int HeartbeatIntervalSeconds,
    int PolicySyncIntervalSeconds,
    string MonitoringStart,
    string MonitoringEnd,
    IReadOnlyList<string> WorkDays,
    string Timezone,
    int RetentionDays,
    int ScreenshotRetentionDays);

public sealed record PolicyResponseDto(long PolicyId, string Name, string Source, string Revision,
    PolicySettingsDto Settings, DateTimeOffset UpdatedAt);

public sealed record DashboardSummary(
    long TotalEmployees, long Online, long Idle, long Locked, long Offline,
    long ActiveDevices, long OnlineDevices, long UnassignedDevices, DateTimeOffset GeneratedAt);

/// <summary>A realtime presence delta from the WebSocket stream.</summary>
public sealed record PresenceEvent(string Type, long DeviceId, long? EmployeeId, string? Status,
    string? PreviousStatus, DateTimeOffset At);

/// <summary>A suspicious-activity alert (USB inserted, flagged website, offline during work hours).</summary>
public sealed record AlertDto(long Id, string Category, string Severity, string Message, long? DeviceId,
    long? EmployeeId, string? DeviceCode, string? EmployeeName, DateTimeOffset CreatedAt, bool Acknowledged,
    DateTimeOffset? AcknowledgedAt);

// ---- Website restriction & access monitoring ----

/// <summary>One rule inside a web policy: a domain (or a category expansion) and what to do with it.</summary>
public sealed record WebRuleDto(long? Id, string? Domain, string? Category, string Action);

/// <summary>A web-access policy: ordered by priority, a set of ALLOW/BLOCK/MONITOR domain rules, assignable to devices/employees.</summary>
public sealed record WebPolicyDto(long Id, string Name, string? Description, bool Enabled, int Priority,
    DateTimeOffset? ValidFrom, DateTimeOffset? ValidTo, int Revision, IReadOnlyList<WebRuleDto> Rules,
    long AssignmentCount, DateTimeOffset CreatedAt, DateTimeOffset UpdatedAt);

/// <summary>Who a policy is applied to (a device or an employee).</summary>
public sealed record WebAssignmentDto(long Id, string TargetType, long TargetId, string? TargetLabel,
    DateTimeOffset CreatedAt);

/// <summary>A domain-level web-access observation, with the server's authoritative enforcement result.</summary>
public sealed record WebAccessEventDto(long Id, long? DeviceId, string? DeviceCode, long? EmployeeId,
    string? EmployeeName, string Domain, string? Browser, string EventType, string? EnforcementResult,
    long? MatchedPolicyId, string? Source, DateTimeOffset OccurredAt);

/// <summary>How web enforcement is going on a device (the browser policy state the agent last reported).</summary>
public sealed record WebEnforcementStatusDto(long DeviceId, string? Mechanism, bool Healthy, string? AppliedRevision,
    string? EffectiveRevision, string? Browsers, DateTimeOffset? LastSyncAt, DateTimeOffset? LastEventAt,
    string? LastError, bool Stale);

public sealed record DomainCountDto(string Domain, long Count);

public sealed record WebAccessSummaryDto(long Observed, long Allowed, long Blocked, long Monitored,
    long EnforcementErrors, IReadOnlyList<DomainCountDto> TopBlocked);

/// <summary>Form payload for creating/updating a web policy.</summary>
public sealed record WebPolicyInput(string Name, string? Description, bool Enabled, int Priority,
    bool OverrideCriticalDomains, IReadOnlyList<WebRuleDto> Rules);
