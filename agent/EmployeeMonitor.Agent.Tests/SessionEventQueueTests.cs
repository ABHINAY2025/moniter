using EmployeeMonitor.Agent.Monitoring;
using EmployeeMonitor.Agent.Platform;
using FluentAssertions;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class SessionEventQueueTests
{
    private static readonly DateTimeOffset T0 = DateTimeOffset.UnixEpoch;

    [Fact]
    public void EnqueueAndDrainPreserveOrderAndAssignUniqueIds()
    {
        var q = new SessionEventQueue();
        q.Enqueue(SessionEventTypes.AgentStart, T0);
        q.Enqueue(SessionEventTypes.Active, T0.AddSeconds(1));

        q.Count.Should().Be(2);
        var batch = q.Drain(10);
        batch.Select(e => e.Type).Should().ContainInOrder(SessionEventTypes.AgentStart, SessionEventTypes.Active);
        batch.Select(e => e.ClientEventId).Distinct().Should().HaveCount(2);
        q.Count.Should().Be(0);
    }

    [Fact]
    public void DrainRespectsMaxBatchSize()
    {
        var q = new SessionEventQueue();
        for (int i = 0; i < 5; i++)
        {
            q.Enqueue(SessionEventTypes.Active, T0);
        }
        q.Drain(2).Should().HaveCount(2);
        q.Count.Should().Be(3);
    }

    [Fact]
    public void RequeueReturnsEventsForRetry()
    {
        var q = new SessionEventQueue();
        q.Enqueue(SessionEventTypes.Lock, T0);
        var batch = q.Drain(10);
        q.Count.Should().Be(0);

        q.Requeue(batch);
        q.Count.Should().Be(1);
        q.Drain(10).Single().Type.Should().Be(SessionEventTypes.Lock);
    }

    [Fact]
    public void CapacityIsBoundedByDroppingOldest()
    {
        var q = new SessionEventQueue(capacity: 3);
        for (int i = 0; i < 10; i++)
        {
            q.Enqueue(SessionEventTypes.Active, T0.AddSeconds(i));
        }
        q.Count.Should().Be(3);
    }

    [Theory]
    [InlineData(SystemSignal.Lock, SessionEventTypes.Lock)]
    [InlineData(SystemSignal.Unlock, SessionEventTypes.Unlock)]
    [InlineData(SystemSignal.Login, SessionEventTypes.Login)]
    [InlineData(SystemSignal.Logout, SessionEventTypes.Logout)]
    [InlineData(SystemSignal.Sleep, SessionEventTypes.Sleep)]
    [InlineData(SystemSignal.Resume, SessionEventTypes.Resume)]
    [InlineData(SystemSignal.Shutdown, SessionEventTypes.Shutdown)]
    public void SignalMappingMatchesServerTypes(SystemSignal signal, string expected)
    {
        SessionEventTypes.ForSignal(signal).Should().Be(expected);
    }
}
