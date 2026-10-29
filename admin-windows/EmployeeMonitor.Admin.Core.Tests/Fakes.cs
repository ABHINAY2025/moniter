using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;

namespace EmployeeMonitor.Admin.Core.Tests;

internal sealed class FakeAdminApi : IAdminApi
{
    public Func<string, string, TokenResponse>? LoginHandler { get; set; }
    public DashboardSummary Summary { get; set; } =
        new(0, 0, 0, 0, 0, 0, 0, 0, DateTimeOffset.UnixEpoch);
    public List<DeviceDto> Devices { get; } = new();
    public List<EmployeeDto> Employees { get; } = new();
    public int LogoutCalls { get; private set; }

    public Task<TokenResponse> LoginAsync(string username, string password, CancellationToken ct = default)
    {
        if (LoginHandler is null)
        {
            throw new ApiException("Invalid username or password", System.Net.HttpStatusCode.Unauthorized,
                "INVALID_CREDENTIALS");
        }
        return Task.FromResult(LoginHandler(username, password));
    }

    public Task LogoutAsync(CancellationToken ct = default)
    {
        LogoutCalls++;
        return Task.CompletedTask;
    }

    public Task<DashboardSummary> GetDashboardSummaryAsync(CancellationToken ct = default) =>
        Task.FromResult(Summary);

    public Task<PageResponse<EmployeeDto>> GetEmployeesAsync(string? query, int page, int size, CancellationToken ct = default)
    {
        var filtered = string.IsNullOrWhiteSpace(query)
            ? Employees
            : Employees.Where(e => e.FullName.Contains(query, StringComparison.OrdinalIgnoreCase)).ToList();
        var pageItems = filtered.Skip(page * size).Take(size).ToList();
        int totalPages = (int)Math.Ceiling(filtered.Count / (double)size);
        return Task.FromResult(new PageResponse<EmployeeDto>(pageItems, page, size, filtered.Count, totalPages));
    }

    public int CreateEmployeeCalls { get; private set; }
    public Func<CreateEmployeeInput, EmployeeDto>? CreateEmployeeHandler { get; set; }

    public Task<EmployeeDto> CreateEmployeeAsync(CreateEmployeeInput input, CancellationToken ct = default)
    {
        CreateEmployeeCalls++;
        var created = CreateEmployeeHandler?.Invoke(input)
            ?? new EmployeeDto(Employees.Count + 1, input.EmployeeCode, input.FullName, input.Email,
                null, input.JobTitle, "ACTIVE");
        Employees.Add(created);
        return Task.FromResult(created);
    }

    public Task<PageResponse<DeviceDto>> GetDevicesAsync(string? query, int page, int size, CancellationToken ct = default)
    {
        var pageItems = Devices.Skip(page * size).Take(size).ToList();
        int totalPages = (int)Math.Ceiling(Devices.Count / (double)size);
        return Task.FromResult(new PageResponse<DeviceDto>(pageItems, page, size, Devices.Count, totalPages));
    }

    public Task<DeviceDto> GetDeviceAsync(long id, CancellationToken ct = default) =>
        Task.FromResult(Devices.First(d => d.Id == id));

    public PolicyResponseDto DefaultPolicy { get; set; } = new(1, "Default policy", "DEFAULT", "1-1",
        new PolicySettingsDto(true, 300, true, false, true, true, true, false, 900, false, 30, 30, 300,
            "09:00", "18:00", new[] { "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY" }, "UTC", 90, 30),
        DateTimeOffset.UnixEpoch);
    public PolicySettingsDto? LastSavedPolicy { get; private set; }

    public Task<PolicyResponseDto> GetDefaultPolicyAsync(CancellationToken ct = default) =>
        Task.FromResult(DefaultPolicy);

    public Task<PolicyResponseDto> UpdateDefaultPolicyAsync(PolicySettingsDto settings, CancellationToken ct = default)
    {
        LastSavedPolicy = settings;
        DefaultPolicy = DefaultPolicy with { Settings = settings, Revision = "1-2" };
        return Task.FromResult(DefaultPolicy);
    }

    public Dictionary<long, PolicyResponseDto> DevicePolicies { get; } = new();
    public (long deviceId, PolicySettingsDto settings)? LastDevicePolicySaved { get; private set; }

    public Task<PolicyResponseDto> GetDevicePolicyAsync(long deviceId, CancellationToken ct = default) =>
        Task.FromResult(DevicePolicies.TryGetValue(deviceId, out var p) ? p : DefaultPolicy);

