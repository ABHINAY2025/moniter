using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Monitoring;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Text clipboard access via the Win32 user32 clipboard API (CF_UNICODETEXT). Used only during an authorized
/// remote-control session to mirror text between the admin and this machine. Opens are retried briefly because the
/// clipboard can be momentarily held by another process.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsClipboard : ISystemClipboard
{
    private const uint CF_UNICODETEXT = 13;
    private const uint GMEM_MOVEABLE = 0x0002;

    public string? GetText()
    {
        if (!TryOpen())
        {
            return null;
        }
        try
        {
            IntPtr handle = GetClipboardData(CF_UNICODETEXT);
            if (handle == IntPtr.Zero)
            {
                return null;
            }
            IntPtr ptr = GlobalLock(handle);
            if (ptr == IntPtr.Zero)
            {
                return null;
            }
            try
            {
                return Marshal.PtrToStringUni(ptr);
            }
            finally
            {
                GlobalUnlock(handle);
            }
        }
        finally
        {
            CloseClipboard();
        }
    }

    public void SetText(string text)
    {
        if (text is null || !TryOpen())
        {
            return;
        }
        try
        {
            EmptyClipboard();
            char[] chars = (text + "\0").ToCharArray();
            IntPtr hMem = GlobalAlloc(GMEM_MOVEABLE, (UIntPtr)(chars.Length * 2));
            if (hMem == IntPtr.Zero)
            {
                return;
            }
            IntPtr ptr = GlobalLock(hMem);
            if (ptr == IntPtr.Zero)
            {
                GlobalFree(hMem);
                return;
            }
            try
            {
                Marshal.Copy(chars, 0, ptr, chars.Length);
            }
            finally
            {
                GlobalUnlock(hMem);
            }
            if (SetClipboardData(CF_UNICODETEXT, hMem) == IntPtr.Zero)
            {
                GlobalFree(hMem);   // ownership not taken by the clipboard
            }
        }
        finally
        {
            CloseClipboard();
        }
    }

    private static bool TryOpen()
    {
        for (int i = 0; i < 8; i++)
        {
            if (OpenClipboard(IntPtr.Zero))
            {
                return true;
            }
            Thread.Sleep(25);
        }
        return false;
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool OpenClipboard(IntPtr hWndNewOwner);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CloseClipboard();

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EmptyClipboard();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr GetClipboardData(uint uFormat);

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetClipboardData(uint uFormat, IntPtr hMem);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GlobalAlloc(uint uFlags, UIntPtr dwBytes);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GlobalFree(IntPtr hMem);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GlobalLock(IntPtr hMem);

    [DllImport("kernel32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GlobalUnlock(IntPtr hMem);
}
