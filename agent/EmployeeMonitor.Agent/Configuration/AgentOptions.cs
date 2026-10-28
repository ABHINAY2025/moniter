namespace EmployeeMonitor.Agent.Configuration;

/// <summary>Static configuration from appsettings / environment (not secrets).</summary>
public sealed class AgentOptions
{
    public const string SectionName = "Agent";

    /// <summary>Backend base URL, e.g. https://monitor.company.com. Use HTTPS in production.</summary>
    public string BackendBaseUrl { get; set; } = "http://localhost:8080";

    /// <summary>
    /// Enrollment token for first-run registration. Normally supplied by the installer via the
    /// EM_ENROLLMENT_TOKEN environment variable rather than stored in a file.
    /// </summary>
    public string? EnrollmentToken { get; set; }

    /// <summary>Directory for the encrypted device credential. Defaults to %ProgramData%\EmployeeMonitor.</summary>
    public string? DataDirectory { get; set; }

    /// <summary>How often to flush queued session events to the backend.</summary>
    public int EventFlushSeconds { get; set; } = 15;

    /// <summary>How often to sample input idle time.</summary>
    public int ActivityPollSeconds { get; set; } = 5;

    /// <summary>Fallbacks used until the first policy sync succeeds.</summary>
    public int DefaultHeartbeatSeconds { get; set; } = 30;
    public int DefaultIdleThresholdSeconds { get; set; } = 300;

    /// <summary>Delay before the first screenshot after start (so the first appears promptly), capped by the interval.</summary>
    public int ScreenshotInitialDelaySeconds { get; set; } = 5;

    /// <summary>Base64 (SubjectPublicKeyInfo) RSA public key used to verify signed agent updates. Empty disables updates.</summary>
    public string? UpdatePublicKey { get; set; }

    /// <summary>How often to check for a signed agent update.</summary>
    public int UpdateCheckIntervalHours { get; set; } = 6;

    /// <summary>How often to fetch the effective web-access policy, reapply enforcement and flush observations.</summary>
    public int WebAccessPollSeconds { get; set; } = 20;
}
