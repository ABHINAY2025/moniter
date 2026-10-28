using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Xaml;
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

    private async void OnAddEmployee(object sender, RoutedEventArgs e)
    {
        var code = new TextBox { Header = "Employee code", PlaceholderText = "e.g. SUJAY" };
        var name = new TextBox { Header = "Full name", PlaceholderText = "e.g. Sujay Kumar" };
        var email = new TextBox { Header = "Work email", PlaceholderText = "e.g. sujay@quantumdataleap.ai" };
        var jobTitle = new TextBox { Header = "Job title (optional)" };
        var errorBar = new InfoBar
        {
            Severity = InfoBarSeverity.Error,
            IsOpen = false,
            IsClosable = false,
        };

        var content = new StackPanel { Spacing = 12, Width = 420 };
        content.Children.Add(code);
        content.Children.Add(name);
        content.Children.Add(email);
        content.Children.Add(jobTitle);
        content.Children.Add(errorBar);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Add an employee",
            Content = content,
            PrimaryButtonText = "Create",
            CloseButtonText = "Cancel",
            DefaultButton = ContentDialogButton.Primary,
        };

        dialog.PrimaryButtonClick += async (_, args) =>
        {
            var deferral = args.GetDeferral();
            try
            {
                string c = code.Text?.Trim() ?? "";
                string n = name.Text?.Trim() ?? "";
                string m = email.Text?.Trim() ?? "";

                if (c.Length == 0 || n.Length == 0 || m.Length == 0)
                {
                    errorBar.Message = "Employee code, full name and email are all required.";
                    errorBar.IsOpen = true;
                    args.Cancel = true;
                    return;
                }
                if (!m.Contains('@') || !m.Contains('.'))
                {
                    errorBar.Message = "Enter a valid email address.";
                    errorBar.IsOpen = true;
                    args.Cancel = true;
                    return;
                }

                var input = new CreateEmployeeInput(c, n, m, null,
                    string.IsNullOrWhiteSpace(jobTitle.Text) ? null : jobTitle.Text.Trim());
                var created = await ViewModel.CreateEmployeeAsync(input);
                if (created is null)
                {
                    errorBar.Message = ViewModel.Error ?? "Could not create the employee.";
                    errorBar.IsOpen = true;
                    args.Cancel = true;   // keep the dialog open so they can fix and retry
                }
            }
            finally
            {
                deferral.Complete();
            }
        };

        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Primary)
        {
            await ViewModel.LoadAsync();   // show the new employee in the list
        }
    }
}
