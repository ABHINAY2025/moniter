using System;
using System.Runtime.InteropServices.WindowsRuntime;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using Windows.Storage.Streams;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class EmployeeDetailPage : Page
{
    public EmployeeDetailViewModel ViewModel { get; }

    public EmployeeDetailPage()
    {
        ViewModel = App.GetService<EmployeeDetailViewModel>();
        InitializeComponent();
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is long employeeId)
        {
            ViewModel.Initialize(employeeId);
            await ViewModel.LoadAsync();
        }
    }

    private void OnBack(object sender, RoutedEventArgs e)
    {
        if (Frame.CanGoBack)
        {
            Frame.GoBack();
        }
    }

    private void OnLiveView(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: long deviceId })
        {
            Frame.Navigate(typeof(LiveViewPage), deviceId);
        }
    }

    private async void OnScreenshotClick(object sender, ItemClickEventArgs e)
    {
        if (e.ClickedItem is not ScreenshotMetadataDto shot)
        {
            return;
        }
        try
        {
            byte[] bytes = await ViewModel.GetScreenshotBytesAsync(shot.Id);
            var image = new BitmapImage();
            using (var stream = new InMemoryRandomAccessStream())
            {
                await stream.WriteAsync(bytes.AsBuffer());
                stream.Seek(0);
                await image.SetSourceAsync(stream);
            }
            var dialog = new ContentDialog
            {
                Title = $"Screenshot - {shot.CapturedAt:g}",
                CloseButtonText = "Close",
                XamlRoot = XamlRoot,
                Content = new ScrollViewer
                {
                    ZoomMode = ZoomMode.Enabled,
                    Content = new Image { Source = image, Stretch = Microsoft.UI.Xaml.Media.Stretch.Uniform },
                    MaxWidth = 1000,
                    MaxHeight = 700,
                },
            };
            await dialog.ShowAsync();
        }
        catch (Exception ex)
        {
            await new ContentDialog
            {
                Title = "Could not open screenshot",
                Content = ex.Message,
                CloseButtonText = "Close",
                XamlRoot = XamlRoot,
            }.ShowAsync();
        }
    }
}
