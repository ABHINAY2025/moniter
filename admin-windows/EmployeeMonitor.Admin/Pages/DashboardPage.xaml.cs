using System.Collections.Generic;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class DashboardPage : Page
{
    public DashboardViewModel ViewModel { get; }

    public DashboardPage()
    {
        ViewModel = App.GetService<DashboardViewModel>();
        InitializeComponent();
    }

    private async void OnDeviceClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not DeviceRowViewModel row)
        {
            return;
        }
        // Assigned device -> open the employee's detail page (monitoring data + Live view).
        if (row.EmployeeId is { } employeeId)
        {
            Frame.Navigate(typeof(EmployeeDetailPage), employeeId);
            return;
        }
        // Unassigned device -> offer options: assign it to an employee, or watch it live now.
        await ShowDeviceOptionsAsync(row);
    }

    private async Task ShowDeviceOptionsAsync(DeviceRowViewModel row)
    {
        var api = App.GetService<IAdminApi>();

        var brushSecondary = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"];

        List<EmployeeDto> employees = new();
        try
        {
            var page = await api.GetEmployeesAsync(null, 0, 200);
            employees = new List<EmployeeDto>(page.Content);
        }
        catch
        {
            // leave the picker empty; assignment just won't be available
        }

        var combo = new ComboBox
        {
            PlaceholderText = "Select an employee",
            ItemsSource = employees,
            DisplayMemberPath = "FullName",
            HorizontalAlignment = HorizontalAlignment.Stretch,
        };

        var errorText = new TextBlock
        {
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.OrangeRed),
            TextWrapping = TextWrapping.Wrap,
            Visibility = Visibility.Collapsed,
        };

        var panel = new StackPanel { Spacing = 12, Width = 420 };
        panel.Children.Add(new TextBlock
        {
            Text = $"{row.Hostname}  ·  {row.DeviceCode}  ·  {row.Status}",
            FontWeight = FontWeights.SemiBold,
        });
        panel.Children.Add(new TextBlock
        {
            Text = "This device isn't linked to an employee yet. Assign it to see full monitoring, or open Live view now.",
            TextWrapping = TextWrapping.Wrap,
            Foreground = brushSecondary,
        });
        panel.Children.Add(new TextBlock
        {
            Text = "Assign to employee",
            FontSize = 12,
            FontWeight = FontWeights.SemiBold,
            Foreground = brushSecondary,
        });
        panel.Children.Add(combo);
        panel.Children.Add(errorText);

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Device options",
            Content = panel,
            PrimaryButtonText = "Assign",
            SecondaryButtonText = "Live view",
            CloseButtonText = "Close",
            DefaultButton = ContentDialogButton.Primary,
        };

        dialog.PrimaryButtonClick += async (_, args) =>
        {
            if (combo.SelectedItem is not EmployeeDto emp)
            {
                args.Cancel = true;
                errorText.Text = "Pick an employee first.";
                errorText.Visibility = Visibility.Visible;
                return;
            }
            var deferral = args.GetDeferral();
            try
            {
                await api.AssignDeviceAsync(row.DeviceId, emp.Id);
            }
            catch (System.Exception ex)
            {
                args.Cancel = true;
                errorText.Text = ex is ApiException ? ex.Message : "Could not assign: " + ex.Message;
                errorText.Visibility = Visibility.Visible;
            }
            finally
            {
                deferral.Complete();
            }
        };

        var result = await dialog.ShowAsync();
        if (result == ContentDialogResult.Secondary)
        {
            Frame.Navigate(typeof(LiveViewPage), row.DeviceId);
        }
        else if (result == ContentDialogResult.Primary)
        {
            await ViewModel.LoadAsync();   // refresh so the row now shows its employee
        }
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        ViewModel.Attach();
        await ViewModel.LoadAsync();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        ViewModel.Dispose();
        base.OnNavigatedFrom(e);
    }
}
