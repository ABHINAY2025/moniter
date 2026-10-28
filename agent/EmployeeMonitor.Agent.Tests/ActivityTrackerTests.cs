using EmployeeMonitor.Agent.Monitoring;
using FluentAssertions;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class ActivityTrackerTests
{
    private static readonly TimeSpan Threshold = TimeSpan.FromMinutes(5);

    [Fact]
    public void StartsActiveAndStaysActiveBelowThreshold()
    {
        var t = new ActivityTracker();
        t.Current.Should().Be(ActivityState.Active);
        t.Poll(TimeSpan.FromMinutes(1), Threshold).Should().BeNull();
        t.Current.Should().Be(ActivityState.Active);
    }

    [Fact]
    public void TransitionsToIdleAtThresholdAndBackToActiveOnInput()
    {
        var t = new ActivityTracker();
        t.Poll(TimeSpan.FromMinutes(5), Threshold).Should().Be(ActivityState.Idle);
        t.Poll(TimeSpan.FromMinutes(6), Threshold).Should().BeNull(); // still idle, no new transition
        t.Poll(TimeSpan.Zero, Threshold).Should().Be(ActivityState.Active);
    }

    [Fact]
    public void LockSuppressesIdlePollingAndHoldsLocked()
    {
        var t = new ActivityTracker();
        t.OnLocked();
        t.Current.Should().Be(ActivityState.Locked);
        t.StatusString.Should().Be("LOCKED");
        t.Poll(TimeSpan.FromHours(1), Threshold).Should().BeNull();
        t.Current.Should().Be(ActivityState.Locked);
    }

    [Fact]
    public void UnlockReestablishesStateFromCurrentIdle()
    {
        var t = new ActivityTracker();
        t.OnLocked();
        t.OnUnlocked(TimeSpan.FromMinutes(10), Threshold).Should().Be(ActivityState.Idle);
        t.Current.Should().Be(ActivityState.Idle);

        t.OnLocked();
        t.OnUnlocked(TimeSpan.Zero, Threshold).Should().Be(ActivityState.Active);
    }

    [Fact]
    public void StatusStringMatchesServerEnum()
    {
        var t = new ActivityTracker();
        t.StatusString.Should().Be("ACTIVE");
        t.Poll(TimeSpan.FromMinutes(5), Threshold);
        t.StatusString.Should().Be("IDLE");
    }
}
