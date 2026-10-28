using System.Net.Http;
using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;
using Windows.System;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class DevicesPage : Page
{
    public DevicesViewModel ViewModel { get; }

    public DevicesPage()
    {
        ViewModel = App.GetService<DevicesViewModel>();
        InitializeComponent();
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

    private void OnSearchKeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key == VirtualKey.Enter)
        {
            ViewModel.SearchCommand.Execute(null);
        }
    }

    private async void OnAddDevice(object sender, RoutedEventArgs e)
    {
        var token = await ViewModel.CreateDeviceTokenAsync();
        if (token is null)
        {
            await ShowMessage("Could not create token", ViewModel.Error ?? "Unknown error.");
            return;
        }

        string server = (App.GetService<HttpClient>().BaseAddress?.ToString() ?? "").TrimEnd('/');
        string expires = token.ExpiresAt.ToLocalTime().ToString("dddd, d MMM yyyy HH:mm");

        var content = new StackPanel { Spacing = 14, Width = 480 };
        content.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Text = $"Enroll a new employee PC. This token works for up to {token.MaxUses} devices and expires {expires}.",
        });

        content.Children.Add(Field("Server", server));
        content.Children.Add(Field("Enrollment token", token.Token, emphasise: true));

        content.Children.Add(new TextBlock
        {
            TextWrapping = TextWrapping.Wrap,
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
            Text = "On the employee PC:\n" +
                   "1.  Copy the EmployeeMonitorAgent folder to the PC.\n" +
                   "2.  Right-click enable-website-monitoring.cmd → Run as administrator (once).\n" +
                   "3.  Open run-agent.cmd, set EM_ENROLLMENT_TOKEN to the token above, then double-click it.\n" +
                   "The PC appears in this list within about 30 seconds.",
        });

        var dialog = new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = "Add a device",
            Content = new ScrollViewer { Content = content, VerticalScrollBarVisibility = ScrollBarVisibility.Auto },
            PrimaryButtonText = "Copy token",
            CloseButtonText = "Done",
            DefaultButton = ContentDialogButton.Primary,
        };
        dialog.PrimaryButtonClick += (_, args) =>
        {
            args.Cancel = true;   // keep the dialog open so they can still read it
            var data = new DataPackage();
            data.SetText(token.Token);
            Clipboard.SetContent(data);
            dialog.PrimaryButtonText = "Copied";
        };
        await dialog.ShowAsync();

        // A freshly added device will show up on the next load.
        await ViewModel.LoadAsync();
    }

    private static StackPanel Field(string label, string value, bool emphasise = false)
    {
        var panel = new StackPanel { Spacing = 4 };
        panel.Children.Add(new TextBlock
        {
            Text = label,
            FontSize = 12,
            FontWeight = FontWeights.SemiBold,
            Foreground = (Brush)Application.Current.Resources["TextFillColorSecondaryBrush"],
        });
        panel.Children.Add(new Border
        {
            Background = (Brush)Application.Current.Resources["LayerFillColorDefaultBrush"],
            BorderBrush = (Brush)Application.Current.Resources["CardStrokeColorDefaultBrush"],
            BorderThickness = new Thickness(1),
            CornerRadius = new CornerRadius(8),
            Padding = new Thickness(12, 8, 12, 8),
            Child = new TextBlock
            {
                Text = value,
                IsTextSelectionEnabled = true,
                FontFamily = new FontFamily("Consolas"),
                FontSize = emphasise ? 15 : 13,
                TextWrapping = TextWrapping.Wrap,
            },
        });
        return panel;
    }

    private Task ShowMessage(string title, string message) =>
        new ContentDialog
        {
            XamlRoot = XamlRoot,
            Title = title,
            Content = new TextBlock { Text = message, TextWrapping = TextWrapping.Wrap },
            CloseButtonText = "OK",
        }.ShowAsync().AsTask();
}
