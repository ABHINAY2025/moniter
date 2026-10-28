using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Admin.Core.Api;

/// <summary>
/// Typed backend client. Attaches the access token, and transparently refreshes it once on a 401 using the
/// refresh token (which rotates server-side). Login/refresh failures surface as <see cref="ApiException"/>.
/// </summary>
public sealed class AdminApiClient : IAdminApi
{
    public static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web)
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly HttpClient _http;
    private readonly SessionState _session;
    private readonly ILogger<AdminApiClient> _logger;
    private readonly SemaphoreSlim _refreshLock = new(1, 1);

    public AdminApiClient(HttpClient http, SessionState session, ILogger<AdminApiClient> logger)
    {
        _http = http;
        _session = session;
        _logger = logger;
    }

    public async Task<TokenResponse> LoginAsync(string username, string password, CancellationToken ct = default)
    {
        var tokens = await PostAsync<TokenResponse>("/api/auth/login",
            new { username, password }, authenticated: false, ct);
        _session.Set(tokens);
        return tokens;
    }

    public async Task LogoutAsync(CancellationToken ct = default)
    {
        try
        {
            if (_session.RefreshToken is { } rt)
            {
                await PostAsync<object?>("/api/auth/logout", new { refreshToken = rt }, authenticated: false, ct);
            }
        }
        catch (Exception ex)
        {
            _logger.LogDebug("Logout call failed (ignored): {Message}", ex.Message);
        }
        finally
        {
            _session.Clear();
        }
    }

    public Task<DashboardSummary> GetDashboardSummaryAsync(CancellationToken ct = default) =>
        GetAsync<DashboardSummary>("/api/dashboard/summary", ct);

    public Task<PageResponse<EmployeeDto>> GetEmployeesAsync(string? query, int page, int size, CancellationToken ct = default) =>
        GetAsync<PageResponse<EmployeeDto>>($"/api/employees?{Query(query)}page={page}&size={size}", ct);

    public Task<PageResponse<DeviceDto>> GetDevicesAsync(string? query, int page, int size, CancellationToken ct = default) =>
        GetAsync<PageResponse<DeviceDto>>($"/api/devices?{Query(query)}page={page}&size={size}", ct);

    public Task<DeviceDto> GetDeviceAsync(long id, CancellationToken ct = default) =>
        GetAsync<DeviceDto>($"/api/devices/{id}", ct);

    public Task<EmployeeDetailDto> GetEmployeeAsync(long id, CancellationToken ct = default) =>
        GetAsync<EmployeeDetailDto>($"/api/employees/{id}", ct);

    public Task<EmployeeDto> CreateEmployeeAsync(CreateEmployeeInput input, CancellationToken ct = default) =>
        PostAsync<EmployeeDto>("/api/employees",
            new
            {
                employeeCode = input.EmployeeCode,
                fullName = input.FullName,
                email = input.Email,
                departmentId = input.DepartmentId,
                jobTitle = input.JobTitle,
            }, authenticated: true, ct);

    public Task<UsageReport> GetApplicationsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default) =>
        GetAsync<UsageReport>($"/api/employees/{employeeId}/applications?date={date:yyyy-MM-dd}&tz={Uri.EscapeDataString(tz)}", ct);

    public Task<UsageReport> GetWebsitesAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default) =>
        GetAsync<UsageReport>($"/api/employees/{employeeId}/websites?date={date:yyyy-MM-dd}&tz={Uri.EscapeDataString(tz)}", ct);

    public Task<PageResponse<UsbEventDto>> GetUsbEventsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default) =>
        GetAsync<PageResponse<UsbEventDto>>($"/api/employees/{employeeId}/usb-events?date={date:yyyy-MM-dd}&tz={Uri.EscapeDataString(tz)}", ct);

    public Task<PageResponse<NetworkEventDto>> GetNetworkEventsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default) =>
        GetAsync<PageResponse<NetworkEventDto>>($"/api/employees/{employeeId}/network-events?date={date:yyyy-MM-dd}&tz={Uri.EscapeDataString(tz)}", ct);

    public Task<ZscalerStatusDto> GetZscalerStatusAsync(long employeeId, CancellationToken ct = default) =>
        GetAsync<ZscalerStatusDto>($"/api/employees/{employeeId}/zscaler", ct);

    public Task<PageResponse<ScreenshotMetadataDto>> GetScreenshotsAsync(long employeeId, DateOnly date, string tz, CancellationToken ct = default) =>
        GetAsync<PageResponse<ScreenshotMetadataDto>>($"/api/employees/{employeeId}/screenshots?date={date:yyyy-MM-dd}&tz={Uri.EscapeDataString(tz)}", ct);

    public async Task<byte[]> GetScreenshotBytesAsync(long screenshotId, CancellationToken ct = default)
    {
        var response = await SendOnce(new HttpRequestMessage(HttpMethod.Get, $"/api/screenshots/{screenshotId}/content"), true, ct);
        if (response.StatusCode == HttpStatusCode.Unauthorized && _session.RefreshToken is not null)
        {
            response.Dispose();
            if (await TryRefreshAsync(ct))
            {
                response = await SendOnce(new HttpRequestMessage(HttpMethod.Get, $"/api/screenshots/{screenshotId}/content"), true, ct);
            }
        }
        using (response)
        {
            if (response.IsSuccessStatusCode)
            {
                return await response.Content.ReadAsByteArrayAsync(ct);
            }
            throw await ToException(response, ct);
        }
    }

    public Task<LiveViewSessionDto> StartLiveViewAsync(long deviceId, CancellationToken ct = default) =>
        PostAsync<LiveViewSessionDto>("/api/live-view/sessions", new { deviceId }, authenticated: true, ct);

    public async Task StopLiveViewAsync(Guid sessionId, CancellationToken ct = default) =>
        await SendAsync<object?>(() => new HttpRequestMessage(HttpMethod.Delete, $"/api/live-view/sessions/{sessionId}"),
            authenticated: true, ct);

    public Task<EnrollmentTokenCreated> CreateEnrollmentTokenAsync(string? description, int maxUses, int expiresInHours,
        CancellationToken ct = default) =>
        PostAsync<EnrollmentTokenCreated>("/api/enrollment-tokens",
            new { description, maxUses, expiresInHours }, authenticated: true, ct);

    public Task<DeviceDto> AssignDeviceAsync(long deviceId, long employeeId, CancellationToken ct = default) =>
        SendAsync<DeviceDto>(() => new HttpRequestMessage(HttpMethod.Put, $"/api/devices/{deviceId}/assignment")
        {
            Content = JsonContent.Create(new { employeeId }, options: JsonOptions),
        }, authenticated: true, ct);

    public Task<PolicyResponseDto> GetDefaultPolicyAsync(CancellationToken ct = default) =>
        GetAsync<PolicyResponseDto>("/api/policies/default", ct);

    public Task<PolicyResponseDto> UpdateDefaultPolicyAsync(PolicySettingsDto settings, CancellationToken ct = default) =>
        SendAsync<PolicyResponseDto>(() => new HttpRequestMessage(HttpMethod.Put, "/api/policies/default")
        {
            Content = JsonContent.Create(settings, options: JsonOptions),
        }, authenticated: true, ct);

    public Task<PageResponse<AlertDto>> GetAlertsAsync(bool unacknowledged, int page, int size,
        CancellationToken ct = default) =>
        GetAsync<PageResponse<AlertDto>>(
            $"/api/alerts?unacknowledged={(unacknowledged ? "true" : "false")}&page={page}&size={size}", ct);

    public Task<AlertDto> AcknowledgeAlertAsync(long alertId, CancellationToken ct = default) =>
        PostAsync<AlertDto>($"/api/alerts/{alertId}/acknowledge", new { }, authenticated: true, ct);

    // ---------- Website restriction & access monitoring ----------

    public Task<PageResponse<WebPolicyDto>> GetWebPoliciesAsync(string? query, int page, int size,
        CancellationToken ct = default) =>
        GetAsync<PageResponse<WebPolicyDto>>($"/api/web-policies?{Query(query)}page={page}&size={size}", ct);

    public Task<WebPolicyDto> CreateWebPolicyAsync(WebPolicyInput input, CancellationToken ct = default) =>
        PostAsync<WebPolicyDto>("/api/web-policies", PolicyBody(input), authenticated: true, ct);

    public Task<WebPolicyDto> UpdateWebPolicyAsync(long id, WebPolicyInput input, CancellationToken ct = default) =>
        SendAsync<WebPolicyDto>(() => new HttpRequestMessage(HttpMethod.Put, $"/api/web-policies/{id}")
        {
            Content = JsonContent.Create(PolicyBody(input), options: JsonOptions),
        }, authenticated: true, ct);

    public Task DeleteWebPolicyAsync(long id, CancellationToken ct = default) =>
        SendAsync<object?>(() => new HttpRequestMessage(HttpMethod.Delete, $"/api/web-policies/{id}"),
            authenticated: true, ct);

    public Task<IReadOnlyList<WebAssignmentDto>> GetWebPolicyAssignmentsAsync(long policyId,
        CancellationToken ct = default) =>
        GetAsync<IReadOnlyList<WebAssignmentDto>>($"/api/web-policies/{policyId}/assignments", ct);

    public Task<WebAssignmentDto> AssignWebPolicyAsync(long policyId, string targetType, long targetId,
        CancellationToken ct = default) =>
        PostAsync<WebAssignmentDto>($"/api/web-policies/{policyId}/assignments",
            new { targetType, targetId }, authenticated: true, ct);

    public Task UnassignWebPolicyAsync(long policyId, long assignmentId, CancellationToken ct = default) =>
        SendAsync<object?>(() => new HttpRequestMessage(HttpMethod.Delete,
            $"/api/web-policies/{policyId}/assignments/{assignmentId}"), authenticated: true, ct);

    public Task<PageResponse<WebAccessEventDto>> GetWebAccessEventsAsync(long? employeeId, string? result,
        string? domain, int page, int size, CancellationToken ct = default)
    {
        var q = $"?page={page}&size={size}";
        if (employeeId is { } emp)
        {
            q += $"&employeeId={emp}";
        }
        if (!string.IsNullOrWhiteSpace(result))
        {
            q += $"&result={Uri.EscapeDataString(result)}";
        }
        if (!string.IsNullOrWhiteSpace(domain))
        {
            q += $"&domain={Uri.EscapeDataString(domain.Trim())}";
        }
        return GetAsync<PageResponse<WebAccessEventDto>>("/api/web-access-events" + q, ct);
    }

    public Task<WebAccessSummaryDto> GetWebAccessSummaryAsync(int days, CancellationToken ct = default) =>
        GetAsync<WebAccessSummaryDto>($"/api/web-access-events/summary?days={days}", ct);

    public Task<WebEnforcementStatusDto> GetWebEnforcementStatusAsync(long deviceId, CancellationToken ct = default) =>
        GetAsync<WebEnforcementStatusDto>($"/api/devices/{deviceId}/web-enforcement-status", ct);

    private static object PolicyBody(WebPolicyInput i) => new
    {
        name = i.Name,
        description = i.Description,
        enabled = i.Enabled,
        priority = i.Priority,
        overrideCriticalDomains = i.OverrideCriticalDomains,
        rules = i.Rules.Select(r => new { domain = r.Domain, category = r.Category, action = r.Action }).ToList(),
    };

    private static string Query(string? q) =>
        string.IsNullOrWhiteSpace(q) ? "" : $"q={Uri.EscapeDataString(q.Trim())}&";

    // ---------- HTTP plumbing ----------

    private Task<T> GetAsync<T>(string path, CancellationToken ct) =>
        SendAsync<T>(() => new HttpRequestMessage(HttpMethod.Get, path), authenticated: true, ct);

    private Task<T> PostAsync<T>(string path, object body, bool authenticated, CancellationToken ct) =>
        SendAsync<T>(() =>
        {
            var req = new HttpRequestMessage(HttpMethod.Post, path)
            {
                Content = JsonContent.Create(body, body.GetType(), options: JsonOptions),
            };
            return req;
        }, authenticated, ct);

    private async Task<T> SendAsync<T>(Func<HttpRequestMessage> factory, bool authenticated, CancellationToken ct)
    {
        var response = await SendOnce(factory(), authenticated, ct);
        if (response.StatusCode == HttpStatusCode.Unauthorized && authenticated && _session.RefreshToken is not null)
        {
            response.Dispose();
            if (await TryRefreshAsync(ct))
            {
                response = await SendOnce(factory(), authenticated, ct);
            }
        }
        using (response)
        {
            if (response.IsSuccessStatusCode)
            {
                if (typeof(T) == typeof(object))
                {
                    return default!;
                }
                var value = await response.Content.ReadFromJsonAsync<T>(JsonOptions, ct);
                return value ?? throw new ApiException("Empty response");
            }
            throw await ToException(response, ct);
        }
    }

    private async Task<HttpResponseMessage> SendOnce(HttpRequestMessage request, bool authenticated, CancellationToken ct)
    {
        if (authenticated && _session.AccessToken is { } token)
        {
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }
        try
        {
            return await _http.SendAsync(request, ct);
        }
        catch (HttpRequestException ex)
        {
            throw new ApiException("Could not reach the server: " + ex.Message, inner: ex);
        }
    }

    private async Task<bool> TryRefreshAsync(CancellationToken ct)
    {
        await _refreshLock.WaitAsync(ct);
        try
        {
            if (_session.RefreshToken is not { } rt)
            {
                return false;
            }
            using var req = new HttpRequestMessage(HttpMethod.Post, "/api/auth/refresh")
            {
                Content = JsonContent.Create(new { refreshToken = rt }, options: JsonOptions),
            };
            using var resp = await _http.SendAsync(req, ct);
            if (!resp.IsSuccessStatusCode)
            {
                _session.Clear();
                return false;
            }
            var tokens = await resp.Content.ReadFromJsonAsync<TokenResponse>(JsonOptions, ct);
            if (tokens is null)
            {
                return false;
            }
            _session.UpdateTokens(tokens);
            return true;
        }
        catch (Exception)
        {
            return false;
        }
        finally
        {
            _refreshLock.Release();
        }
    }

    private async Task<ApiException> ToException(HttpResponseMessage response, CancellationToken ct)
    {
        string? code = null;
        string detail = response.ReasonPhrase ?? "Request failed";
        try
        {
            var problem = await response.Content.ReadFromJsonAsync<Problem>(JsonOptions, ct);
            if (problem is not null)
            {
                code = problem.Code;
                detail = problem.Detail ?? detail;
            }
        }
        catch (Exception)
        {
            // keep reason phrase
        }
        if (response.StatusCode == HttpStatusCode.Unauthorized && code == "INVALID_CREDENTIALS")
        {
            detail = "Invalid username or password";
        }
        return new ApiException(detail, response.StatusCode, code);
    }

    private sealed record Problem(string? Code, string? Detail, int? Status);
}
