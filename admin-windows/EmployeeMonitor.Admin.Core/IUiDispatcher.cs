namespace EmployeeMonitor.Admin.Core;

/// <summary>Marshals an action onto the UI thread. Keeps view models free of any UI framework dependency.</summary>
public interface IUiDispatcher
{
    void Post(Action action);
}

/// <summary>Runs actions inline; used in tests and non-UI contexts.</summary>
public sealed class ImmediateDispatcher : IUiDispatcher
{
    public void Post(Action action) => action();
}
