using System.Collections.Concurrent;

namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>Thread-safe, bounded FIFO of pending upload payloads. Oldest are dropped past capacity.</summary>
public sealed class UploadQueue<T>
{
    private readonly ConcurrentQueue<T> _queue = new();
    private readonly int _capacity;
    private int _count;

    public UploadQueue(int capacity = 5000)
    {
        _capacity = capacity;
    }

    public int Count => Volatile.Read(ref _count);

    public void Enqueue(T item)
    {
        while (Volatile.Read(ref _count) >= _capacity && _queue.TryDequeue(out _))
        {
            Interlocked.Decrement(ref _count);
        }
        _queue.Enqueue(item);
        Interlocked.Increment(ref _count);
    }

    public List<T> Drain(int max)
    {
        var batch = new List<T>(Math.Min(max, Count));
        while (batch.Count < max && _queue.TryDequeue(out var item))
        {
            Interlocked.Decrement(ref _count);
            batch.Add(item);
        }
        return batch;
    }

    public void Requeue(IEnumerable<T> items)
    {
        foreach (var item in items)
        {
            _queue.Enqueue(item);
            Interlocked.Increment(ref _count);
        }
    }
}
