using System.Net;
using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Monitoring;
using EmployeeMonitor.Agent.Platform;

namespace EmployeeMonitor.Agent.Tests;

internal sealed class FakeForegroundAppProvider : IForegroundAppProvider
{
    public ForegroundApp? Current { get; set; }
    public ForegroundApp? GetForeground() => Current;
}

internal sealed class FakeNetworkProvider : INetworkProvider
{
    public NetworkSnapshot Snapshot { get; set; } = new(null, null, null);
    public NetworkSnapshot GetSnapshot() => Snapshot;
}

internal sealed class FakeZscalerProvider : IZscalerProvider
{
    public string Status { get; set; } = "UNKNOWN";
    public string GetStatus() => Status;
}

internal sealed class FakeRemovableDriveProvider : IRemovableDriveProvider
{
    public List<RemovableDrive> Drives { get; } = new();
    public IReadOnlyList<RemovableDrive> GetRemovableDrives() => Drives;
}

internal sealed class FakeDnsVisitProvider : IDnsVisitProvider
{
    public List<string> Hosts { get; } = new();
    public IReadOnlyList<string> GetResolvedHosts() => Hosts;
}

internal sealed class FakeScreenCaptureProvider : IScreenCaptureProvider
{
    public ScreenCapture? Next { get; set; } = new(new byte[] { 1, 2, 3, 4 }, "image/jpeg", 100, 100);
    public int Captures { get; private set; }

    public ScreenCapture? Capture()
    {
        Captures++;
        return Next;
    }

    public ScreenCapture? Capture(int maxLongEdge, int quality) => Capture();
}

internal sealed class InMemorySecureStore : ISecureStore
{
    private string? _value;
    public void Save(string plaintext) => _value = plaintext;
    public string? Load() => _value;
    public void Clear() => _value = null;
}

internal sealed class FakeInputMonitor : IInputMonitor
{
    public TimeSpan Idle { get; set; } = TimeSpan.Zero;
    public TimeSpan GetIdleTime() => Idle;
}

internal sealed class FakeClock : ISystemClock
{
    public DateTimeOffset UtcNow { get; set; } = DateTimeOffset.UnixEpoch;
}

internal sealed class FakeSystemInfo : ISystemInfo
{
    public string Hostname => "TEST-PC";
    public string OsVersion => "Windows 11 Pro 23H2";
    public string? MacAddress => "00-11-22-33-44-55";
    public string? PrimaryIpAddress => "10.0.0.5";
    public string? HardwareId => "hw-test-guid";
}

internal sealed class FakeSystemEventSource : ISystemEventSource
{
    public event Action<SystemSignal>? Raised;
    public bool Started { get; private set; }
    public void Start() => Started = true;
    public void Raise(SystemSignal signal) => Raised?.Invoke(signal);
    public void Dispose() { }
}

/// <summary>In-memory backend capturing everything the agent sends.</summary>
internal sealed class FakeBackendApi : IBackendApi
{
    private readonly object _gate = new();
    private readonly List<string> _events = new();

    public int RegisterCalls { get; private set; }
    public int HeartbeatCalls { get; private set; }
    public string? LastStatus { get; private set; }
    public PolicySettings Policy { get; set; } = TestData.Policy();
    public string PolicyRevision { get; set; } = "1-1";

    /// <summary>When set, GetPolicyAsync throws a 401 auth failure for this device token (simulates a credential
    /// the backend no longer accepts), so the re-enrollment path can be exercised.</summary>
    public string? RejectPolicyForToken { get; set; }

    public IReadOnlyList<string> Events
    {
        get { lock (_gate) { return _events.ToList(); } }
    }

    public Task<DeviceRegistrationResponse> RegisterAsync(RegisterDeviceRequest request, CancellationToken ct)
    {
        RegisterCalls++;
        return Task.FromResult(new DeviceRegistrationResponse(
            42, "DEV-10042", "dvt_fake_token", DateTimeOffset.UnixEpoch.AddYears(1),
            new EmployeeSummary(7, "EMP-7", "Rahul Kumar", "Engineering"), PolicyRevision, Policy));
    }

    public Task<HeartbeatResponse> HeartbeatAsync(HeartbeatRequest request, string deviceToken, CancellationToken ct)
    {
        lock (_gate)
        {
            HeartbeatCalls++;
            LastStatus = request.Status;
        }
        return Task.FromResult(new HeartbeatResponse(DateTimeOffset.UnixEpoch, PolicyRevision, 10));
    }

    public Task<PolicyResponse> GetPolicyAsync(string deviceToken, CancellationToken ct)
    {
        if (RejectPolicyForToken is not null && deviceToken == RejectPolicyForToken)
        {
            throw new BackendException("Authentication required", HttpStatusCode.Unauthorized, "UNAUTHORIZED");
        }
        return Task.FromResult(new PolicyResponse(1, "Default policy", "DEFAULT", PolicyRevision, Policy, DateTimeOffset.UnixEpoch));
    }

    public Task<AgentIdentityResponse> GetIdentityAsync(string deviceToken, CancellationToken ct) =>
        Task.FromResult(new AgentIdentityResponse(42, "DEV-10042", "TEST-PC",
            new EmployeeSummary(7, "EMP-7", "Rahul Kumar", "Engineering"), PolicyRevision));

