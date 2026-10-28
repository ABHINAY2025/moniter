using System.Text.Json;
using EmployeeMonitor.Admin.Core;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;
using EmployeeMonitor.Admin.Core.ViewModels;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace EmployeeMonitor.Admin.Infrastructure;

/// <summary>Composition root. Core services are singletons; view models are transient per navigation.</summary>
public static class ServiceConfig
{
    public static IServiceProvider Build(IUiDispatcher dispatcher)
    {
        string baseUrl = ResolveBaseUrl();
        var services = new ServiceCollection();

        services.AddSingleton(dispatcher);
        services.AddSingleton<SessionState>();
        services.AddSingleton(_ => new HttpClient { BaseAddress = new Uri(baseUrl), Timeout = TimeSpan.FromSeconds(30) });
        services.AddSingleton<IAdminApi>(sp => new AdminApiClient(
            sp.GetRequiredService<HttpClient>(), sp.GetRequiredService<SessionState>(),
            NullLogger<AdminApiClient>.Instance));
        services.AddSingleton<IRealtimeClient>(sp => new RealtimeClient(
            baseUrl, sp.GetRequiredService<SessionState>(), NullLogger<RealtimeClient>.Instance));
        services.AddTransient<ILiveViewSignaling>(sp => new LiveViewSignalingClient(
            baseUrl, sp.GetRequiredService<SessionState>(), NullLogger<LiveViewSignalingClient>.Instance));

        services.AddSingleton<ShellViewModel>();
        services.AddSingleton<AlertsViewModel>();
        services.AddSingleton<WebAccessViewModel>();
        services.AddTransient<LoginViewModel>();
        services.AddTransient<DashboardViewModel>();
        services.AddTransient<EmployeesViewModel>();
        services.AddTransient<DevicesViewModel>();
        services.AddTransient<EmployeeDetailViewModel>();
        services.AddTransient<LiveViewViewModel>();
        services.AddTransient<PolicyViewModel>();

        return services.BuildServiceProvider();
    }

    private static string ResolveBaseUrl()
    {
        var env = Environment.GetEnvironmentVariable("EM_ADMIN_BACKEND_URL");
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
                if (doc.RootElement.TryGetProperty("Backend", out var b)
                    && b.TryGetProperty("BaseUrl", out var u) && u.GetString() is { Length: > 0 } s)
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
}
