using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Platform;
using Microsoft.Win32;

namespace EmployeeMonitor.Sessions;

/// <summary>Host facts gathered at enrollment from supported APIs.</summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsSystemInfo : ISystemInfo
{
    public string Hostname { get; } = Environment.MachineName;

    public string OsVersion { get; } = BuildOsVersion();

    public string? MacAddress { get; }

    public string? PrimaryIpAddress { get; }

    public string? HardwareId { get; } = ReadMachineGuid();

    public WindowsSystemInfo()
    {
        var nic = NetworkInterface.GetAllNetworkInterfaces()
            .Where(n => n.OperationalStatus == OperationalStatus.Up
                        && n.NetworkInterfaceType != NetworkInterfaceType.Loopback
                        && n.NetworkInterfaceType != NetworkInterfaceType.Tunnel)
            .OrderByDescending(n => n.Speed)
            .FirstOrDefault();

        if (nic is not null)
        {
            var mac = nic.GetPhysicalAddress().GetAddressBytes();
            if (mac.Length == 6)
            {
                MacAddress = string.Join("-", mac.Select(b => b.ToString("X2")));
            }
            PrimaryIpAddress = nic.GetIPProperties().UnicastAddresses
                .FirstOrDefault(a => a.Address.AddressFamily == AddressFamily.InterNetwork)?.Address.ToString();
        }
    }

    private static string BuildOsVersion()
    {
        string description = RuntimeInformation.OSDescription.Trim();
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(
                @"SOFTWARE\Microsoft\Windows NT\CurrentVersion");
            var product = key?.GetValue("ProductName") as string;
            var display = key?.GetValue("DisplayVersion") as string;
            if (!string.IsNullOrWhiteSpace(product))
            {
                return string.IsNullOrWhiteSpace(display) ? product : $"{product} {display}";
            }
        }
        catch (Exception)
        {
            // fall back to the runtime description
        }
        return description;
    }

    private static string? ReadMachineGuid()
    {
        try
        {
            using var key = Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Cryptography");
            return key?.GetValue("MachineGuid") as string;
        }
        catch (Exception)
        {
            return null;
        }
    }
}
