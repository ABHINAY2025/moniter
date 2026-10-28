using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Agent.Api;

/// <summary>
/// Thin typed client over the backend HTTP API. The device token is supplied per call so the same client
/// can be used before enrollment (register) and after (authenticated calls).
/// </summary>
public sealed class BackendClient : IBackendApi
{
    public static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull,
    };

    private readonly HttpClient _http;
    private readonly ILogger<BackendClient> _logger;

    public BackendClient(HttpClient http, ILogger<BackendClient> logger)
    {
        _http = http;
        _logger = logger;
    }

    public Task<DeviceRegistrationResponse> RegisterAsync(RegisterDeviceRequest request, CancellationToken ct) =>
        SendAsync<DeviceRegistrationResponse>(HttpMethod.Post, "/api/devices/register", null, request, ct);

    public Task<HeartbeatResponse> HeartbeatAsync(HeartbeatRequest request, string deviceToken, CancellationToken ct) =>
        SendAsync<HeartbeatResponse>(HttpMethod.Post, "/api/agent/heartbeat", deviceToken, request, ct);

    public Task<PolicyResponse> GetPolicyAsync(string deviceToken, CancellationToken ct) =>
        SendAsync<PolicyResponse>(HttpMethod.Get, "/api/agent/policy", deviceToken, null, ct);

    public Task<AgentIdentityResponse> GetIdentityAsync(string deviceToken, CancellationToken ct) =>
        SendAsync<AgentIdentityResponse>(HttpMethod.Get, "/api/agent/me", deviceToken, null, ct);

    public Task<SessionIngestResponse> PostSessionEventsAsync(
        SessionIngestRequest request, string deviceToken, CancellationToken ct) =>
        SendAsync<SessionIngestResponse>(HttpMethod.Post, "/api/agent/session-events", deviceToken, request, ct);

    public Task<MonIngestResult> PostApplicationUsageAsync(MonBatch<MonAppUsage> batch, string deviceToken, CancellationToken ct) =>
        SendAsync<MonIngestResult>(HttpMethod.Post, "/api/agent/application-usage", deviceToken, batch, ct);

    public Task<MonIngestResult> PostWebsiteUsageAsync(MonBatch<MonWebsiteUsage> batch, string deviceToken, CancellationToken ct) =>
        SendAsync<MonIngestResult>(HttpMethod.Post, "/api/agent/website-usage", deviceToken, batch, ct);

    public Task<MonIngestResult> PostUsbEventsAsync(MonBatch<MonUsbEvent> batch, string deviceToken, CancellationToken ct) =>
        SendAsync<MonIngestResult>(HttpMethod.Post, "/api/agent/usb-events", deviceToken, batch, ct);

    public Task<MonIngestResult> PostNetworkEventsAsync(MonBatch<MonNetworkEvent> batch, string deviceToken, CancellationToken ct) =>
        SendAsync<MonIngestResult>(HttpMethod.Post, "/api/agent/network-events", deviceToken, batch, ct);

    public Task<MonIngestResult> PostZscalerEventsAsync(MonBatch<MonZscalerEvent> batch, string deviceToken, CancellationToken ct) =>
        SendAsync<MonIngestResult>(HttpMethod.Post, "/api/agent/zscaler-events", deviceToken, batch, ct);

    public Task<MonScreenshotResult> PostScreenshotAsync(MonScreenshot screenshot, string deviceToken, CancellationToken ct) =>
        SendAsync<MonScreenshotResult>(HttpMethod.Post, "/api/agent/screenshots", deviceToken, screenshot, ct);

    /// <summary>Used by the tray to show the on-device live-view indicator.</summary>
    public Task<AgentLiveViewStatus> GetLiveViewStatusAsync(string deviceToken, CancellationToken ct) =>
        SendAsync<AgentLiveViewStatus>(HttpMethod.Get, "/api/agent/live-view/status", deviceToken, null, ct);

    public async Task<UpdateAvailable?> CheckUpdateAsync(string currentVersion, string deviceToken, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get,
            "/api/agent/update?current=" + Uri.EscapeDataString(currentVersion));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", deviceToken);
        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            throw new BackendException("Network error checking for updates: " + ex.Message, inner: ex);
        }
        using (response)
        {
            if (response.StatusCode == System.Net.HttpStatusCode.NoContent)
            {
                return null;
            }
            if (response.IsSuccessStatusCode)
            {
                return await response.Content.ReadFromJsonAsync<UpdateAvailable>(JsonOptions, ct);
            }
            await ThrowForError(response, "/api/agent/update", ct);
            return null;
        }
    }

    public async Task<byte[]> DownloadUpdateAsync(string path, string deviceToken, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get, path);
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", deviceToken);
        using var response = await _http.SendAsync(request, ct);
        if (!response.IsSuccessStatusCode)
        {
            await ThrowForError(response, path, ct);
        }
        return await response.Content.ReadAsByteArrayAsync(ct);
    }

    public Task<EffectiveWebPolicy> GetWebPolicyAsync(string deviceToken, CancellationToken ct) =>
        SendAsync<EffectiveWebPolicy>(HttpMethod.Get, "/api/agent/web-policy", deviceToken, null, ct);

    public Task<WebAccessIngestResult> PostWebAccessEventsAsync(WebAccessBatch batch, string deviceToken, CancellationToken ct) =>
        SendAsync<WebAccessIngestResult>(HttpMethod.Post, "/api/agent/web-access-events/batch", deviceToken, batch, ct);

    public Task PostWebEnforcementStatusAsync(WebEnforcementReport report, string deviceToken, CancellationToken ct) =>
        SendVoidAsync(HttpMethod.Post, "/api/agent/web-enforcement-status", deviceToken, report, ct);

    private async Task SendVoidAsync(HttpMethod method, string path, string? bearer, object? body, CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, path);
        if (bearer is not null)
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
        }
        if (body is not null)
        {
            request.Content = JsonContent.Create(body, body.GetType(), options: JsonOptions);
        }
        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            throw new BackendException($"Network error calling {path}: {ex.Message}", inner: ex);
        }
        using (response)
        {
            if (!response.IsSuccessStatusCode)
            {
                await ThrowForError(response, path, ct);
            }
        }
    }

    private async Task<T> SendAsync<T>(HttpMethod method, string path, string? bearer, object? body,
        CancellationToken ct)
    {
        using var request = new HttpRequestMessage(method, path);
        if (bearer is not null)
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
        }
        if (body is not null)
        {
            request.Content = JsonContent.Create(body, body.GetType(), options: JsonOptions);
        }

        HttpResponseMessage response;
        try
        {
            response = await _http.SendAsync(request, ct);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            throw new BackendException($"Network error calling {path}: {ex.Message}", inner: ex);
        }

        using (response)
        {
            if (response.IsSuccessStatusCode)
            {
                var result = await response.Content.ReadFromJsonAsync<T>(JsonOptions, ct);
                return result ?? throw new BackendException($"Empty response from {path}");
            }
            await ThrowForError(response, path, ct);
            throw new InvalidOperationException("unreachable");
        }
    }

    private async Task ThrowForError(HttpResponseMessage response, string path, CancellationToken ct)
    {
        string? code = null;
        string detail = response.ReasonPhrase ?? "error";
        try
        {
            var problem = await response.Content.ReadFromJsonAsync<ProblemDocument>(JsonOptions, ct);
            if (problem is not null)
            {
                code = problem.Code;
                detail = problem.Detail ?? detail;
            }
        }
        catch (Exception)
        {
            // Non-JSON error body; keep the reason phrase.
        }
        _logger.LogWarning("Backend {Path} failed: {Status} {Code} {Detail}",
            path, (int)response.StatusCode, code, detail);
        throw new BackendException($"{path} failed: {detail}", response.StatusCode, code);
    }

    private sealed record ProblemDocument(string? Code, string? Detail, int? Status);
}
