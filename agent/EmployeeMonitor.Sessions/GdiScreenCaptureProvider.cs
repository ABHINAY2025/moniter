using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Monitoring;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Captures the whole desktop (all monitors) with the supported GDI path (Graphics.CopyFromScreen) and encodes it
/// as JPEG. This is a visible, policy-governed capture of the screen only - no keystrokes, no window contents
/// beyond what is on screen, no webcam. In a Session-0 service it cannot see the interactive desktop (see docs).
/// The process is made per-monitor DPI aware so the full physical resolution is captured on scaled displays.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class GdiScreenCaptureProvider : IScreenCaptureProvider
{
    // Virtual screen metrics span every monitor (origin can be negative).
    private const int SM_XVIRTUALSCREEN = 76;
    private const int SM_YVIRTUALSCREEN = 77;
    private const int SM_CXVIRTUALSCREEN = 78;
    private const int SM_CYVIRTUALSCREEN = 79;
    private const long JpegQuality = 70L;

    private static readonly IntPtr DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = (IntPtr)(-4);

    [DllImport("user32.dll")]
    private static extern int GetSystemMetrics(int index);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetProcessDpiAwarenessContext(IntPtr value);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetProcessDPIAware();

    static GdiScreenCaptureProvider()
    {
        // Must run before any device context is created so GetSystemMetrics returns physical pixels.
        EnsureDpiAware();
    }

    /// <summary>Marks the process per-monitor DPI aware (call as early as possible in process startup).</summary>
    public static void EnsureDpiAware()
    {
        try
        {
            if (!SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2))
            {
                SetProcessDPIAware();
            }
        }
        catch (Exception)
        {
            // Older Windows without the newer API; SetProcessDPIAware is the fallback.
            try { SetProcessDPIAware(); } catch (Exception) { /* best effort */ }
        }
    }

    public ScreenCapture? Capture()
    {
        int x = GetSystemMetrics(SM_XVIRTUALSCREEN);
        int y = GetSystemMetrics(SM_YVIRTUALSCREEN);
        int width = GetSystemMetrics(SM_CXVIRTUALSCREEN);
        int height = GetSystemMetrics(SM_CYVIRTUALSCREEN);
        if (width <= 0 || height <= 0)
        {
            return null;
        }
        try
        {
            using var bitmap = new Bitmap(width, height, PixelFormat.Format24bppRgb);
            using (var graphics = Graphics.FromImage(bitmap))
            {
                // Source origin is the virtual-screen top-left (may be negative on multi-monitor setups).
                graphics.CopyFromScreen(x, y, 0, 0, new Size(width, height), CopyPixelOperation.SourceCopy);
            }
            using var stream = new MemoryStream();
            var encoder = ImageCodecInfo.GetImageEncoders().First(c => c.FormatID == ImageFormat.Jpeg.Guid);
            using var parameters = new EncoderParameters(1);
            parameters.Param[0] = new EncoderParameter(Encoder.Quality, JpegQuality);
            bitmap.Save(stream, encoder, parameters);
            return new ScreenCapture(stream.ToArray(), "image/jpeg", width, height);
        }
        catch (Exception)
        {
            return null;
        }
    }
}
