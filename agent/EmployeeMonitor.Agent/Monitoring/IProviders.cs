namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>Foreground application: friendly name + process name. Window titles/contents are never read.</summary>
public sealed record ForegroundApp(string Application, string? Process);

/// <summary>Current foreground app, or null when there is none (locked/idle/no window).</summary>
public interface IForegroundAppProvider
{
    ForegroundApp? GetForeground();
}

/// <summary>Current endpoint network state (type, name, IPv4). Status only - no traffic inspection.</summary>
public interface INetworkProvider
{
    NetworkSnapshot GetSnapshot();
}

/// <summary>
/// Zscaler client status from supported local signals (service/process/tunnel presence). Returns one of
/// CONNECTED, DISCONNECTED, ERROR, UNKNOWN. Never intercepts or decrypts Zscaler traffic.
/// </summary>
public interface IZscalerProvider
{
    string GetStatus();
}

/// <summary>Removable (USB mass-storage) drives currently mounted. Device metadata only; files are never read.</summary>
public interface IRemovableDriveProvider
{
    IReadOnlyList<RemovableDrive> GetRemovableDrives();
}

/// <summary>
/// Supplies the host names the machine has recently resolved (read from the OS DNS client cache). Domain-level
/// only - never full URLs, page contents or packet data.
/// </summary>
public interface IDnsVisitProvider
{
    IReadOnlyList<string> GetResolvedHosts();
}

/// <summary>
/// Captures the device's SYSTEM audio output (WASAPI loopback - what is already playing on the speakers), never
/// the microphone. Delivers 16-bit little-endian PCM chunks with their sample rate and channel count. Used only
/// while an authorized live-view session is active, so the admin can hear what the employee hears.
/// </summary>
public interface ISystemAudioCapture : IDisposable
{
    bool IsSupported { get; }

    /// <summary>Starts loopback capture. <paramref name="onChunk"/> receives (pcm16, sampleRate, channels).</summary>
    void Start(Action<byte[], int, int> onChunk);

    void Stop();
}

/// <summary>
/// Injects remote mouse/keyboard input into the interactive session for admin-assisted troubleshooting, after the
/// employee explicitly consents. Mouse coordinates are normalised (0..1) over the virtual desktop. Nothing is ever
/// injected silently: <see cref="RequestConsent"/> shows a prompt the employee must accept, and the admin must hold
/// control for the duration.
/// </summary>
public interface IRemoteInputInjector
{
    /// <summary>Shows a prompt on the employee's desktop and blocks until they allow (true) or deny (false).</summary>
    bool RequestConsent(string adminName);

    void MoveMouse(double nx, double ny);
    void MouseButton(string button, bool down, double nx, double ny);
    void MouseWheel(int delta, double nx, double ny);
    void KeyEvent(int virtualKey, bool down);
}

/// <summary>
/// Reads/writes the interactive session's text clipboard, for clipboard sync during an authorized remote-control
/// session (copy on one machine, paste on the other). Text only; used only while control is granted.
/// </summary>
public interface ISystemClipboard
{
    string? GetText();
    void SetText(string text);
}

/// <summary>A captured screen image, already encoded (PNG or JPEG).</summary>
public sealed record ScreenCapture(byte[] Bytes, string ContentType, int Width, int Height);

/// <summary>Captures the current screen when policy permits. Returns null if capture is unavailable.</summary>
public interface IScreenCaptureProvider
{
    /// <summary>Full-resolution capture (JPEG, default quality) — used for stored screenshots.</summary>
    ScreenCapture? Capture();

    /// <summary>
    /// Capture downscaled for live streaming: the long edge is capped at <paramref name="maxLongEdge"/>
    /// (0 = no scaling) and encoded at JPEG <paramref name="quality"/> (1–100). Smaller + lighter so the
    /// frame rate can keep up over a constrained uplink.
    /// </summary>
    ScreenCapture? Capture(int maxLongEdge, int quality);
}

/// <summary>Pure policy gate for whether a screenshot may be captured right now.</summary>
public static class ScreenshotGate
{
    public static bool ShouldCapture(Api.PolicySettings policy, bool withinWindow, ActivityState status) =>
        policy.Screenshots && withinWindow && status != ActivityState.Locked;
}
