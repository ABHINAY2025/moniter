using System.Collections.Concurrent;
using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Platform;

namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>Session/activity event type names, matching the backend's SessionEventType enum.</summary>
public static class SessionEventTypes
{
    public const string Login = "LOGIN";
    public const string Logout = "LOGOUT";
    public const string Lock = "LOCK";
    public const string Unlock = "UNLOCK";
    public const string Shutdown = "SHUTDOWN";
    public const string Sleep = "SLEEP";
    public const string Resume = "RESUME";
    public const string Active = "ACTIVE";
    public const string Idle = "IDLE";
    public const string AgentStart = "AGENT_START";
    public const string AgentStop = "AGENT_STOP";

    public static string ForSignal(SystemSignal signal) => signal switch
    {
        SystemSignal.Lock => Lock,
        SystemSignal.Unlock => Unlock,
        SystemSignal.Login => Login,
        SystemSignal.Logout => Logout,
        SystemSignal.Sleep => Sleep,
        SystemSignal.Resume => Resume,
        SystemSignal.Shutdown => Shutdown,
        _ => throw new ArgumentOutOfRangeException(nameof(signal), signal, null),
    };

    public static string ForActivity(ActivityState state) => state switch
    {
        ActivityState.Active => Active,
        ActivityState.Idle => Idle,
        ActivityState.Locked => Lock,
        _ => Active,
    };
}

/// <summary>
/// Thread-safe, bounded buffer of pending session events awaiting upload. Each event carries a client-generated
/// id so retried batches are de-duplicated server-side. On upload failure the drained events are requeued.
/// </summary>
public sealed class SessionEventQueue
{
    private readonly ConcurrentQueue<SessionEventInput> _queue = new();
    private readonly int _capacity;
    private int _count;

    public SessionEventQueue(int capacity = 5000)
    {
        _capacity = capacity;
    }

    public int Count => Volatile.Read(ref _count);

    public void Enqueue(string type, DateTimeOffset occurredAt)
    {
        // Drop the oldest if we somehow exceed capacity (prolonged backend outage), keeping memory bounded.
        while (Volatile.Read(ref _count) >= _capacity && _queue.TryDequeue(out _))
        {
            Interlocked.Decrement(ref _count);
        }
        _queue.Enqueue(new SessionEventInput(Guid.NewGuid(), type, occurredAt));
        Interlocked.Increment(ref _count);
    }

    /// <summary>Removes up to <paramref name="max"/> events for upload.</summary>
    public List<SessionEventInput> Drain(int max)
    {
        var batch = new List<SessionEventInput>(Math.Min(max, Count));
        while (batch.Count < max && _queue.TryDequeue(out var e))
        {
            Interlocked.Decrement(ref _count);
            batch.Add(e);
        }
        return batch;
    }

    /// <summary>Returns events to the front-ish of the queue after a failed upload (order is best-effort).</summary>
    public void Requeue(IEnumerable<SessionEventInput> events)
    {
        foreach (var e in events)
        {
            _queue.Enqueue(e);
            Interlocked.Increment(ref _count);
        }
    }
}
