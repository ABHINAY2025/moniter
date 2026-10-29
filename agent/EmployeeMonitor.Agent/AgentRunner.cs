using System.Threading.Channels;
using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Configuration;
using EmployeeMonitor.Agent.Monitoring;
using EmployeeMonitor.Agent.Platform;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;

namespace EmployeeMonitor.Agent;

/// <summary>
/// Host-agnostic orchestrator for the Windows agent. Ensures the device is enrolled, then runs three
/// cooperating loops — heartbeat/presence, input-activity polling, and session-event upload — and reacts to
/// OS session/power signals. All monitoring is policy-gated and never inspects input or content.
/// </summary>
public sealed class AgentRunner
{
    private readonly IBackendApi _api;
    private readonly CredentialStore _credentials;
    private readonly ISystemInfo _systemInfo;
    private readonly IInputMonitor _input;
    private readonly ISystemEventSource _events;
    private readonly ISystemClock _clock;
    private readonly AgentOptions _options;
    private readonly ILogger<AgentRunner> _logger;
    private readonly MonitoringCollector _monitoring;
    private readonly IScreenCaptureProvider _screenCapture;
    private readonly SessionEventQueue _queue = new();
    private readonly ActivityTracker _tracker = new();
    private readonly object _gate = new();
    private readonly SemaphoreSlim _reenrollLock = new(1, 1);

    private DeviceCredential _credential = null!;
    private PolicySettings _policy = null!;
    private volatile string _policyRevision = "";
    private readonly string _agentVersion;

    private readonly ISystemAudioCapture? _audioCapture;
    private readonly IRemoteInputInjector? _remoteInput;
    private readonly ISystemClipboard? _clipboard;
    private readonly WebAccessManager? _webAccess;
    private readonly IWebEnforcement? _webEnforcement;
    private readonly IScreenshotEventSource? _screenshotEvents;

    public AgentRunner(IBackendApi api, CredentialStore credentials, ISystemInfo systemInfo, IInputMonitor input,
        ISystemEventSource events, ISystemClock clock, MonitoringCollector monitoring,
        IScreenCaptureProvider screenCapture, IOptions<AgentOptions> options, ILogger<AgentRunner> logger,
        ISystemAudioCapture? audioCapture = null, IRemoteInputInjector? remoteInput = null,
        ISystemClipboard? clipboard = null, WebAccessManager? webAccess = null, IWebEnforcement? webEnforcement = null,
        IScreenshotEventSource? screenshotEvents = null)
    {
        _api = api;
        _credentials = credentials;
        _systemInfo = systemInfo;
        _input = input;
        _events = events;
        _clock = clock;
        _monitoring = monitoring;
        _screenCapture = screenCapture;
        _options = options.Value;
        _logger = logger;
        _audioCapture = audioCapture;
        _remoteInput = remoteInput;
        _clipboard = clipboard;
        _webAccess = webAccess;
        _webEnforcement = webEnforcement;
        _screenshotEvents = screenshotEvents;
        _agentVersion = typeof(AgentRunner).Assembly.GetName().Version?.ToString(3) ?? "1.0.0";
    }

    public string StatusString => _tracker.StatusString;
    public DeviceCredential Credential => _credential;

    public async Task RunAsync(CancellationToken ct)
    {
        await EnsureEnrolledAsync(ct);

        _events.Raised += OnSystemSignal;
        _events.Start();

        // Mark the agent up, then sync policy which also establishes the current activity baseline.
        EmitSession(SessionEventTypes.AgentStart);
        try
        {
            await SyncPolicyAsync(ct);
        }
        catch (BackendException ex) when (ex.IsAuthFailure)
        {
            // The stored credential is no longer accepted (a stale device.dat from a previous backend, a
            // server DB reset, or the device removed/rotated server-side). Clear it and enroll again with the
            // enrollment token instead of crashing the agent.
            _logger.LogWarning("Stored credential rejected on startup ({Code}); re-enrolling.", ex.ErrorCode);
            await ReenrollAsync(_credential.DeviceToken, ct);
            await SyncPolicyAsync(ct);
        }

        var liveView = new LiveViewClient(_options.BackendBaseUrl, _screenCapture, _logger,
            () => _policy?.LiveView ?? false,
            () => { lock (_gate) { return _tracker.Current == ActivityState.Locked; } },
            audio: _audioCapture, input: _remoteInput, clipboard: _clipboard,
            // null/true => require consent; only an explicit policy opt-out enables unattended control.
            requireControlConsent: () => _policy?.RequireControlConsent != false);
        liveView.Configure(_credential.DeviceToken);

        var updater = new AgentUpdater(_api, _agentVersion, _options.UpdatePublicKey,
            Path.Combine(DataDirectory(), "staged-updates"), _logger);

        _logger.LogInformation("Agent running as {DeviceCode} (employee {EmployeeId}); monitoring from policy {Rev}",
            _credential.DeviceCode, _credential.EmployeeId, _policyRevision);

        try
        {
            await Task.WhenAll(HeartbeatLoop(ct), ActivityLoop(ct), FlushLoop(ct), CaptureLoop(ct),
                liveView.RunAsync(ct), UpdateLoop(updater, ct), WebAccessLoop(ct));
        }
        catch (OperationCanceledException)
        {
            // normal shutdown
        }
        finally
        {
            _events.Raised -= OnSystemSignal;
            _events.Dispose();
            await ShutdownAsync();
        }
    }

