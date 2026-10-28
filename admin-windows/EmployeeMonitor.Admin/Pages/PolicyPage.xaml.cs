using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class PolicyPage : Page
{
    public PolicyViewModel ViewModel { get; }

    public PolicyPage()
    {
        ViewModel = App.GetService<PolicyViewModel>();
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        await ViewModel.LoadAsync();
    }
}