    public Task<PolicyResponseDto> UpdateDevicePolicyAsync(long deviceId, PolicySettingsDto settings,
        CancellationToken ct = default)
    {
        LastDevicePolicySaved = (deviceId, settings);
        var dto = new PolicyResponseDto(100 + deviceId, "device:" + deviceId, "DEVICE_OVERRIDE", "100-1",
            settings, DateTimeOffset.UnixEpoch);
        DevicePolicies[deviceId] = dto;
        return Task.FromResult(dto);
    }

    // Milestone 4
    public UsageReport ApplicationsReport { get; set; } = new(1, "2026-10-06", "UTC", 0, new List<UsageSlice>());
    public UsageReport WebsitesReport { get; set; } = new(1, "2026-10-06", "UTC", 0, new List<UsageSlice>());
    public List<UsbEventDto> Usb { get; } = new();
    public List<NetworkEventDto> Network { get; } = new();
    public ZscalerStatusDto Zscaler { get; set; } = new("UNKNOWN", null, new List<ZscalerEventDto>());

    public Task<EmployeeDetailDto> GetEmployeeAsync(long id, CancellationToken ct = default)
    {
        var emp = Employees.FirstOrDefault(e => e.Id == id)
                  ?? new EmployeeDto(id, "E" + id, "Employee " + id, "e@x.com", null, null, "ACTIVE");
        var devices = Devices.Where(d => d.Employee?.Id == id).ToList();
        return Task.FromResult(new EmployeeDetailDto(emp, devices));
    }

    public Task<UsageReport> GetApplicationsAsync(long e, DateOnly d, string tz, CancellationToken ct = default) =>
        Task.FromResult(ApplicationsReport);

    public Task<UsageReport> GetWebsitesAsync(long e, DateOnly d, string tz, CancellationToken ct = default) =>
        Task.FromResult(WebsitesReport);

    public Task<PageResponse<UsbEventDto>> GetUsbEventsAsync(long e, DateOnly d, string tz, CancellationToken ct = default) =>
        Task.FromResult(new PageResponse<UsbEventDto>(Usb, 0, 100, Usb.Count, 1));

    public Task<PageResponse<NetworkEventDto>> GetNetworkEventsAsync(long e, DateOnly d, string tz, CancellationToken ct = default) =>
        Task.FromResult(new PageResponse<NetworkEventDto>(Network, 0, 100, Network.Count, 1));

    public Task<ZscalerStatusDto> GetZscalerStatusAsync(long e, CancellationToken ct = default) =>
        Task.FromResult(Zscaler);

    public List<ScreenshotMetadataDto> Screenshots { get; } = new();
    public byte[] ScreenshotBytes { get; set; } = { 1, 2, 3 };

    public Task<PageResponse<ScreenshotMetadataDto>> GetScreenshotsAsync(long e, DateOnly d, string tz, CancellationToken ct = default) =>
        Task.FromResult(new PageResponse<ScreenshotMetadataDto>(Screenshots, 0, 100, Screenshots.Count, 1));

    public Task<byte[]> GetScreenshotBytesAsync(long id, CancellationToken ct = default) =>
        Task.FromResult(ScreenshotBytes);

    public LiveViewSessionDto? LiveViewToStart { get; set; }
    public int StopLiveViewCalls { get; private set; }

    public Task<LiveViewSessionDto> StartLiveViewAsync(long deviceId, CancellationToken ct = default)
    {
        if (LiveViewToStart is null)
        {
            throw new ApiException("Live view is disabled by policy for this device",
                System.Net.HttpStatusCode.Forbidden, "FORBIDDEN");
        }
        return Task.FromResult(LiveViewToStart);
    }

    public Task StopLiveViewAsync(Guid sessionId, CancellationToken ct = default)
    {
        StopLiveViewCalls++;
        return Task.CompletedTask;
    }

    public EnrollmentTokenCreated? EnrollmentTokenToReturn { get; set; }
    public int CreateEnrollmentTokenCalls { get; private set; }

    public Task<EnrollmentTokenCreated> CreateEnrollmentTokenAsync(string? description, int maxUses, int expiresInHours,
        CancellationToken ct = default)
    {
        CreateEnrollmentTokenCalls++;
        return Task.FromResult(EnrollmentTokenToReturn
            ?? new EnrollmentTokenCreated(1, "enr_faketoken", description, maxUses,
                DateTimeOffset.UnixEpoch.AddHours(expiresInHours)));
    }

    public (long deviceId, long employeeId)? LastAssignment { get; private set; }

