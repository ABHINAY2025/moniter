using EmployeeMonitor.Agent;
using EmployeeMonitor.Agent.Api;
using EmployeeMonitor.Agent.Configuration;
using EmployeeMonitor.Agent.Monitoring;
using EmployeeMonitor.Agent.Platform;
using EmployeeMonitor.Service;
using EmployeeMonitor.Sessions;
using Microsoft.Extensions.Options;

// Make the process per-monitor DPI aware before anything creates a window/DC, so screen capture grabs the full
// physical resolution on scaled displays.
if (OperatingSystem.IsWindows())
{
    GdiScreenCaptureProvider.EnsureDpiAware();
    // Run the always-on agent in a low-power (Eco QoS) mode to be light on battery and heat.
    PowerThrottling.EnableEcoQoS();
}

var builder = Host.CreateApplicationBuilder(args);

// Runs as a Windows Service when launched by the SCM, and as a normal console app otherwise.
builder.Services.AddWindowsService(options => options.ServiceName = "EmployeeMonitorAgent");

builder.Services.Configure<AgentOptions>(builder.Configuration.GetSection(AgentOptions.SectionName));

builder.Services.AddSingleton<ISystemClock, SystemClock>();
builder.Services.AddSingleton<ISystemInfo, WindowsSystemInfo>();
builder.Services.AddSingleton<IInputMonitor, Win32InputMonitor>();
builder.Services.AddSingleton<ISystemEventSource, WindowsSessionEventSource>();
builder.Services.AddSingleton<IForegroundAppProvider, Win32ForegroundAppProvider>();
builder.Services.AddSingleton<INetworkProvider, WindowsNetworkProvider>();
builder.Services.AddSingleton<IZscalerProvider, WindowsZscalerProvider>();
builder.Services.AddSingleton<IRemovableDriveProvider, WindowsRemovableDriveProvider>();
builder.Services.AddSingleton<IDnsVisitProvider, WindowsDnsVisitProvider>();
builder.Services.AddSingleton<IScreenCaptureProvider, GdiScreenCaptureProvider>();
builder.Services.AddSingleton<ISystemAudioCapture, WindowsSystemAudioCapture>();
builder.Services.AddSingleton<IRemoteInputInjector, WindowsRemoteInputInjector>();
builder.Services.AddSingleton<ISystemClipboard, WindowsClipboard>();
builder.Services.AddSingleton<WebAccessManager>();
builder.Services.AddSingleton<IWebEnforcement, ChromiumPolicyEnforcement>();
builder.Services.AddSingleton<MonitoringCollector>();
builder.Services.AddSingleton<ISecureStore>(sp =>
{
    var opts = sp.GetRequiredService<IOptions<AgentOptions>>().Value;
    var dir = string.IsNullOrWhiteSpace(opts.DataDirectory)
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "EmployeeMonitor")
        : opts.DataDirectory;
    return new DpapiSecureStore(dir, sp.GetRequiredService<ILogger<DpapiSecureStore>>());
});
builder.Services.AddSingleton<CredentialStore>();

builder.Services.AddHttpClient<IBackendApi, BackendClient>((sp, client) =>
{
    var opts = sp.GetRequiredService<IOptions<AgentOptions>>().Value;
    client.BaseAddress = new Uri(opts.BackendBaseUrl);
    client.Timeout = TimeSpan.FromSeconds(30);
});

builder.Services.AddSingleton<AgentRunner>();
builder.Services.AddHostedService<AgentWorker>();

await builder.Build().RunAsync();