    private TimeSpan IdleThreshold => TimeSpan.FromSeconds(_policy?.IdleThresholdSeconds ?? _options.DefaultIdleThresholdSeconds);

    // ---------- Enrollment ----------

    private async Task EnsureEnrolledAsync(CancellationToken ct)
    {
        var existing = _credentials.Load();
        if (existing is not null)
        {
            _credential = existing;
            _logger.LogInformation("Loaded existing enrollment for {DeviceCode}", existing.DeviceCode);
            return;
        }

        await EnrollWithTokenAsync(ct);
    }

    /// <summary>
    /// Registers this device with the backend using the enrollment token and persists the returned credential.
    /// Used for first-run enrollment and for recovery when a stored credential is rejected
    /// (see <see cref="ReenrollAsync"/>).
    /// </summary>
    private async Task EnrollWithTokenAsync(CancellationToken ct)
    {
        var token = Environment.GetEnvironmentVariable("EM_ENROLLMENT_TOKEN") ?? _options.EnrollmentToken;
        if (string.IsNullOrWhiteSpace(token))
        {
            throw new InvalidOperationException(
                "Device is not enrolled and no enrollment token was provided. Set EM_ENROLLMENT_TOKEN or " +
                "Agent:EnrollmentToken for first run.");
        }

        var request = new RegisterDeviceRequest(
            token.Trim(), _systemInfo.Hostname, _systemInfo.OsVersion, _agentVersion,
            _systemInfo.MacAddress, _systemInfo.PrimaryIpAddress, _systemInfo.HardwareId);

        _logger.LogInformation("Enrolling device {Hostname}...", _systemInfo.Hostname);
        var response = await _api.RegisterAsync(request, ct);
        _credential = new DeviceCredential(response.DeviceId, response.DeviceCode, response.DeviceToken,
            response.DeviceTokenExpiresAt, response.Employee?.Id, _systemInfo.HardwareId);
        _credentials.Save(_credential);
        _policy = response.Policy;
        _policyRevision = response.PolicyRevision;
        _logger.LogInformation("Enrolled as {DeviceCode}", response.DeviceCode);
    }

    /// <summary>
    /// Recovers from a rejected device credential by clearing it and enrolling again with the enrollment token.
    /// Guarded so that if several loops observe the 401 at once, only the first re-enrolls; the others see the
    /// refreshed credential and return without consuming another enrollment-token use. <paramref name="rejectedToken"/>
    /// is the device token that failed, so a caller whose token was already replaced becomes a no-op.
    /// </summary>
    private async Task ReenrollAsync(string rejectedToken, CancellationToken ct)
    {
        await _reenrollLock.WaitAsync(ct);
        try
        {
            if (!string.Equals(_credential?.DeviceToken, rejectedToken, StringComparison.Ordinal))
            {
                return; // another loop already re-enrolled with a fresh credential
            }
            _logger.LogWarning("Clearing the rejected device credential and re-enrolling.");
            _credentials.Clear();
            await EnrollWithTokenAsync(ct);
        }
        finally
        {
            _reenrollLock.Release();
        }
    }

    // ---------- Loops ----------