    public Task<DeviceDto> AssignDeviceAsync(long deviceId, long employeeId, CancellationToken ct = default)
    {
        LastAssignment = (deviceId, employeeId);
        return Task.FromResult(new DeviceDto(deviceId, $"DEV-{deviceId}", "HOST", null, null, null, "ACTIVE",
            new EmployeeSummaryDto(employeeId, $"EMP-{employeeId}", "Assigned", null), null, null,
            new PresenceDto("ACTIVE", true, null)));
    }

    public List<AlertDto> AlertsToReturn { get; } = new();
    public int AcknowledgeCalls { get; private set; }

    public Task<PageResponse<AlertDto>> GetAlertsAsync(bool unacknowledged, int page, int size,
        CancellationToken ct = default) =>
        Task.FromResult(new PageResponse<AlertDto>(AlertsToReturn, page, size, AlertsToReturn.Count, 1));

    public Task<AlertDto> AcknowledgeAlertAsync(long alertId, CancellationToken ct = default)
    {
        AcknowledgeCalls++;
        return Task.FromResult(new AlertDto(alertId, "USB_CONNECTED", "WARNING", "", null, null, null, null,
            DateTimeOffset.UnixEpoch, true, DateTimeOffset.UnixEpoch));
    }

    // ---- Website restriction & access monitoring ----
    public List<WebPolicyDto> WebPolicies { get; } = new();
    public List<WebAccessEventDto> WebEvents { get; } = new();
    public List<WebAssignmentDto> Assignments { get; } = new();
    public WebAccessSummaryDto WebSummary { get; set; } =
        new(0, 0, 0, 0, 0, new List<DomainCountDto>());
    public WebEnforcementStatusDto? EnforcementStatus { get; set; }
    public WebPolicyInput? LastCreated { get; private set; }
    public WebPolicyInput? LastUpdated { get; private set; }
    public long? LastDeletedPolicy { get; private set; }
    public (long policyId, string targetType, long targetId)? LastAssigned { get; private set; }

    public Task<PageResponse<WebPolicyDto>> GetWebPoliciesAsync(string? query, int page, int size,
        CancellationToken ct = default) =>
        Task.FromResult(new PageResponse<WebPolicyDto>(WebPolicies, page, size, WebPolicies.Count, 1));

    public Task<WebPolicyDto> CreateWebPolicyAsync(WebPolicyInput input, CancellationToken ct = default)
    {
        LastCreated = input;
        var dto = new WebPolicyDto(WebPolicies.Count + 1, input.Name, input.Description, input.Enabled,
            input.Priority, null, null, 1, input.Rules, 0, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch);
        WebPolicies.Add(dto);
        return Task.FromResult(dto);
    }

    public Task<WebPolicyDto> UpdateWebPolicyAsync(long id, WebPolicyInput input, CancellationToken ct = default)
    {
        LastUpdated = input;
        return Task.FromResult(new WebPolicyDto(id, input.Name, input.Description, input.Enabled, input.Priority,
            null, null, 2, input.Rules, 0, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch));
    }

    public Task DeleteWebPolicyAsync(long id, CancellationToken ct = default)
    {
        LastDeletedPolicy = id;
        WebPolicies.RemoveAll(p => p.Id == id);
        return Task.CompletedTask;
    }

    public Task<IReadOnlyList<WebAssignmentDto>> GetWebPolicyAssignmentsAsync(long policyId,
        CancellationToken ct = default) =>
        Task.FromResult<IReadOnlyList<WebAssignmentDto>>(Assignments);

    public Task<WebAssignmentDto> AssignWebPolicyAsync(long policyId, string targetType, long targetId,
        CancellationToken ct = default)
    {
        LastAssigned = (policyId, targetType, targetId);
        var dto = new WebAssignmentDto(Assignments.Count + 1, targetType, targetId, $"{targetType} {targetId}",
            DateTimeOffset.UnixEpoch);
        Assignments.Add(dto);
        return Task.FromResult(dto);
    }

    public Task UnassignWebPolicyAsync(long policyId, long assignmentId, CancellationToken ct = default)
    {
        Assignments.RemoveAll(a => a.Id == assignmentId);
        return Task.CompletedTask;
    }

    public Task<PageResponse<WebAccessEventDto>> GetWebAccessEventsAsync(long? employeeId, string? result,
        string? domain, int page, int size, CancellationToken ct = default) =>
        Task.FromResult(new PageResponse<WebAccessEventDto>(WebEvents, page, size, WebEvents.Count, 1));

