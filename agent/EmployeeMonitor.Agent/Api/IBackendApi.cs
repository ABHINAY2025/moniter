namespace EmployeeMonitor.Agent.Api;

/// <summary>Backend surface the agent depends on. Abstracted so the orchestrator can be unit-tested with a fake.</summary>
public interface IBackendApi
{
    Task<DeviceRegistrationResponse> RegisterAsync(RegisterDeviceRequest request, CancellationToken ct);
    Task<HeartbeatResponse> HeartbeatAsync(HeartbeatRequest request, string deviceToken, CancellationToken ct);
    Task<PolicyResponse> GetPolicyAsync(string deviceToken, CancellationToken ct);
    Task<AgentIdentityResponse> GetIdentityAsync(string deviceToken, CancellationToken ct);
    Task<SessionIngestResponse> PostSessionEventsAsync(SessionIngestRequest request, string deviceToken, CancellationToken ct);

    Task<MonIngestResult> PostApplicationUsageAsync(MonBatch<MonAppUsage> batch, string deviceToken, CancellationToken ct);
    Task<MonIngestResult> PostWebsiteUsageAsync(MonBatch<MonWebsiteUsage> batch, string deviceToken, CancellationToken ct);
    Task<MonIngestResult> PostUsbEventsAsync(MonBatch<MonUsbEvent> batch, string deviceToken, CancellationToken ct);
    Task<MonIngestResult> PostNetworkEventsAsync(MonBatch<MonNetworkEvent> batch, string deviceToken, CancellationToken ct);
    Task<MonIngestResult> PostZscalerEventsAsync(MonBatch<MonZscalerEvent> batch, string deviceToken, CancellationToken ct);

    Task<MonScreenshotResult> PostScreenshotAsync(MonScreenshot screenshot, string deviceToken, CancellationToken ct);

    Task<UpdateAvailable?> CheckUpdateAsync(string currentVersion, string deviceToken, CancellationToken ct);
    Task<byte[]> DownloadUpdateAsync(string path, string deviceToken, CancellationToken ct);

    Task<EffectiveWebPolicy> GetWebPolicyAsync(string deviceToken, CancellationToken ct);
    Task<WebAccessIngestResult> PostWebAccessEventsAsync(WebAccessBatch batch, string deviceToken, CancellationToken ct);
    Task PostWebEnforcementStatusAsync(WebEnforcementReport report, string deviceToken, CancellationToken ct);
}
