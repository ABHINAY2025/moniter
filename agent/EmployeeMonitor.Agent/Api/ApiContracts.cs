using System.Text.Json.Serialization;

namespace EmployeeMonitor.Agent.Api;

// Wire contracts mirroring the backend DTOs. Property names serialize to camelCase (see BackendClient options).

public sealed record EmployeeSummary(
    long Id, string EmployeeCode, string FullName, string? Department);

public sealed record RegisterDeviceRequest(
    string EnrollmentToken, string Hostname, string? OsVersion, string AgentVersion,
    string? MacAddress, string? IpAddress, string? HardwareId);

public sealed record DeviceRegistrationResponse(
    long DeviceId, string DeviceCode, string DeviceToken, DateTimeOffset? DeviceTokenExpiresAt,
    EmployeeSummary? Employee, string PolicyRevision, PolicySettings Policy);

public sealed record HeartbeatRequest(
    string Status, DateTimeOffset Timestamp, string? AgentVersion, string? IpAddress,
    string? CurrentApplication = null, string? Network = null, string? ZscalerStatus = null);

public sealed record HeartbeatResponse(
    DateTimeOffset ServerTime, string PolicyRevision, int NextHeartbeatSeconds);

public sealed record PolicyResponse(
    long PolicyId, string Name, string Source, string Revision, PolicySettings Settings, DateTimeOffset UpdatedAt);

public sealed record AgentIdentityResponse(
    long DeviceId, string DeviceCode, string Hostname, EmployeeSummary? Employee, string PolicyRevision);

public sealed record AgentLiveViewStatus(bool Active, Guid? SessionId);

public sealed record UpdateAvailable(
    string Version, string Sha256, string Signature, bool Mandatory, long SizeBytes, string DownloadPath);

public sealed record SessionEventInput(
    [property: JsonPropertyName("clientEventId")] Guid ClientEventId,
    [property: JsonPropertyName("type")] string Type,
    [property: JsonPropertyName("occurredAt")] DateTimeOffset OccurredAt);

public sealed record SessionIngestRequest(IReadOnlyList<SessionEventInput> Events);

public sealed record SessionIngestResponse(int Accepted, int Duplicates, int Rejected);

/// <summary>Monitoring policy as delivered by the backend. All categories can be toggled independently.</summary>
public sealed record PolicySettings(
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
