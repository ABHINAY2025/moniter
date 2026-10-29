using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Monitoring;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Detects when the employee takes a screenshot and raises an event so the agent can store exactly one capture in
/// response. Two non-invasive signals are used, neither of which reads keystrokes or clipboard/image contents:
///   - the clipboard gaining an image (PrtSc, Alt+PrtSc, Win+Shift+S snips) — observed via the clipboard's change
///     sequence number and image-format availability, never by opening or reading the clipboard data;
///   - a new file appearing in the user's Pictures\Screenshots folder (Win+PrtSc auto-save).
/// Near-simultaneous signals from a single screenshot are coalesced by the caller.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsScreenshotEventSource : IScreenshotEventSource, IDisposable
{
    private readonly ILogger<WindowsScreenshotEventSource> _log;
    private readonly object _gate = new();

    private Action? _onScreenshot;
    private System.Threading.Timer? _clipboardTimer;
    private FileSystemWatcher? _watcher;
    private uint _lastClipboardSeq;
    private long _lastFireTicks;
    private bool _running;

    public WindowsScreenshotEventSource(ILogger<WindowsScreenshotEventSource> log) => _log = log;

    public void Start(Action onScreenshot)
    {
        lock (_gate)
        {
            if (_running)
            {
                return;
            }
            _running = true;
            _onScreenshot = onScreenshot;
            _lastClipboardSeq = GetClipboardSequenceNumber();   // baseline so existing clipboard content doesn't fire
            _clipboardTimer = new System.Threading.Timer(_ => PollClipboard(), null, TimeSpan.FromMilliseconds(600),
                TimeSpan.FromMilliseconds(600));
            StartFolderWatcher();
            _log.LogInformation("Screenshot detection started (clipboard images + Screenshots folder).");
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            _running = false;
            _clipboardTimer?.Dispose();
            _clipboardTimer = null;
            _watcher?.Dispose();
            _watcher = null;
            _onScreenshot = null;
        }
    }

    private void PollClipboard()
    {
        try
        {
            uint seq = GetClipboardSequenceNumber();
            if (seq == _lastClipboardSeq)
            {
                return;
            }
            _lastClipboardSeq = seq;
            // Only an image landing on the clipboard counts; text copies change the sequence too but are ignored.
            if (IsClipboardFormatAvailable(CF_BITMAP) || IsClipboardFormatAvailable(CF_DIB)
                || IsClipboardFormatAvailable(CF_DIBV5))
            {
                Fire("clipboard-image");
            }
        }
        catch (Exception ex)
        {
            _log.LogDebug("Clipboard screenshot poll failed: {Err}", ex.Message);
        }
    }

    private void StartFolderWatcher()
    {
        try
        {
            string pictures = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures);
            if (string.IsNullOrEmpty(pictures))
            {
                return;
            }
            string dir = Path.Combine(pictures, "Screenshots");
            Directory.CreateDirectory(dir);   // Windows creates this on first Win+PrtSc; ensure the watcher can attach now
            _watcher = new FileSystemWatcher(dir, "*.png")
            {
                NotifyFilter = NotifyFilters.FileName,
                IncludeSubdirectories = false,
                EnableRaisingEvents = true,
            };
            _watcher.Created += (_, _) => Fire("screenshots-folder");
        }
        catch (Exception ex)
        {
            _log.LogDebug("Could not watch the Screenshots folder: {Err}", ex.Message);
        }
    }

    private void Fire(string reason)
    {
        // Coalesce the signals a single screenshot can raise (e.g. clipboard + saved file) within a short window.
        long now = Environment.TickCount64;
        long prev = Interlocked.Read(ref _lastFireTicks);
        if (now - prev < 1000)
        {
            return;
        }
        Interlocked.Exchange(ref _lastFireTicks, now);
        _log.LogDebug("Screenshot detected ({Reason}).", reason);
        try
        {
            _onScreenshot?.Invoke();
        }
        catch (Exception ex)
        {
            _log.LogDebug("Screenshot handler threw: {Err}", ex.Message);
        }
    }

    public void Dispose() => Stop();

    private const uint CF_BITMAP = 2;
    private const uint CF_DIB = 8;
    private const uint CF_DIBV5 = 17;

    [DllImport("user32.dll")]
    private static extern uint GetClipboardSequenceNumber();

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsClipboardFormatAvailable(uint format);
}
