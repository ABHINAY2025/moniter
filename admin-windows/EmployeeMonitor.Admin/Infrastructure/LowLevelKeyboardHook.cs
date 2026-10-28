using System;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace EmployeeMonitor.Admin.Infrastructure;

/// <summary>
/// A global low-level keyboard hook. While active, it offers every key press/release to <see cref="OnKey"/>; if that
/// returns true the key is swallowed locally (because it was forwarded to the remote machine). This is what lets the
/// admin send system combos like Win+R or Alt+Tab to the controlled PC instead of triggering them on their own.
/// Ctrl+Alt+Del is a secure attention sequence Windows never delivers to hooks, so it cannot be forwarded.
/// </summary>
public sealed class LowLevelKeyboardHook : IDisposable
{
    private const int WH_KEYBOARD_LL = 13;
    private const int WM_KEYDOWN = 0x0100;
    private const int WM_KEYUP = 0x0101;
    private const int WM_SYSKEYDOWN = 0x0104;
    private const int WM_SYSKEYUP = 0x0105;

    private delegate IntPtr LowLevelProc(int nCode, IntPtr wParam, IntPtr lParam);

    private readonly LowLevelProc _proc;   // kept alive so the GC can't collect the callback
    private IntPtr _hook;

    /// <summary>(virtualKey, isDown) =&gt; true to swallow the key locally (it was handled/forwarded).</summary>
    public Func<int, bool, bool>? OnKey { get; set; }

    public LowLevelKeyboardHook() => _proc = Callback;

    public void Start()
    {
        if (_hook != IntPtr.Zero)
        {
            return;
        }
        _hook = SetWindowsHookEx(WH_KEYBOARD_LL, _proc, GetModuleHandle(null), 0);
    }

    public void Stop()
    {
        if (_hook != IntPtr.Zero)
        {
            UnhookWindowsHookEx(_hook);
            _hook = IntPtr.Zero;
        }
    }

    private IntPtr Callback(int nCode, IntPtr wParam, IntPtr lParam)
    {
        if (nCode >= 0)
        {
            int msg = (int)wParam;
            bool down = msg == WM_KEYDOWN || msg == WM_SYSKEYDOWN;
            bool up = msg == WM_KEYUP || msg == WM_SYSKEYUP;
            if (down || up)
            {
                int vk = Marshal.ReadInt32(lParam);   // KBDLLHOOKSTRUCT.vkCode is the first field
                try
                {
                    if (OnKey?.Invoke(vk, down) == true)
                    {
                        return (IntPtr)1;   // swallow locally
                    }
                }
                catch
                {
                    // never let a handler error break the keyboard
                }
            }
        }
        return CallNextHookEx(_hook, nCode, wParam, lParam);
    }

    public void Dispose() => Stop();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern IntPtr SetWindowsHookEx(int idHook, LowLevelProc lpfn, IntPtr hMod, uint dwThreadId);

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool UnhookWindowsHookEx(IntPtr hhk);

    [DllImport("user32.dll")]
    private static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern IntPtr GetModuleHandle(string? lpModuleName);
}