    public Task<WebAccessSummaryDto> GetWebAccessSummaryAsync(int days, CancellationToken ct = default) =>
        Task.FromResult(WebSummary);

    public Task<WebEnforcementStatusDto> GetWebEnforcementStatusAsync(long deviceId, CancellationToken ct = default) =>
        Task.FromResult(EnforcementStatus
            ?? new WebEnforcementStatusDto(deviceId, "chromium-url-policy", true, "abc", "abc", "Chrome, Edge",
                DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch, null, false));
}

internal sealed class FakeLiveViewSignaling : EmployeeMonitor.Admin.Core.Realtime.ILiveViewSignaling
{
    public EmployeeMonitor.Admin.Core.Realtime.RealtimeStatus Status { get; private set; }
    public event Action<byte[]>? FrameReceived;
    public event Action<byte[], int, int>? AudioReceived;
    public event Action<string>? Stopped;
    public event Action<EmployeeMonitor.Admin.Core.Realtime.RealtimeStatus>? StatusChanged;
    public event Action? ControlGranted;
    public event Action? ControlDenied;
    public event Action<string>? ClipboardReceived;
    public Guid? StartedSession { get; private set; }
    public bool Stopping { get; private set; }
    public List<string> Sent { get; } = new();

    public Task StartAsync(Guid sessionId, CancellationToken ct = default)
    {
        StartedSession = sessionId;
        Status = EmployeeMonitor.Admin.Core.Realtime.RealtimeStatus.Connected;
        StatusChanged?.Invoke(Status);
        return Task.CompletedTask;
    }

    public Task StopAsync()
    {
        Stopping = true;
        return Task.CompletedTask;
    }

    public Task SendAsync(string json, CancellationToken ct = default)
    {
        Sent.Add(json);
        return Task.CompletedTask;
    }

    public void EmitFrame(byte[] bytes) => FrameReceived?.Invoke(bytes);
    public void EmitAudio(byte[] pcm, int sampleRate, int channels) => AudioReceived?.Invoke(pcm, sampleRate, channels);
    public void EmitStopped(string reason) => Stopped?.Invoke(reason);
    public void EmitControlGranted() => ControlGranted?.Invoke();
    public void EmitControlDenied() => ControlDenied?.Invoke();
    public void EmitClipboard(string text) => ClipboardReceived?.Invoke(text);
    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

internal sealed class FakeRealtimeClient : IRealtimeClient
{
    public RealtimeStatus Status { get; private set; } = RealtimeStatus.Disconnected;
    public event Action<PresenceEvent>? PresenceReceived;
    public event Action<AlertDto>? AlertReceived;
    public event Action<WebAccessEventDto>? WebAccessEventReceived;
    public event Action<RealtimeStatus>? StatusChanged;

    public Task StartAsync(CancellationToken ct = default)
    {
        SetStatus(RealtimeStatus.Connected);
        return Task.CompletedTask;
    }

    public Task StopAsync()
    {
        SetStatus(RealtimeStatus.Disconnected);
        return Task.CompletedTask;
    }

    public void Emit(PresenceEvent evt) => PresenceReceived?.Invoke(evt);
    public void EmitAlert(AlertDto alert) => AlertReceived?.Invoke(alert);
    public void EmitWebEvent(WebAccessEventDto evt) => WebAccessEventReceived?.Invoke(evt);
    public void SetStatus(RealtimeStatus status)
    {
        Status = status;
        StatusChanged?.Invoke(status);
    }

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

internal static class Data
{
    public static DeviceDto Device(long id, long? empId, string status) =>
        new(id, $"DEV-{10000 + id}", $"PC-{id}", "Windows 11", "1.0.0", "10.0.0.1", "ACTIVE",
            empId is null ? null : new EmployeeSummaryDto(empId.Value, $"EMP-{empId}", $"Employee {empId}", "Engineering"),
            null, DateTimeOffset.UnixEpoch, new PresenceDto(status, status != "OFFLINE", DateTimeOffset.UnixEpoch));

    public static TokenResponse Tokens(string username = "admin", params string[] roles) =>
        new("Bearer", "access-xyz", DateTimeOffset.UtcNow.AddMinutes(15), "rt_abc", DateTimeOffset.UtcNow.AddDays(7),
            new UserInfo(1, username, "Administrator", "a@b.c", null,
                roles.Length == 0 ? new[] { "SUPER_ADMIN" } : roles,
                new[] { "REPORT_READ", "EMPLOYEE_READ_ALL", "USER_MANAGE" }));
}
