using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Platform;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Idle detection via the supported GetLastInputInfo API. It returns only the <em>time</em> of the last
/// keyboard/mouse input for the current session; the content of input is never accessible and never read.
/// Correct in an interactive user session (see docs for the Session-0 service note).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class Win32InputMonitor : IInputMonitor
{
    [StructLayout(LayoutKind.Sequential)]
    private struct LASTINPUTINFO
    {
        public uint cbSize;
        public uint dwTime;
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetLastInputInfo(ref LASTINPUTINFO plii);

    public TimeSpan GetIdleTime()
    {
        var info = new LASTINPUTINFO { cbSize = (uint)Marshal.SizeOf<LASTINPUTINFO>() };
        if (!GetLastInputInfo(ref info))
        {
            return TimeSpan.Zero;
        }
        // Both are 32-bit tick counts (ms) that wrap ~every 49.7 days; the unsigned subtraction wraps correctly.
        uint elapsed = unchecked((uint)Environment.TickCount - info.dwTime);
        return TimeSpan.FromMilliseconds(elapsed);
    }
}
