using EmployeeMonitor.Admin.Infrastructure;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;

namespace EmployeeMonitor.Admin;

public partial class App : Application
{
    private Window? _window;

    public static IServiceProvider Services { get; private set; } = null!;

    public static T GetService<T>() where T : notnull => Services.GetRequiredService<T>();

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        var dispatcher = new UiDispatcher(DispatcherQueue.GetForCurrentThread());
        Services = ServiceConfig.Build(dispatcher);

        _window = new LoginWindow();
        _window.Activate();
    }

    /// <summary>Swaps the active top-level window (login &lt;-&gt; main) and closes the previous one.</summary>
    public void SwitchWindow(Window next)
    {
        var previous = _window;
        _window = next;
        next.Activate();
        previous?.Close();
    }
}
