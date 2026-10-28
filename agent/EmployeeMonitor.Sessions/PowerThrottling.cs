using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Opts the current process into Windows "Eco QoS" power throttling, so the OS schedules it on efficiency cores at a
/// lower frequency. This keeps the always-on background agent light on battery and heat. It still runs normally when
/// the machine is active; Eco QoS only changes how the scheduler prioritises power vs performance.
/// </summary>
[SupportedOSPlatform("windows")]
public static class PowerThrottling
{
    public static void EnableEcoQoS()
    {
        try
        {
            var state = new PROCESS_POWER_THROTTLING_STATE
            {
                Version = PROCESS_POWER_THROTTLING_CURRENT_VERSION,
                ControlMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED,
                StateMask = PROCESS_POWER_THROTTLING_EXECUTION_SPEED,
            };
            SetProcessInformation(GetCurrentProcess(), ProcessPowerThrottling, ref state,
                Marshal.SizeOf<PROCESS_POWER_THROTTLING_STATE>());
        }
        catch
        {
            // Best-effort: older Windows without Eco QoS just keeps default scheduling.
        }
    }

    private const int ProcessPowerThrottling = 4;   // PROCESS_INFORMATION_CLASS.ProcessPowerThrottling
    private const uint PROCESS_POWER_THROTTLING_CURRENT_VERSION = 1;
    private const uint PROCESS_POWER_THROTTLING_EXECUTION_SPEED = 0x1;

    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_POWER_THROTTLING_STATE
    {
        public uint Version;
        public uint ControlMask;
        public uint StateMask;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetProcessInformation(IntPtr hProcess, int ProcessInformationClass,
        ref PROCESS_POWER_THROTTLING_STATE ProcessInformation, int ProcessInformationSize);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();
}