    public Task<SessionIngestResponse> PostSessionEventsAsync(
        SessionIngestRequest request, string deviceToken, CancellationToken ct)
    {
        lock (_gate)
        {
            _events.AddRange(request.Events.Select(e => e.Type));
        }
        return Task.FromResult(new SessionIngestResponse(request.Events.Count, 0, 0));
    }

    public int AppUsageUploaded { get; private set; }
    public int NetworkUploaded { get; private set; }
    public int ZscalerUploaded { get; private set; }
    public int UsbUploaded { get; private set; }

    public Task<MonIngestResult> PostApplicationUsageAsync(MonBatch<MonAppUsage> batch, string t, CancellationToken ct)
    {
        lock (_gate) { AppUsageUploaded += batch.Events.Count; }
        return Task.FromResult(new MonIngestResult(batch.Events.Count, 0, 0));
    }

    public int WebsiteUploaded { get; private set; }
    public List<string> WebsiteDomains { get; } = new();

    public Task<MonIngestResult> PostWebsiteUsageAsync(MonBatch<MonWebsiteUsage> batch, string t, CancellationToken ct)
    {
        lock (_gate) { WebsiteUploaded += batch.Events.Count; WebsiteDomains.AddRange(batch.Events.Select(e => e.Domain)); }
        return Task.FromResult(new MonIngestResult(batch.Events.Count, 0, 0));
    }

    public Task<MonIngestResult> PostUsbEventsAsync(MonBatch<MonUsbEvent> batch, string t, CancellationToken ct)
    {
        lock (_gate) { UsbUploaded += batch.Events.Count; }
        return Task.FromResult(new MonIngestResult(batch.Events.Count, 0, 0));
    }

    public Task<MonIngestResult> PostNetworkEventsAsync(MonBatch<MonNetworkEvent> batch, string t, CancellationToken ct)
    {
        lock (_gate) { NetworkUploaded += batch.Events.Count; }
        return Task.FromResult(new MonIngestResult(batch.Events.Count, 0, 0));
    }

    public Task<MonIngestResult> PostZscalerEventsAsync(MonBatch<MonZscalerEvent> batch, string t, CancellationToken ct)
    {
        lock (_gate) { ZscalerUploaded += batch.Events.Count; }
        return Task.FromResult(new MonIngestResult(batch.Events.Count, 0, 0));
    }

    public int ScreenshotsUploaded { get; private set; }

    public Task<MonScreenshotResult> PostScreenshotAsync(MonScreenshot screenshot, string t, CancellationToken ct)
    {
        lock (_gate) { ScreenshotsUploaded++; }
        return Task.FromResult(new MonScreenshotResult(ScreenshotsUploaded, false));
    }

    public UpdateAvailable? Update { get; set; }
    public byte[] UpdatePackage { get; set; } = Array.Empty<byte>();

    public Task<UpdateAvailable?> CheckUpdateAsync(string currentVersion, string t, CancellationToken ct) =>
        Task.FromResult(Update);

    public Task<byte[]> DownloadUpdateAsync(string path, string t, CancellationToken ct) =>
        Task.FromResult(UpdatePackage);

    public EffectiveWebPolicy WebPolicy { get; set; } =
        new("0", Array.Empty<string>(), Array.Empty<string>(), Array.Empty<string>());
    public int WebPolicyFetches { get; private set; }
    public List<WebAccessEventInput> WebAccessEvents { get; } = new();
    public WebEnforcementReport? LastEnforcementReport { get; private set; }

    public Task<EffectiveWebPolicy> GetWebPolicyAsync(string deviceToken, CancellationToken ct)
    {
        lock (_gate) { WebPolicyFetches++; }
        return Task.FromResult(WebPolicy);
    }

    public Task<WebAccessIngestResult> PostWebAccessEventsAsync(WebAccessBatch batch, string deviceToken, CancellationToken ct)
    {
        lock (_gate) { WebAccessEvents.AddRange(batch.Events); }
        return Task.FromResult(new WebAccessIngestResult(batch.Events.Count, 0, 0));
    }

    public Task PostWebEnforcementStatusAsync(WebEnforcementReport report, string deviceToken, CancellationToken ct)
    {
        lock (_gate) { LastEnforcementReport = report; }
        return Task.CompletedTask;
    }
}

internal static class TestData
{
    public static PolicySettings Policy(
        int idle = 300, bool session = true, int heartbeat = 10,
        string start = "00:00", string end = "23:59") =>
        new(
            SessionMonitoring: session, IdleThresholdSeconds: idle,
            ApplicationMonitoring: true, WebsiteMonitoring: false, UsbMonitoring: true,
            NetworkMonitoring: true, ZscalerMonitoring: true,
            Screenshots: false, ScreenshotIntervalSeconds: 900,
            LiveView: false, LiveViewMaxMinutes: 30,
            HeartbeatIntervalSeconds: heartbeat, PolicySyncIntervalSeconds: 300,
            MonitoringStart: start, MonitoringEnd: end,
            WorkDays: new[] { "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY" },
            Timezone: "UTC", RetentionDays: 90, ScreenshotRetentionDays: 30);
}
