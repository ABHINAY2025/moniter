namespace EmployeeMonitor.Admin.Core.Api;

/// <summary>Backend operations the admin UI uses. Abstracted so view models can be unit-tested with a fake.</summary>
public interface IAdminApi
{
    Task<TokenResponse> LoginAsync(string username, string password, CancellationToken ct = default);
    Task LogoutAsync(CancellationToken ct = default);
    Task<DashboardSummary> GetDashboardSummaryAsync(CancellationToken ct = default);
    Task<PageResponse<EmployeeDto>> GetEmployeesAsync(string? query, int page, int size, CancellationToken ct = default);
    Task<EmployeeDto> CreateEmployeeAsync(CreateEmployeeInput input, CancellationToken ct = default);
    Task<PageResponse<DeviceDto>> GetDevicesAsync(string? query, int page, int size, CancellationToken ct = default);
    Task<DeviceDto> GetDeviceAsync(long id, CancellationToken ct = default);

    Task<EmployeeDetailDto> GetEmployeeAsync(long id, CancellationToken ct = default);
    Task<UsageReport> GetApplicationsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default);
    Task<UsageReport> GetWebsitesAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default);
    Task<PageResponse<UsbEventDto>> GetUsbEventsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default);
    Task<PageResponse<NetworkEventDto>> GetNetworkEventsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default);
    Task<ZscalerStatusDto> GetZscalerStatusAsync(long employeeId, CancellationToken ct = default);
    Task<PageResponse<ScreenshotMetadataDto>> GetScreenshotsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default);
    Task<byte[]> GetScreenshotBytesAsync(long screenshotId, CancellationToken ct = default);

    Task<LiveViewSessionDto> StartLiveViewAsync(long deviceId, CancellationToken ct = default);
    Task StopLiveViewAsync(Guid sessionId, CancellationToken ct = default);

    Task<EnrollmentTokenCreated> CreateEnrollmentTokenAsync(string? description, int maxUses, int expiresInHours,
        CancellationToken ct = default);

    Task<DeviceDto> AssignDeviceAsync(long deviceId, long employeeId, CancellationToken ct = default);

    Task<PageResponse<AlertDto>> GetAlertsAsync(bool unacknowledged, int page, int size, CancellationToken ct = default);
    Task<AlertDto> AcknowledgeAlertAsync(long alertId, CancellationToken ct = default);

    // Website restriction & access monitoring
    Task<PageResponse<WebPolicyDto>> GetWebPoliciesAsync(string? query, int page, int size, CancellationToken ct = default);
    Task<WebPolicyDto> CreateWebPolicyAsync(WebPolicyInput input, CancellationToken ct = default);
    Task<WebPolicyDto> UpdateWebPolicyAsync(long id, WebPolicyInput input, CancellationToken ct = default);
    Task DeleteWebPolicyAsync(long id, CancellationToken ct = default);

    Task<IReadOnlyList<WebAssignmentDto>> GetWebPolicyAssignmentsAsync(long policyId, CancellationToken ct = default);
    Task<WebAssignmentDto> AssignWebPolicyAsync(long policyId, string targetType, long targetId, CancellationToken ct = default);
    Task UnassignWebPolicyAsync(long policyId, long assignmentId, CancellationToken ct = default);

    Task<PageResponse<WebAccessEventDto>> GetWebAccessEventsAsync(long? employeeId, string? result, string? domain,
        int page, int size, CancellationToken ct = default);
    Task<WebAccessSummaryDto> GetWebAccessSummaryAsync(int days, CancellationToken ct = default);
    Task<WebEnforcementStatusDto> GetWebEnforcementStatusAsync(long deviceId, CancellationToken ct = default);
}
