using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Windows.System;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class EmployeesPage : Page
{
    public EmployeesViewModel ViewModel { get; }

    public EmployeesPage()
    {
        ViewModel = App.GetService<EmployeesViewModel>();
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        await ViewModel.LoadAsync();
    }

    private void OnSearchKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter)
        {
            ViewModel.SearchCommand.Execute(null);
        }
    }

    private void OnEmployeeClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is EmployeeDto employee)
        {
            Frame.Navigate(typeof(EmployeeDetailPage), employee.Id);
        }
    }
}
