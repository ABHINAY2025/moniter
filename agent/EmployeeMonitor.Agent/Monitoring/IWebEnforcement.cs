namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>Outcome of applying the web policy: the mechanism used, whether it is actually in effect, and any error.</summary>
public sealed record WebEnforcementResult(string Mechanism, bool Healthy, string? Browsers, string? Error);

/// <summary>
/// Applies the effective block/allow domain policy to supported browsers. The production implementation uses the
/// official Chrome/Edge enterprise URLBlocklist/URLAllowlist policy (domain-level, no TLS interception, no certs).
/// It must never disable existing security products or intercept HTTPS. Returns an honest health result - if the
/// policy could not be written (e.g. the process lacks rights), Healthy is false and Error explains why.
/// </summary>
public interface IWebEnforcement
{
    WebEnforcementResult Apply(IReadOnlyList<string> block, IReadOnlyList<string> allow);
}
