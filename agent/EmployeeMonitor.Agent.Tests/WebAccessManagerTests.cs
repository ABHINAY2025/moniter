using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Monitoring;
using FluentAssertions;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class WebAccessManagerTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.UnixEpoch;

    private static EffectiveWebPolicy Policy(
        string revision = "abc", string[]? block = null, string[]? allow = null, string[]? monitor = null) =>
        new(revision, block ?? Array.Empty<string>(), allow ?? Array.Empty<string>(), monitor ?? Array.Empty<string>());

    [Fact]
    public void ObservesOnlyPolicyRelevantHostsSubdomainSafe()
    {
        var m = new WebAccessManager();
        m.SetPolicy(Policy(block: new[] { "facebook.com" }, monitor: new[] { "youtube.com" }));

        m.Observe(new[]
        {
            "www.facebook.com",          // subdomain of a blocked domain -> relevant
            "youtube.com",               // exact monitored domain -> relevant
            "facebook.com.attacker.test",// NOT a subdomain of facebook.com -> ignored
            "github.com",                // not in any list -> ignored
        }, T0);

        var events = m.DrainEvents();
        events.Select(e => e.Domain).Should().BeEquivalentTo("www.facebook.com", "youtube.com");
        events.Should().OnlyContain(e => e.EventType == "NAVIGATION_OBSERVED" && e.Source == "DNS_LOG");
    }

    [Fact]
    public void CanonicalizesHostsBeforeMatchingAndReporting()
    {
        var m = new WebAccessManager();
        m.SetPolicy(Policy(block: new[] { "netflix.com" }));

        m.Observe(new[] { "https://WWW.Netflix.com:443/browse" }, T0);

        var events = m.DrainEvents();
        events.Should().ContainSingle();
        events[0].Domain.Should().Be("www.netflix.com");
    }

    [Fact]
    public void DeduplicatesSameHostWithinWindowButReemitsAfter()
    {
        var m = new WebAccessManager();
        m.SetPolicy(Policy(block: new[] { "tiktok.com" }));

        m.Observe(new[] { "www.tiktok.com" }, T0);
        m.Observe(new[] { "www.tiktok.com" }, T0.AddMinutes(1));  // within 2-min dedup window
        m.DrainEvents().Should().ContainSingle();

        m.Observe(new[] { "www.tiktok.com" }, T0.AddMinutes(5));  // window elapsed -> new observation
        m.DrainEvents().Should().ContainSingle();
    }

    [Fact]
    public void DrainClearsPendingAndRevisionTracksPolicy()
    {
        var m = new WebAccessManager();
        m.Revision.Should().Be("0");
        m.SetPolicy(Policy(revision: "rev-9", block: new[] { "reddit.com" }));
        m.Revision.Should().Be("rev-9");

        m.Observe(new[] { "reddit.com" }, T0);
        m.DrainEvents().Should().ContainSingle();
        m.DrainEvents().Should().BeEmpty();
    }
}