    private async Task HeartbeatLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            var deviceToken = _credential.DeviceToken;
            try
            {
                var snap = _monitoring.Snapshot();
                var request = new HeartbeatRequest(_tracker.StatusString, _clock.UtcNow, _agentVersion,
                    _systemInfo.PrimaryIpAddress, snap.App, snap.Network, snap.Zscaler);
                var response = await _api.HeartbeatAsync(request, deviceToken, ct);
                if (!string.Equals(response.PolicyRevision, _policyRevision, StringComparison.Ordinal))
                {
                    _logger.LogInformation("Policy changed {Old} -> {New}; re-syncing",
                        _policyRevision, response.PolicyRevision);
                    await SyncPolicyAsync(ct);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (BackendException ex) when (ex.IsAuthFailure)
            {
                // The credential was rejected mid-run (token expired/rotated, or the device removed
                // server-side). Try to recover in place so monitoring resumes without waiting for a restart.
                _logger.LogWarning("Device credential rejected ({Code}); attempting re-enrollment.", ex.ErrorCode);
                try
                {
                    await ReenrollAsync(deviceToken, ct);
                    _logger.LogInformation("Re-enrolled as {DeviceCode}; monitoring resumes.", _credential.DeviceCode);
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested)
                {
                    break;
                }
                catch (Exception reEx)
                {
                    _logger.LogError(reEx, "Re-enrollment failed; stopping monitoring until the agent restarts.");
                    break;
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning("Heartbeat failed: {Message}", ex.Message);
            }
            await DelaySeconds(HeartbeatSeconds, ct);
        }
    }

    private async Task ActivityLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            await DelaySeconds(_options.ActivityPollSeconds, ct);
            ActivityState current;
            lock (_gate)
            {
                var transition = _tracker.Poll(_input.GetIdleTime(), IdleThreshold);
                if (transition is { } state)
                {
                    EmitActivity(state);
                }
                current = _tracker.Current;
            }
            // Provider sampling can enumerate processes/drives, so run it outside the signal lock.
            var now = _clock.UtcNow;
            _monitoring.Poll(_policy, MonitoringSchedule.IsWithinWindow(_policy, now),
                current == ActivityState.Active, now);
        }
    }

    private string DataDirectory() => string.IsNullOrWhiteSpace(_options.DataDirectory)
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "EmployeeMonitor")
        : _options.DataDirectory;

    private async Task UpdateLoop(AgentUpdater updater, CancellationToken ct)
    {
        if (!updater.Enabled)
        {
            return;
        }
        while (!ct.IsCancellationRequested)
        {
            try
            {
                await updater.CheckAndStageAsync(_credential.DeviceToken, ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                _logger.LogWarning("Update check error: {Message}", ex.Message);
            }
            await DelaySeconds(Math.Max(1, _options.UpdateCheckIntervalHours) * 3600, ct);
        }
    }

    // Screenshots are event-driven: one is stored only when the employee actually takes a screenshot, instead of
    // on a fixed timer. A short minimum gap coalesces the near-simultaneous signals a single screenshot can raise
    // (e.g. clipboard image + saved file) and rate-limits rapid repeats so the store can't be flooded.
    private const int MinScreenshotGapMs = 1500;
    private readonly Channel<byte> _screenshotRequests =
        Channel.CreateBounded<byte>(new BoundedChannelOptions(8) { FullMode = BoundedChannelFullMode.DropOldest });

    private void OnScreenshotTaken() => _screenshotRequests.Writer.TryWrite(0);

    private async Task CaptureLoop(CancellationToken ct)
    {
        if (_screenshotEvents is null)
        {
            return;   // no screenshot detector available (non-Windows / tests) -> no screenshots are stored
        }
        try
        {
            _screenshotEvents.Start(OnScreenshotTaken);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Screenshot detector failed to start: {Message}", ex.Message);
            return;
        }

        DateTimeOffset? last = null;
        try
        {
            while (await _screenshotRequests.Reader.WaitToReadAsync(ct))
            {
                while (_screenshotRequests.Reader.TryRead(out _))
                {
                    var now = _clock.UtcNow;
                    if (last is not null && (now - last.Value).TotalMilliseconds < MinScreenshotGapMs)
                    {
                        continue;   // coalesce the double-signal / rate-limit repeats
                    }
                    ActivityState status;
                    lock (_gate)
                    {
                        status = _tracker.Current;
                    }
                    bool eligible = ScreenshotGate.ShouldCapture(_policy,
                        MonitoringSchedule.IsWithinWindow(_policy, now), status);
                    if (!eligible)
                    {
                        continue;   // screenshots disabled by policy, outside the work window, or workstation locked
                    }
                    await CaptureOnceAsync(ct);
                    last = now;
                }
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // normal shutdown
        }
        finally
        {
            try { _screenshotEvents.Stop(); } catch { /* best-effort */ }
        }
    }

    private async Task CaptureOnceAsync(CancellationToken ct)
    {
        var now = _clock.UtcNow;
        ScreenCapture? capture;
        try
        {
            capture = _screenCapture.Capture();
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Screen capture failed: {Message}", ex.Message);
            return;
        }
        if (capture is null)
        {
            return;
        }
        try
        {
            await _api.PostScreenshotAsync(new MonScreenshot(Guid.NewGuid(), now, capture.ContentType,
                capture.Width, capture.Height, Convert.ToBase64String(capture.Bytes)), _credential.DeviceToken, ct);
            _logger.LogDebug("Uploaded screenshot ({Bytes} bytes)", capture.Bytes.Length);
        }
        catch (BackendException ex) when (ex.IsAuthFailure)
        {
            _logger.LogError("Screenshot upload rejected ({Code})", ex.ErrorCode);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested)
        {
            // shutting down
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Screenshot upload failed: {Message}", ex.Message);
        }
    }

    private async Task FlushLoop(CancellationToken ct)
    {
        while (!ct.IsCancellationRequested)
        {
            await DelaySeconds(_options.EventFlushSeconds, ct);
            await FlushAsync(ct);
        }
    }

    private async Task FlushAsync(CancellationToken ct)
    {
        await _monitoring.FlushAsync(_api, _credential.DeviceToken, ct);
        var batch = _queue.Drain(200);
        if (batch.Count == 0)
        {
            return;
        }
        try
        {
            var response = await _api.PostSessionEventsAsync(new SessionIngestRequest(batch), _credential.DeviceToken, ct);
            _logger.LogDebug("Uploaded {Count} session events ({Dup} dup, {Rej} rejected)",
                batch.Count, response.Duplicates, response.Rejected);
        }
        catch (BackendException ex) when (ex.IsAuthFailure)
        {
            _logger.LogError("Session upload rejected ({Code}); dropping batch", ex.ErrorCode);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Session upload failed ({Message}); will retry", ex.Message);
            _queue.Requeue(batch);
        }
    }

    // ---------- Website restriction & access monitoring ----------

    /// <summary>
    /// Fetches the effective web-access policy, applies the Chrome/Edge enterprise block/allow lists when the
    /// policy revision changes (blocking is continuous, not window-gated), reports enforcement health honestly,
    /// and uploads the domain-level navigation observations collected by the monitoring poll.
    /// </summary>
    private async Task WebAccessLoop(CancellationToken ct)
    {
        if (_webAccess is null)
        {
            return;
        }
        string appliedRevision = "";
        while (!ct.IsCancellationRequested)
        {
            try
            {
                var policy = await _api.GetWebPolicyAsync(_credential.DeviceToken, ct);
                _webAccess.SetPolicy(policy);

                if (!string.Equals(policy.Revision, appliedRevision, StringComparison.Ordinal))
                {
                    bool nothingToBlock = policy.Block.Count == 0;
                    var result = _webEnforcement?.Apply(policy.Block, policy.Allow)
                        ?? new WebEnforcementResult("none", nothingToBlock, null,
                            nothingToBlock ? null : "No web-enforcement component is available on this platform.");
                    try
                    {
                        await _api.PostWebEnforcementStatusAsync(new WebEnforcementReport(
                            result.Mechanism, result.Healthy, policy.Revision, result.Browsers, result.Error),
                            _credential.DeviceToken, ct);
                    }
                    catch (Exception ex)
                    {
                        _logger.LogDebug("Web enforcement status report failed: {Message}", ex.Message);
                    }
                    if (result.Healthy)
                    {
                        // Only advance once applied, so a failed apply (e.g. missing rights) is retried next cycle.
                        appliedRevision = policy.Revision;
                        _logger.LogInformation("Web policy {Rev} enforced via {Mech}: {Block} blocked, {Allow} allowed.",
                            policy.Revision, result.Mechanism, policy.Block.Count, policy.Allow.Count);
                    }
                    else
                    {
                        _logger.LogWarning("Web policy {Rev} not enforced: {Error}", policy.Revision, result.Error);
                    }
                }

                var events = _webAccess.DrainEvents();
                if (events.Count > 0)
                {
                    try
                    {
                        await _api.PostWebAccessEventsAsync(new WebAccessBatch(events), _credential.DeviceToken, ct);
                    }
                    catch (Exception ex)
                    {
                        _logger.LogDebug("Web-access event upload failed: {Message}", ex.Message);
                    }
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                break;
            }
            catch (BackendException ex) when (ex.IsAuthFailure)
            {
                _logger.LogError("Web policy fetch rejected ({Code}); stopping web-access loop.", ex.ErrorCode);
                break;
            }
            catch (Exception ex)
            {
                _logger.LogDebug("Web access loop error: {Message}", ex.Message);
            }
            await DelaySeconds(Math.Clamp(_options.WebAccessPollSeconds, 10, 300), ct);
        }
    }

    // ---------- Policy ----------

    private async Task SyncPolicyAsync(CancellationToken ct)
    {
        var policy = await _api.GetPolicyAsync(_credential.DeviceToken, ct);
        _policy = policy.Settings;
        _policyRevision = policy.Revision;
        _logger.LogInformation(
            "Policy {Rev}: idle={Idle}s heartbeat={Hb}s session={Session} screenshots={Shots} liveView={Live}",
            policy.Revision, policy.Settings.IdleThresholdSeconds, policy.Settings.HeartbeatIntervalSeconds,
            policy.Settings.SessionMonitoring, policy.Settings.Screenshots, policy.Settings.LiveView);

        // Re-establish a current activity baseline so the backend always has the state after a (re)sync,
        // including when a change opens the monitoring window with no input transition to trigger it.
        lock (_gate)
        {
            _tracker.Poll(_input.GetIdleTime(), IdleThreshold);
            if (_tracker.Current != ActivityState.Locked)
            {
                EmitActivity(_tracker.Current);
            }
        }
    }

    private int HeartbeatSeconds =>
        Math.Clamp(_policy?.HeartbeatIntervalSeconds ?? _options.DefaultHeartbeatSeconds, 10, 300);

    // ---------- Signal handling ----------

    private void OnSystemSignal(SystemSignal signal)
    {
        lock (_gate)
        {
            switch (signal)
            {
                case SystemSignal.Lock:
                    _tracker.OnLocked();
                    EmitSession(SessionEventTypes.Lock);
                    break;
                case SystemSignal.Unlock:
                    EmitSession(SessionEventTypes.Unlock);
                    EmitActivity(_tracker.OnUnlocked(_input.GetIdleTime(), IdleThreshold));
                    break;
                case SystemSignal.Login:
                    EmitSession(SessionEventTypes.Login);
                    EmitActivity(_tracker.OnUnlocked(_input.GetIdleTime(), IdleThreshold));
                    break;
                case SystemSignal.Logout:
                    EmitSession(SessionEventTypes.Logout);
                    break;
                case SystemSignal.Sleep:
                    EmitSession(SessionEventTypes.Sleep);
                    break;
                case SystemSignal.Resume:
                    EmitSession(SessionEventTypes.Resume);
                    EmitActivity(_tracker.OnUnlocked(_input.GetIdleTime(), IdleThreshold));
                    break;
                case SystemSignal.Shutdown:
                    EmitSession(SessionEventTypes.Shutdown);
                    break;
            }
        }
    }

    // ---------- Emit helpers (caller holds _gate for activity/session consistency) ----------

    private void EmitActivity(ActivityState state)
    {
        if (SessionCaptureEnabled)
        {
            _queue.Enqueue(SessionEventTypes.ForActivity(state), _clock.UtcNow);
        }
    }

    private void EmitSession(string type)
    {
        // Lifecycle markers are always recorded; other session events honour the policy window and toggle.
        bool lifecycle = type is SessionEventTypes.AgentStart or SessionEventTypes.AgentStop;
        if (lifecycle || SessionCaptureEnabled)
        {
            _queue.Enqueue(type, _clock.UtcNow);
        }
    }

    private bool SessionCaptureEnabled =>
        _policy is not null && _policy.SessionMonitoring && MonitoringSchedule.IsWithinWindow(_policy, _clock.UtcNow);

    private async Task ShutdownAsync()
    {
        _monitoring.CloseOpenIntervals(_clock.UtcNow);
        EmitSession(SessionEventTypes.AgentStop);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        try
        {
            await FlushAsync(cts.Token);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Final session flush failed: {Message}", ex.Message);
        }
    }

    private static async Task DelaySeconds(int seconds, CancellationToken ct)
    {
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(Math.Max(1, seconds)), ct);
        }
        catch (OperationCanceledException)
        {
        }
    }
}
