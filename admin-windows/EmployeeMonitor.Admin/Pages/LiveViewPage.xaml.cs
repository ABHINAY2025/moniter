using System;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.WindowsRuntime;
using EmployeeMonitor.Admin.Core.ViewModels;
using EmployeeMonitor.Admin.Infrastructure;
using Microsoft.UI.Input;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.Storage.Streams;

namespace EmployeeMonitor.Admin.Pages;

public sealed partial class LiveViewPage : Page
{
    private readonly AudioPlayer _audio = new();
    private readonly LowLevelKeyboardHook _keyHook = new();
    private readonly uint _pid = (uint)Environment.ProcessId;
    private double _bmpW;
    private double _bmpH;
    private long _lastMoveTick;
    private bool _decoding;
    private string? _lastClipboardText;
    private bool _clipboardSubscribed;

    public LiveViewViewModel ViewModel { get; }

    public LiveViewPage()
    {
        ViewModel = App.GetService<LiveViewViewModel>();
        InitializeComponent();
        ViewModel.FrameUpdated += OnFrameUpdated;
        ViewModel.AudioUpdated += OnAudioUpdated;
        ViewModel.ClipboardUpdated += OnRemoteClipboard;
        ViewModel.PropertyChanged += OnViewModelPropertyChanged;
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _keyHook.OnKey = HandleHookKey;
        _keyHook.Start();   // installed on the UI thread, which has the message loop the hook needs
        if (e.Parameter is long deviceId)
        {
            await ViewModel.LoadControlPolicyAsync(deviceId);
            await ViewModel.StartAsync(deviceId);
        }
    }

    protected override async void OnNavigatedFrom(NavigationEventArgs e)
    {
        _keyHook.Stop();
        UnsubscribeClipboard();
        ViewModel.FrameUpdated -= OnFrameUpdated;
        ViewModel.AudioUpdated -= OnAudioUpdated;
        ViewModel.ClipboardUpdated -= OnRemoteClipboard;
        ViewModel.PropertyChanged -= OnViewModelPropertyChanged;
        _audio.Stop();
        await ViewModel.StopAsync();
        base.OnNavigatedFrom(e);
    }

    private void OnViewModelPropertyChanged(object? sender, PropertyChangedEventArgs e)
    {
        if (e.PropertyName == nameof(ViewModel.Muted))
        {
            _audio.IsMuted = ViewModel.Muted;
        }
        else if (e.PropertyName == nameof(ViewModel.Volume))
        {
            _audio.Volume = (float)(ViewModel.Volume / 100.0);
        }
        else if (e.PropertyName == nameof(ViewModel.IsControlling))
        {
            if (ViewModel.IsControlling)
            {
                SubscribeClipboard();
            }
            else
            {
                UnsubscribeClipboard();
            }
        }
    }

    // ---------- Clipboard sync (copy here <-> paste there, only while controlling) ----------

    private void SubscribeClipboard()
    {
        if (!_clipboardSubscribed)
        {
            _lastClipboardText = null;
            Clipboard.ContentChanged += OnClipboardChanged;
            _clipboardSubscribed = true;
        }
    }

    private void UnsubscribeClipboard()
    {
        if (_clipboardSubscribed)
        {
            Clipboard.ContentChanged -= OnClipboardChanged;
            _clipboardSubscribed = false;
        }
    }

    private async void OnClipboardChanged(object? sender, object e)
    {
        if (!ViewModel.IsControlling)
        {
            return;
        }
        try
        {
            var view = Clipboard.GetContent();
            if (!view.Contains(StandardDataFormats.Text))
            {
                return;
            }
            string text = await view.GetTextAsync();
            if (text == _lastClipboardText)
            {
                return;   // this is what we just received/sent - don't echo
            }
            _lastClipboardText = text;
            await ViewModel.SendClipboardAsync(text);
        }
        catch (Exception)
        {
            // clipboard busy / no text - ignore
        }
    }

    private void OnRemoteClipboard(string text)
    {
        try
        {
            _lastClipboardText = text;   // so OnClipboardChanged doesn't echo it back
            var data = new DataPackage();
            data.SetText(text);
            Clipboard.SetContent(data);
        }
        catch (Exception)
        {
            // ignore
        }
    }

    // ---------- Keyboard: global low-level hook forwards every key (incl. Win/Alt combos) while controlling ----------

    private bool HandleHookKey(int virtualKey, bool down)
    {
        if (!ViewModel.IsControlling || !IsAdminAppForeground())
        {
            return false;   // let the key work normally on this PC
        }
        _ = ViewModel.SendKeyAsync(virtualKey, down);
        return true;   // swallow it locally - it went to the remote
    }

