using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class AlertsPage : Page
{
    public AlertsViewModel ViewModel { get; }

    public AlertsPage()
    {
        ViewModel = App.GetService<AlertsViewModel>();
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        await ViewModel.LoadAsync();
    }

    private void OnAcknowledge(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: AlertRowViewModel row })
        {
            ViewModel.AcknowledgeCommand.Execute(row);
        }
    }
}
