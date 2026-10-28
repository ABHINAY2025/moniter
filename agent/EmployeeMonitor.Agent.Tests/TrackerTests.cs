using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Monitoring;
using FluentAssertions;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class AppUsageTrackerTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.UnixEpoch;

    [Fact]
    public void EmitsIntervalWhenApplicationChanges()
    {
        var t = new AppUsageTracker();
        t.Sample("Code", "Code.exe", T0).Should().BeNull();                      // first app, nothing to close
        t.Sample("Code", "Code.exe", T0.AddMinutes(10)).Should().BeNull();       // unchanged
        var interval = t.Sample("Chrome", "chrome.exe", T0.AddMinutes(15));      // switch -> close Code
        interval.Should().NotBeNull();
        interval!.Application.Should().Be("Code");
        interval.Start.Should().Be(T0);
        interval.End.Should().Be(T0.AddMinutes(15));
    }

    [Fact]
    public void NullSampleClosesCurrentInterval()
    {
        var t = new AppUsageTracker();
        t.Sample("Outlook", "outlook.exe", T0);
        var interval = t.Sample(null, null, T0.AddMinutes(5));   // user went idle/locked
        interval!.Application.Should().Be("Outlook");
        interval.End.Should().Be(T0.AddMinutes(5));
        // No open interval remains.
        t.Close(T0.AddMinutes(6)).Should().BeNull();
    }

    [Fact]
    public void CloseReturnsOpenIntervalOnce()
    {
        var t = new AppUsageTracker();
        t.Sample("Teams", "teams.exe", T0);
        t.Close(T0.AddMinutes(3))!.Application.Should().Be("Teams");
        t.Close(T0.AddMinutes(4)).Should().BeNull();
    }
}

public class NetworkTrackerTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.UnixEpoch;

    [Fact]
    public void EmitsConnectedThenChangedThenDisconnected()
    {
        var t = new NetworkTracker();
        t.Sample(new NetworkSnapshot(null, null, null)).Should().BeNull();          // still disconnected
        t.Sample(new NetworkSnapshot("ETHERNET", "CORP", "10.0.0.5"))!.EventType.Should().Be("CONNECTED");
        t.Sample(new NetworkSnapshot("ETHERNET", "CORP", "10.0.0.5")).Should().BeNull(); // unchanged
        t.Sample(new NetworkSnapshot("WIFI", "GUEST", "192.168.1.9"))!.EventType.Should().Be("CHANGED");
        t.Sample(new NetworkSnapshot(null, null, null))!.EventType.Should().Be("DISCONNECTED");
    }
}

public class StatusChangeTrackerTests
{
    [Fact]
    public void ReportsOnlyOnChange()
    {
        var t = new StatusChangeTracker();
        t.Changed("CONNECTED").Should().BeTrue();
        t.Changed("CONNECTED").Should().BeFalse();
        t.Changed("DISCONNECTED").Should().BeTrue();
    }
}

public class UsbDriveTrackerTests
{
    private static RemovableDrive Drive(string key, string? label = null) => new(key, label);

    [Fact]
    public void DetectsConnectAndRemove()
    {
        var t = new UsbDriveTracker();
        t.Sample(new[] { Drive("E:", "KINGSTON") })
            .Should().ContainSingle(c => c.EventType == "CONNECTED" && c.Drive.Key == "E:");

        t.Sample(new[] { Drive("E:", "KINGSTON") }).Should().BeEmpty();   // unchanged

        var removed = t.Sample(Array.Empty<RemovableDrive>());
        removed.Should().ContainSingle(c => c.EventType == "REMOVED" && c.Drive.Key == "E:");
    }

    [Fact]
    public void HandlesSimultaneousConnectAndRemove()
    {
        var t = new UsbDriveTracker();
        t.Sample(new[] { Drive("E:") });
        var changes = t.Sample(new[] { Drive("F:") });
        changes.Should().HaveCount(2);
        changes.Should().Contain(c => c.EventType == "CONNECTED" && c.Drive.Key == "F:");
        changes.Should().Contain(c => c.EventType == "REMOVED" && c.Drive.Key == "E:");
    }
}

public class DnsVisitTrackerTests
{
    [Fact]
    public void NormalizesToRegistrableDomain()
    {
        DnsVisitTracker.Normalize("www.youtube.com").Should().Be("youtube.com");
        DnsVisitTracker.Normalize("i.ytimg.com").Should().Be("ytimg.com");
        DnsVisitTracker.Normalize("news.bbc.co.uk").Should().Be("bbc.co.uk");
        DnsVisitTracker.Normalize("GOOGLE.COM.").Should().Be("google.com");
    }

