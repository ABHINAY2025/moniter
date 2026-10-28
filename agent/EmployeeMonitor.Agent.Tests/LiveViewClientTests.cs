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
}
