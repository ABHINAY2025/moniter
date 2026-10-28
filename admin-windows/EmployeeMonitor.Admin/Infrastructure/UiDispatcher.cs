using EmployeeMonitor.Admin.Core;
using Microsoft.UI.Dispatching;

namespace EmployeeMonitor.Admin.Infrastructure;

/// <summary>Marshals realtime callbacks onto the WinUI UI thread.</summary>
public sealed class UiDispatcher : IUiDispatcher
{
    private readonly DispatcherQueue _queue;

    public UiDispatcher(DispatcherQueue queue)
    {
        _queue = queue;
    }

    public void Post(Action action)
    {
        if (_queue.HasThreadAccess)
        {
            action();
        }
        else
        {
            _queue.TryEnqueue(() => action());
        }
    }
}