    [Fact]
    public void SkipsLocalReverseAndIpNames()
    {
        DnsVisitTracker.Normalize("localhost").Should().BeNull();
        DnsVisitTracker.Normalize("myhost.local").Should().BeNull();
        DnsVisitTracker.Normalize("1.0.0.127.in-addr.arpa").Should().BeNull();
        DnsVisitTracker.Normalize("10.0.0.5").Should().BeNull();
        DnsVisitTracker.Normalize("singlelabel").Should().BeNull();
    }

    [Fact]
    public void EmitsEachDomainOnceUntilItLeavesAndReturns()
    {
        var t = new DnsVisitTracker();
        t.Sample(new[] { "www.youtube.com", "i.ytimg.com" })
            .Should().BeEquivalentTo("youtube.com", "ytimg.com");
        // Same hosts still cached -> no new visits.
        t.Sample(new[] { "www.youtube.com", "i.ytimg.com" }).Should().BeEmpty();
        // youtube expires, github appears -> only github is new.
        t.Sample(new[] { "i.ytimg.com", "github.com" }).Should().BeEquivalentTo("github.com");
        // youtube resolved again after leaving -> counts as a new visit.
        t.Sample(new[] { "youtube.com" }).Should().BeEquivalentTo("youtube.com");
    }
}

public class MonitoringCollectorTests
{
    private static readonly PolicySettings AllOn = TestData.Policy() with { ApplicationMonitoring = true };

    [Fact]
    public async Task CollectsAppUsageNetworkZscalerAndUsbThenUploads()
    {
        var foreground = new FakeForegroundAppProvider();
        var network = new FakeNetworkProvider();
        var zscaler = new FakeZscalerProvider();
        var drives = new FakeRemovableDriveProvider();
        var dns = new FakeDnsVisitProvider();
        var collector = new MonitoringCollector(foreground, network, zscaler, drives, dns,
            Microsoft.Extensions.Logging.Abstractions.NullLogger<MonitoringCollector>.Instance);
        var api = new FakeBackendApi();
        var now = DateTimeOffset.UnixEpoch;
        var policy = AllOn with { WebsiteMonitoring = true };

        foreground.Current = new EmployeeMonitor.Agent.Monitoring.ForegroundApp("Code", "Code.exe");
        network.Snapshot = new NetworkSnapshot("ETHERNET", "CORP", "10.0.0.5");
        zscaler.Status = "CONNECTED";
        drives.Drives.Add(new RemovableDrive("E:", "KINGSTON"));
        dns.Hosts.Add("www.youtube.com");

        collector.Poll(policy, withinWindow: true, userActive: true, now);
        // App usage interval only completes when the app changes.
        foreground.Current = new EmployeeMonitor.Agent.Monitoring.ForegroundApp("Chrome", "chrome.exe");
        collector.Poll(policy, withinWindow: true, userActive: true, now.AddMinutes(5));

        var snap = collector.Snapshot();
        snap.App.Should().Be("Chrome");
        snap.Network.Should().Be("CORP");
        snap.Zscaler.Should().Be("CONNECTED");

        await collector.FlushAsync(api, "token", CancellationToken.None);
        api.AppUsageUploaded.Should().Be(1);
        api.NetworkUploaded.Should().Be(1);   // CONNECTED
        api.ZscalerUploaded.Should().Be(1);   // CONNECTED
        api.UsbUploaded.Should().Be(1);       // E: connected
        api.WebsiteUploaded.Should().Be(1);   // youtube.com visit
        api.WebsiteDomains.Should().Contain("youtube.com");
    }

    [Fact]
    public async Task RespectsPolicyFlagsAndWindow()
    {
        var foreground = new FakeForegroundAppProvider { Current = new EmployeeMonitor.Agent.Monitoring.ForegroundApp("Code", "Code.exe") };
        var collector = new MonitoringCollector(foreground, new FakeNetworkProvider(), new FakeZscalerProvider(),
            new FakeRemovableDriveProvider(), new FakeDnsVisitProvider(),
            Microsoft.Extensions.Logging.Abstractions.NullLogger<MonitoringCollector>.Instance);
        var api = new FakeBackendApi();

        // Outside the monitoring window: nothing is captured and the live snapshot is cleared.
        collector.Poll(AllOn, withinWindow: false, userActive: true, DateTimeOffset.UnixEpoch);
        collector.Poll(AllOn, withinWindow: false, userActive: true, DateTimeOffset.UnixEpoch.AddMinutes(5));
        collector.Snapshot().App.Should().BeNull();
        await collector.FlushAsync(api, "token", CancellationToken.None);
        api.AppUsageUploaded.Should().Be(0);
    }
}
