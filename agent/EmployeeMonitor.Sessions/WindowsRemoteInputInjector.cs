using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Monitoring;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Injects mouse/keyboard input into the user's interactive session via Win32 SendInput, for admin-assisted
/// troubleshooting. It first shows the employee a consent prompt (a system-modal, top-most message box) and only
/// the admin-held control session that the user accepted can drive input. This runs in the user session (the agent
/// is installed as a logon task); from a Session-0 service the prompt and injection would not reach the desktop.
/// Mouse coordinates are normalised 0..1 across the whole virtual desktop.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsRemoteInputInjector : IRemoteInputInjector
{
    private readonly ILogger<WindowsRemoteInputInjector> _log;
    private readonly ControlBanner _banner = new();

    public WindowsRemoteInputInjector(ILogger<WindowsRemoteInputInjector> log) => _log = log;

    public void ShowControlBanner(string adminName)
    {
        try
        {
            _banner.Show(adminName);
        }
        catch (Exception ex)
        {
            _log.LogDebug("Control banner failed to show: {Err}", ex.Message);
        }
    }

    public void HideControlBanner()
    {
        try
        {
            _banner.Hide();
        }
        catch (Exception ex)
        {
            _log.LogDebug("Control banner failed to hide: {Err}", ex.Message);
        }
    }

    public bool RequestConsent(string adminName)
    {
        try
        {
            string who = string.IsNullOrWhiteSpace(adminName) ? "An administrator" : adminName;
            string text = who + " is requesting remote control of this PC to help you.\n\n" +
                          "They will be able to move your mouse and type while you watch. " +
                          "You can stop it any time by signing out or disconnecting.\n\nAllow remote control?";
            // MB_YESNO | MB_ICONQUESTION | MB_SYSTEMMODAL | MB_TOPMOST | MB_SETFOREGROUND
            int result = MessageBox(IntPtr.Zero, text, "Remote control request",
                0x00000004 | 0x00000020 | 0x00001000 | 0x00040000 | 0x00010000);
            return result == 6;   // IDYES
        }
        catch (Exception ex)
        {
            _log.LogWarning(ex, "Remote-control consent prompt failed; denying.");
            return false;
        }
    }

    public void MoveMouse(double nx, double ny)
    {
        var input = MouseAbsolute(nx, ny, MOUSEEVENTF_MOVE);
        Send(input);
    }

    public void MouseButton(string button, bool down, double nx, double ny)
    {
        uint flag = (button, down) switch
        {
            ("left", true) => MOUSEEVENTF_LEFTDOWN,
            ("left", false) => MOUSEEVENTF_LEFTUP,
            ("right", true) => MOUSEEVENTF_RIGHTDOWN,
            ("right", false) => MOUSEEVENTF_RIGHTUP,
            ("middle", true) => MOUSEEVENTF_MIDDLEDOWN,
            ("middle", false) => MOUSEEVENTF_MIDDLEUP,
            _ => 0,
        };
        if (flag == 0)
        {
            return;
        }
        var input = MouseAbsolute(nx, ny, MOUSEEVENTF_MOVE | flag);
        Send(input);
    }

    public void MouseWheel(int delta, double nx, double ny)
    {
        var input = MouseAbsolute(nx, ny, MOUSEEVENTF_MOVE | MOUSEEVENTF_WHEEL);
        input.U.mi.mouseData = (uint)delta;
        Send(input);
    }

    public void KeyEvent(int virtualKey, bool down)
    {
        var input = new INPUT { type = INPUT_KEYBOARD };
        input.U.ki = new KEYBDINPUT
        {
            wVk = (ushort)virtualKey,
            wScan = 0,
            dwFlags = down ? 0u : KEYEVENTF_KEYUP,
            time = 0,
            dwExtraInfo = IntPtr.Zero,
        };
        Send(input);
    }

    private static INPUT MouseAbsolute(double nx, double ny, uint flags)
    {
        nx = Math.Clamp(nx, 0.0, 1.0);
        ny = Math.Clamp(ny, 0.0, 1.0);
        var input = new INPUT { type = INPUT_MOUSE };
        input.U.mi = new MOUSEINPUT
        {
            dx = (int)Math.Round(nx * 65535.0),
            dy = (int)Math.Round(ny * 65535.0),
            mouseData = 0,
            dwFlags = flags | MOUSEEVENTF_ABSOLUTE | MOUSEEVENTF_VIRTUALDESK,
            time = 0,
            dwExtraInfo = IntPtr.Zero,
        };
        return input;
    }

    private void Send(INPUT input)
    {
        var arr = new[] { input };
        if (SendInput(1, arr, Marshal.SizeOf<INPUT>()) == 0)
        {
            _log.LogDebug("SendInput failed: {Err}", Marshal.GetLastWin32Error());
        }
    }

    // ---------- Win32 ----------

    private const int INPUT_MOUSE = 0;
    private const int INPUT_KEYBOARD = 1;
    private const uint MOUSEEVENTF_MOVE = 0x0001;
    private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
    private const uint MOUSEEVENTF_LEFTUP = 0x0004;
    private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
    private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
    private const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020;
    private const uint MOUSEEVENTF_MIDDLEUP = 0x0040;
    private const uint MOUSEEVENTF_WHEEL = 0x0800;
    private const uint MOUSEEVENTF_ABSOLUTE = 0x8000;
    private const uint MOUSEEVENTF_VIRTUALDESK = 0x4000;
    private const uint KEYEVENTF_KEYUP = 0x0002;

    [StructLayout(LayoutKind.Sequential)]
    private struct MOUSEINPUT
    {
        public int dx;
        public int dy;
        public uint mouseData;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct KEYBDINPUT
    {
        public ushort wVk;
        public ushort wScan;
        public uint dwFlags;
        public uint time;
        public IntPtr dwExtraInfo;
    }

    [StructLayout(LayoutKind.Explicit)]
    private struct InputUnion
    {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct INPUT
    {
        public int type;
        public InputUnion U;
    }

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int MessageBox(IntPtr hWnd, string text, string caption, uint type);
}
