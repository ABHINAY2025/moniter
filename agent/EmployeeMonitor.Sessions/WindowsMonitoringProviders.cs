using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Monitoring;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Foreground application via supported Win32 APIs (GetForegroundWindow + the owning process). Reports the
/// process name and friendly product/description only - never the window title or any window content.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class Win32ForegroundAppProvider : IForegroundAppProvider
{
    [DllImport("user32.dll")]
    private static extern IntPtr GetForegroundWindow();

    [DllImport("user32.dll", SetLastError = true)]
    private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

    public ForegroundApp? GetForeground()
    {
        IntPtr hwnd = GetForegroundWindow();
        if (hwnd == IntPtr.Zero)
        {
            return null;
        }
        if (GetWindowThreadProcessId(hwnd, out uint pid) == 0 || pid == 0)
        {
            return null;
        }
        try
        {
            using var process = Process.GetProcessById((int)pid);
            string processName = process.ProcessName;
            string friendly = processName;
            try
            {
                var description = process.MainModule?.FileVersionInfo.FileDescription;
                if (!string.IsNullOrWhiteSpace(description))
                {
                    friendly = description!;
                }
            }
            catch (Exception)
            {
                // MainModule can throw for protected/elevated processes; the process name is enough.
            }
            return new ForegroundApp(friendly, processName);
        }
        catch (Exception)
        {
            return null;
        }
    }
}

/// <summary>Endpoint network state from NetworkInterface. Status/visibility only; no traffic is inspected.</summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsNetworkProvider : INetworkProvider
{
    public NetworkSnapshot GetSnapshot()
    {
        var nic = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                        && n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
            .OrderByDescending(n => n.Speed)
            .FirstOrDefault();

        if (nic is null)
        {
            return new NetworkSnapshot(null, null, null);
        }

        string type = nic.NetworkInterfaceType switch
        {
            NetworkInterfaceType.Wireless80211 => "WIFI",
            NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet
                or NetworkInterfaceType.FastEthernetT => "ETHERNET",
            NetworkInterfaceType.Ppp or NetworkInterfaceType.Tunnel => "VPN",
            _ => "OTHER",
        };
        string? ip = nic.GetIPProperties().UnicastAddresses
            .FirstOrDefault(a => a.Address.AddressFamily == AddressFamily.InterNetwork)?.Address.ToString();
        return new NetworkSnapshot(type, nic.Name, ip);
    }
}

/// <summary>
/// Detects whether the Zscaler client is active from supported local signals (its processes and tunnel adapter).
/// Reports CONNECTED when the client is running, otherwise UNKNOWN. The platform never bypasses or decrypts
/// Zscaler; precise tunnel state requires the authorized Zscaler API integration on the backend.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsZscalerProvider : IZscalerProvider
{
    private static readonly string[] ProcessNames = { "ZSATray", "ZSAService", "ZSATunnel", "ZSAUpm" };

    public string GetStatus()
    {
        try
        {
            bool clientRunning = ProcessNames.Any(name => Process.GetProcessesByName(name).Length > 0);
            bool tunnelPresent = NetworkInterface.GetAllNetworkInterfaces()
                .Any(n => n.OperationalStatus == OperationalStatus.Up
                          && n.Description.Contains("Zscaler", StringComparison.OrdinalIgnoreCase));
            return clientRunning || tunnelPresent ? "CONNECTED" : "UNKNOWN";
        }
        catch (Exception)
        {
            return "UNKNOWN";
        }
    }
}

/// <summary>Removable (USB mass-storage) drives currently mounted. Device metadata only; files are never read.</summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsRemovableDriveProvider : IRemovableDriveProvider
{
    public IReadOnlyList<RemovableDrive> GetRemovableDrives()
    {
        try
        {
            return DriveInfo.GetDrives()
                .Where(d => d.DriveType == DriveType.Removable)
                .Select(d => new RemovableDrive(
                    d.Name.TrimEnd('\\'),
                    SafeLabel(d)))
                .ToList();
        }
        catch (Exception)
        {
            return Array.Empty<RemovableDrive>();
        }
    }

    private static string? SafeLabel(DriveInfo drive)
    {
        try
        {
            return drive.IsReady ? drive.VolumeLabel : null;
        }
        catch (Exception)
        {
            return null;
        }
    }
}
