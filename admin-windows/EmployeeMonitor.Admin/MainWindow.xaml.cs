using EmployeeMonitor.Admin.Core.ViewModels;
using EmployeeMonitor.Admin.Pages;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace EmployeeMonitor.Admin;

public sealed partial class MainWindow : Window
{
    public ShellViewModel ShellViewModel { get; }
    public AlertsViewModel AlertsViewModel { get; }
    public WebAccessViewModel WebAccessViewModel { get; }

    public MainWindow()
    {
        ShellViewModel = App.GetService<ShellViewModel>();
        AlertsViewModel = App.GetService<AlertsViewModel>();
        WebAccessViewModel = App.GetService<WebAccessViewModel>();
        InitializeComponent();
        Title = "Employee Monitor Admin";
        ShellViewModel.LoggedOut += OnLoggedOut;
        AlertsViewModel.PropertyChanged += OnAlertsPropertyChanged;
    }

    private void OnAlertsPropertyChanged(object? sender, System.ComponentModel.PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(AlertsViewModel.HasAlerts))
        {
            AlertBadge.Visibility = AlertsViewModel.HasAlerts ? Visibility.Visible : Visibility.Collapsed;
        }
    }

    private async void OnLoaded(object sender, RoutedEventArgs e)
    {
        ContentFrame.Navigate(typeof(DashboardPage));
        await ShellViewModel.OnSignedInAsync();
        // Realtime is connected now; start the live alerts feed and load the current unacknowledged ones.
        AlertsViewModel.Start();
        await AlertsViewModel.LoadAsync();
        // Keep the web-access live feed running so blocked-access events accumulate even before the page opens.
        WebAccessViewModel.Start();
    }

    private void OnNavSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (args.SelectedItem is not NavigationViewItem item)
        {
            return;
        }
        Type page = (item.Tag as string) switch
        {
            "employees" => typeof(EmployeesPage),
            "devices" => typeof(DevicesPage),
            "webaccess" => typeof(WebAccessPage),
            "alerts" => typeof(AlertsPage),
            "policy" => typeof(PolicyPage),
            _ => typeof(DashboardPage),
        };
        if (ContentFrame.CurrentSourcePageType != page)
        {
            ContentFrame.Navigate(page);
        }
    }

    private void OnLoggedOut()
    {
        ShellViewModel.LoggedOut -= OnLoggedOut;
        AlertsViewModel.Stop();
        WebAccessViewModel.Stop();
        ((App)Application.Current).SwitchWindow(new LoginWindow());
    }
}