    private bool IsAdminAppForeground()
    {
        GetWindowThreadProcessId(GetForegroundWindow(), out uint pid);
        return pid == _pid;
    }

    // ---------- Mouse ----------

    private bool TryNormalize(PointerRoutedEventArgs e, out double nx, out double ny)
    {
        nx = ny = 0;
        double cw = ScreenImage.ActualWidth;
        double ch = ScreenImage.ActualHeight;
        if (cw <= 0 || ch <= 0 || _bmpW <= 0 || _bmpH <= 0)
        {
            return false;
        }
        double scale = Math.Min(cw / _bmpW, ch / _bmpH);
        double contentW = _bmpW * scale;
        double contentH = _bmpH * scale;
        double offX = (cw - contentW) / 2;
        double offY = (ch - contentH) / 2;
        Point p = e.GetCurrentPoint(ScreenImage).Position;
        nx = Math.Clamp((p.X - offX) / contentW, 0, 1);
        ny = Math.Clamp((p.Y - offY) / contentH, 0, 1);
        return true;
    }

    private async void OnPointerMoved(object sender, PointerRoutedEventArgs e)
    {
        if (!ViewModel.IsControlling)
        {
            return;
        }
        long now = Environment.TickCount64;
        if (now - _lastMoveTick < 30)
        {
            return;
        }
        _lastMoveTick = now;
        if (TryNormalize(e, out double nx, out double ny))
        {
            await ViewModel.SendMouseMoveAsync(nx, ny);
        }
    }

    private async void OnPointerPressed(object sender, PointerRoutedEventArgs e)
    {
        if (!ViewModel.IsControlling || !TryNormalize(e, out double nx, out double ny))
        {
            return;
        }
        ScreenImage.CapturePointer(e.Pointer);
        string? button = e.GetCurrentPoint(ScreenImage).Properties.PointerUpdateKind switch
        {
            PointerUpdateKind.LeftButtonPressed => "left",
            PointerUpdateKind.RightButtonPressed => "right",
            PointerUpdateKind.MiddleButtonPressed => "middle",
            _ => null,
        };
        if (button is not null)
        {
            await ViewModel.SendMouseButtonAsync(button, true, nx, ny);
        }
    }

    private async void OnPointerReleased(object sender, PointerRoutedEventArgs e)
    {
        if (!ViewModel.IsControlling || !TryNormalize(e, out double nx, out double ny))
        {
            return;
        }
        string? button = e.GetCurrentPoint(ScreenImage).Properties.PointerUpdateKind switch
        {
            PointerUpdateKind.LeftButtonReleased => "left",
            PointerUpdateKind.RightButtonReleased => "right",
            PointerUpdateKind.MiddleButtonReleased => "middle",
            _ => null,
        };
        ScreenImage.ReleasePointerCapture(e.Pointer);
        if (button is not null)
        {
            await ViewModel.SendMouseButtonAsync(button, false, nx, ny);
        }
    }

    private async void OnPointerWheel(object sender, PointerRoutedEventArgs e)
    {
        if (!ViewModel.IsControlling || !TryNormalize(e, out double nx, out double ny))
        {
            return;
        }
        int delta = e.GetCurrentPoint(ScreenImage).Properties.MouseWheelDelta;
        await ViewModel.SendWheelAsync(delta, nx, ny);
    }

    // ---------- Media ----------

    private void OnAudioUpdated(byte[] pcm, int sampleRate, int channels)
    {
        _audio.IsMuted = ViewModel.Muted;
        _audio.Volume = (float)(ViewModel.Volume / 100.0);
        _audio.Enqueue(pcm, sampleRate, channels);
    }

    private async void OnFrameUpdated(byte[] bytes)
    {
        if (_decoding)
        {
            return;
        }
        _decoding = true;
        try
        {
            var image = new BitmapImage();
            using (var stream = new InMemoryRandomAccessStream())
            {
                await stream.WriteAsync(bytes.AsBuffer());
                stream.Seek(0);
                await image.SetSourceAsync(stream);
            }
            _bmpW = image.PixelWidth;
            _bmpH = image.PixelHeight;
            ScreenImage.Source = image;
        }
        catch (Exception)
        {
            // drop a corrupt frame
        }
        finally
        {
            _decoding = false;
        }
    }

    private void OnBack(object sender, RoutedEventArgs e)
    {
        if (Frame.CanGoBack)
        {
            Frame.GoBack();
        }
    }

    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll")]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
}
