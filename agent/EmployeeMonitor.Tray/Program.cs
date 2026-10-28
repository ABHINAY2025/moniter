using System.Text.Json;
using EmployeeMonitor.Tray;

// Single-instance, visible tray indicator. No elevation, no hidden windows.
using var mutex = new Mutex(initiallyOwned: true, "EmployeeMonitorTray.SingleInstance", out bool isNew);
if (!isNew)
{
    return;
}

ApplicationConfiguration.Initialize();

if (OperatingSystem.IsWindows())
{
    EmployeeMonitor.Sessions.PowerThrottling.EnableEcoQoS();
}

string baseUrl = ResolveBackendUrl();
using var context = new TrayApplicationContext(baseUrl);
Application.Run(context);

static string ResolveBackendUrl()
{
    var env = Environment.GetEnvironmentVariable("EM_BACKEND_URL");
    if (!string.IsNullOrWhiteSpace(env))
    {
        return env;
    }
    try
    {
        var path = Path.Combine(AppContext.BaseDirectory, "appsettings.json");
        if (File.Exists(path))
        {
            using var doc = JsonDocument.Parse(File.ReadAllText(path));
            if (doc.RootElement.TryGetProperty("Agent", out var agent)
                && agent.TryGetProperty("BackendBaseUrl", out var url)
                && url.GetString() is { Length: > 0 } s)
            {
                return s;
            }
        }
    }
    catch (Exception)
    {
        // fall through to default
    }
    return "http://localhost:8080";
}
