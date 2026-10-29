using System.Text.Json;
using EmployeeMonitor.Agent;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class LiveViewClientTests
{
    private static LiveViewClient Build(FakeScreenCaptureProvider capture, Func<bool> allowed, Func<bool> locked) =>
        new("http://localhost:8080", capture, NullLogger.Instance, allowed, locked);

    private static LiveViewClient BuildWithControl(FakeRemoteInputInjector input, Func<bool> requireConsent,
        Func<bool>? allowed = null) =>
        new("http://localhost:8080", new FakeScreenCaptureProvider(), NullLogger.Instance,
            allowed ?? (() => true), () => false, input: input, requireControlConsent: requireConsent);

    [Fact]
    public void StartActivatesAndReturnsReadyAck()
    {
        var client = Build(new FakeScreenCaptureProvider(), () => true, () => false);
        client.Active.Should().BeFalse();

        var ack = client.OnControlMessage("{\"type\":\"START\",\"sessionId\":\"abc-123\"}");
        client.Active.Should().BeTrue();
        JsonDocument.Parse(ack!).RootElement.GetProperty("type").GetString().Should().Be("READY");
        JsonDocument.Parse(ack!).RootElement.GetProperty("sessionId").GetString().Should().Be("abc-123");

        client.OnControlMessage("{\"type\":\"STOP\"}").Should().BeNull();
        client.Active.Should().BeFalse();
    }

    [Fact]
    public void BuildFrameOnlyWhenActiveAllowedAndUnlocked()
    {
        var capture = new FakeScreenCaptureProvider();
        bool allowed = true, locked = false;
        var client = Build(capture, () => allowed, () => locked);

        client.BuildFrameMessage().Should().BeNull();            // not active

        client.OnControlMessage("{\"type\":\"START\",\"sessionId\":\"s1\"}");
        var frame = client.BuildFrameMessage();
        frame.Should().NotBeNull();
        var root = JsonDocument.Parse(frame!).RootElement;
        root.GetProperty("type").GetString().Should().Be("FRAME");
        root.GetProperty("sessionId").GetString().Should().Be("s1");
        root.GetProperty("data").GetString().Should().NotBeNullOrEmpty();

        locked = true;
        client.BuildFrameMessage().Should().BeNull();            // locked -> no capture

        locked = false;
        allowed = false;
        client.BuildFrameMessage().Should().BeNull();            // policy disabled -> no capture
    }

    [Fact]
    public void NoFrameWhenCaptureUnavailable()
    {
        var client = Build(new FakeScreenCaptureProvider { Next = null }, () => true, () => false);
        client.OnControlMessage("{\"type\":\"START\",\"sessionId\":\"s2\"}");
        client.BuildFrameMessage().Should().BeNull();
    }

    [Fact]
    public void UnattendedControlGrantsWithoutPromptButAlwaysShowsBanner()
    {
        var input = new FakeRemoteInputInjector();
        var client = BuildWithControl(input, requireConsent: () => false);
        client.OnControlMessage("{\"type\":\"START\",\"sessionId\":\"s1\"}");

        client.EvaluateControlRequest("Alice (IT)").Should().Be(LiveViewClient.ControlDecision.Granted);
        input.ConsentPrompts.Should().Be(0);          // no prompt in unattended mode
        input.BannerShows.Should().Be(1);             // but the indicator is always shown
        input.BannerVisible.Should().BeTrue();
        input.LastBannerAdmin.Should().Be("Alice (IT)");

        // Ending the live-view session releases control and removes the banner.
        client.OnControlMessage("{\"type\":\"STOP\"}");
        input.BannerHides.Should().Be(1);
        input.BannerVisible.Should().BeFalse();
    }

    [Fact]
    public void AttendedControlNeedsConsentAndDoesNotShowBannerUntilGranted()
    {
        var input = new FakeRemoteInputInjector();
        var client = BuildWithControl(input, requireConsent: () => true);
        client.OnControlMessage("{\"type\":\"START\",\"sessionId\":\"s1\"}");

        client.EvaluateControlRequest("Bob (IT)").Should().Be(LiveViewClient.ControlDecision.NeedsConsent);
        input.BannerShows.Should().Be(0);             // consent path shows the banner only once the employee accepts
    }

    [Fact]
    public void ControlDeniedWhenLiveViewNotAllowed()
    {
        var input = new FakeRemoteInputInjector();
        var client = BuildWithControl(input, requireConsent: () => false, allowed: () => false);
        client.OnControlMessage("{\"type\":\"START\",\"sessionId\":\"s1\"}");

        client.EvaluateControlRequest("Mallory").Should().Be(LiveViewClient.ControlDecision.Denied);
        input.BannerShows.Should().Be(0);
    }
}
