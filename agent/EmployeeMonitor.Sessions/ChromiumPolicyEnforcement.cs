using System.Runtime.Versioning;
using EmployeeMonitor.Agent.Monitoring;
using Microsoft.Extensions.Logging;
using Microsoft.Win32;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Enforces the web policy using the official Chrome and Edge enterprise URLBlocklist/URLAllowlist registry policy
/// under HKLM\SOFTWARE\Policies. Browsers block navigation to the listed domains themselves and show their standard
/// block page - there is no HTTPS interception, no injected certificate, and no change to existing security products.
/// Writing these machine policies needs administrative rights; the one-time elevated setup creates the keys and
/// grants the agent account write access. If it cannot write them, it reports Healthy=false (never a silent failure).
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class ChromiumPolicyEnforcement : IWebEnforcement
{
    private const string MECHANISM = "chromium-url-policy";

    private static readonly (string Name, string Base)[] Browsers =
    {
        ("Chrome", @"SOFTWARE\Policies\Google\Chrome"),
        ("Edge", @"SOFTWARE\Policies\Microsoft\Edge"),
    };

    private readonly ILogger<ChromiumPolicyEnforcement> _log;

    public ChromiumPolicyEnforcement(ILogger<ChromiumPolicyEnforcement> log) => _log = log;

    public WebEnforcementResult Apply(IReadOnlyList<string> block, IReadOnlyList<string> allow)
    {
        var applied = new List<string>();
        string? error = null;
        foreach (var (name, basePath) in Browsers)
        {
            try
            {
                WriteList(basePath + @"\URLBlocklist", block);
                WriteList(basePath + @"\URLAllowlist", allow);
                applied.Add(name);
            }
            catch (Exception ex)
            {
                error = ex.Message;
                _log.LogDebug(ex, "Could not write {Browser} web policy", name);
            }
        }
        bool healthy = applied.Count > 0;
        if (healthy)
        {
            _log.LogInformation("Web policy applied to {Browsers}: {Block} blocked, {Allow} allowed.",
                string.Join(", ", applied), block.Count, allow.Count);
            return new WebEnforcementResult(MECHANISM, true, string.Join(", ", applied), null);
        }
        return new WebEnforcementResult(MECHANISM, false, null,
            "Could not write browser policy - run the setup step as administrator to grant access. " + error);
    }

    private static void WriteList(string subkey, IReadOnlyList<string> items)
    {
        using RegistryKey? key = Registry.LocalMachine.CreateSubKey(subkey, writable: true);
        if (key is null)
        {
            throw new InvalidOperationException("Cannot open " + subkey);
        }
        foreach (var existing in key.GetValueNames())
        {
            key.DeleteValue(existing, throwOnMissingValue: false);
        }
        int i = 1;
        foreach (var domain in items)
        {
            if (!string.IsNullOrWhiteSpace(domain))
            {
                key.SetValue((i++).ToString(), domain.Trim(), RegistryValueKind.String);
            }
        }
    }
}
