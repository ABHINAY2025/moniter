using System.Diagnostics;
using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Configuration;
using EmployeeMonitor.Agent.Monitoring;
using EmployeeMonitor.Agent.Platform;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class AgentRunnerTests
{
    private static MonitoringCollector NewCollector() =>
        new(new FakeForegroundAppProvider(), new FakeNetworkProvider(), new FakeZscalerProvider(),
            new FakeRemovableDriveProvider(), new FakeDnsVisitProvider(), NullLogger<MonitoringCollector>.Instance);

    private static async Task WaitUntil(Func<bool> condition, TimeSpan timeout)
    {
        var sw = Stopwatch.StartNew();
        while (sw.Elapsed < timeout)
        {
            if (condition())
            {
                return;
            }
            await Task.Delay(50);
        }
        throw new TimeoutException("Condition not met within " + timeout);
    }

    private static (AgentRunner runner, FakeBackendApi api, FakeInputMonitor input, FakeSystemEventSource events)
        Build(string? enrollmentToken = "enr_test")
    {
        var api = new FakeBackendApi();
        var input = new FakeInputMonitor();
        var events = new FakeSystemEventSource();
        var store = new CredentialStore(new InMemorySecureStore(), NullLogger<CredentialStore>.Instance);
        var options = Options.Create(new AgentOptions
        {
            BackendBaseUrl = "http://localhost",
            EnrollmentToken = enrollmentToken,
            ActivityPollSeconds = 1,
            EventFlushSeconds = 1,
            DefaultHeartbeatSeconds = 10,
        });
        var runner = new AgentRunner(api, store, new FakeSystemInfo(), input, events, new FakeClock(),
            NewCollector(), new FakeScreenCaptureProvider { Next = null }, options, NullLogger<AgentRunner>.Instance);
        return (runner, api, input, events);
    }

    [Fact]
    public async Task EnrollsHeartbeatsAndEmitsLifecycleAndSessionEvents()
    {
        var (runner, api, _, events) = Build();
        using var cts = new CancellationTokenSource();
        var run = runner.RunAsync(cts.Token);

        await WaitUntil(() => api.Events.Contains("AGENT_START") && api.Events.Contains("ACTIVE"),
            TimeSpan.FromSeconds(10));
        events.Started.Should().BeTrue();
        api.RegisterCalls.Should().Be(1);

        events.Raise(SystemSignal.Lock);
        await WaitUntil(() => api.Events.Contains("LOCK"), TimeSpan.FromSeconds(5));
        runner.StatusString.Should().Be("LOCKED");

        events.Raise(SystemSignal.Unlock);
        await WaitUntil(() => api.Events.Contains("UNLOCK"), TimeSpan.FromSeconds(5));
        runner.StatusString.Should().Be("ACTIVE");

        api.HeartbeatCalls.Should().BeGreaterThanOrEqualTo(1);
        api.LastStatus.Should().BeOneOf("ACTIVE", "IDLE", "LOCKED");

        cts.Cancel();
        await run;
        api.Events.Should().Contain("AGENT_STOP");
    }

    [Fact]
    public async Task ReusesStoredCredentialWithoutReenrolling()
    {
        var store = new CredentialStore(new InMemorySecureStore(), NullLogger<CredentialStore>.Instance);
        store.Save(new DeviceCredential(42, "DEV-10042", "dvt_existing", null, 7, "hw-test-guid"));

        var api = new FakeBackendApi();
        var options = Options.Create(new AgentOptions
        {
            BackendBaseUrl = "http://localhost", EnrollmentToken = null,
            ActivityPollSeconds = 1, EventFlushSeconds = 1, DefaultHeartbeatSeconds = 10,
        });
        var runner = new AgentRunner(api, store, new FakeSystemInfo(), new FakeInputMonitor(),
            new FakeSystemEventSource(), new FakeClock(), NewCollector(), new FakeScreenCaptureProvider { Next = null },
            options, NullLogger<AgentRunner>.Instance);

        using var cts = new CancellationTokenSource();
        var run = runner.RunAsync(cts.Token);
        await WaitUntil(() => api.HeartbeatCalls >= 1, TimeSpan.FromSeconds(10));

        api.RegisterCalls.Should().Be(0);
        runner.Credential.DeviceCode.Should().Be("DEV-10042");

        cts.Cancel();
        await run;
    }

    [Fact]
    public async Task ReenrollsWhenStoredCredentialIsRejected()
    {
        // A stale credential from a previous backend (or a server DB reset): the server rejects it with 401.
        // The agent must clear it, enroll again with the token, and keep running instead of crashing.
        var store = new CredentialStore(new InMemorySecureStore(), NullLogger<CredentialStore>.Instance);
        store.Save(new DeviceCredential(99, "DEV-OLD", "dvt_stale", null, null, "hw-test-guid"));

        var api = new FakeBackendApi { RejectPolicyForToken = "dvt_stale" };
        var options = Options.Create(new AgentOptions
        {
            BackendBaseUrl = "http://localhost", EnrollmentToken = "enr_test",
            ActivityPollSeconds = 1, EventFlushSeconds = 1, DefaultHeartbeatSeconds = 10,
        });
        var runner = new AgentRunner(api, store, new FakeSystemInfo(), new FakeInputMonitor(),
            new FakeSystemEventSource(), new FakeClock(), NewCollector(), new FakeScreenCaptureProvider { Next = null },
            options, NullLogger<AgentRunner>.Instance);

        using var cts = new CancellationTokenSource();
        var run = runner.RunAsync(cts.Token);

        // It recovers to a live, heartbeating state rather than throwing out of RunAsync.
        await WaitUntil(() => api.HeartbeatCalls >= 1, TimeSpan.FromSeconds(10));
        api.RegisterCalls.Should().Be(1);                      // re-enrolled exactly once
        runner.Credential.DeviceCode.Should().Be("DEV-10042"); // now using the fresh credential

        cts.Cancel();
        await run;
    }

    [Fact]
    public async Task FailsFastWhenNotEnrolledAndNoToken()
    {
        var (runner, _, _, _) = Build(enrollmentToken: null);
        Environment.SetEnvironmentVariable("EM_ENROLLMENT_TOKEN", null);
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));

        var act = async () => await runner.RunAsync(cts.Token);
        await act.Should().ThrowAsync<InvalidOperationException>()
            .Where(e => e.Message.Contains("not enrolled"));
    }

    [Fact]
    public async Task CapturesAndUploadsScreenshotsWhenEnabled()
    {
        var api = new FakeBackendApi { Policy = TestData.Policy() with { Screenshots = true, ScreenshotIntervalSeconds = 300 } };
        var store = new CredentialStore(new InMemorySecureStore(), NullLogger<CredentialStore>.Instance);
        var options = Options.Create(new AgentOptions
        {
            BackendBaseUrl = "http://localhost", EnrollmentToken = "enr_test",
            ActivityPollSeconds = 1, EventFlushSeconds = 1, DefaultHeartbeatSeconds = 10,
            ScreenshotInitialDelaySeconds = 1,
        });
        var capture = new FakeScreenCaptureProvider();
        var runner = new AgentRunner(api, store, new FakeSystemInfo(), new FakeInputMonitor(),
            new FakeSystemEventSource(), new FakeClock(), NewCollector(), capture, options,
            NullLogger<AgentRunner>.Instance);

        using var cts = new CancellationTokenSource();
        var run = runner.RunAsync(cts.Token);
        await WaitUntil(() => api.ScreenshotsUploaded >= 1, TimeSpan.FromSeconds(10));
        capture.Captures.Should().BeGreaterThanOrEqualTo(1);

        cts.Cancel();
        await run;
    }

    [Fact]
    public async Task SessionEventsSuppressedWhenSessionMonitoringDisabled()
    {
        var (runner, api, _, events) = Build();
        api.Policy = TestData.Policy(session: false); // heartbeats continue, session capture off

        using var cts = new CancellationTokenSource();
        var run = runner.RunAsync(cts.Token);
        await WaitUntil(() => api.HeartbeatCalls >= 1, TimeSpan.FromSeconds(10));

        events.Raise(SystemSignal.Lock);
        // Give the flush loop time to run; LOCK must NOT be uploaded, but lifecycle AGENT_START must be.
        await Task.Delay(2500);

        api.Events.Should().Contain("AGENT_START");
        api.Events.Should().NotContain("LOCK");
        api.Events.Should().NotContain("ACTIVE");

        cts.Cancel();
        await run;
    }
}
