using EmployeeMonitor.Agent;

namespace EmployeeMonitor.Service;

/// <summary>Hosts the <see cref="AgentRunner"/> inside the generic host / Windows Service.</summary>
public sealed class AgentWorker : BackgroundService
{
    private readonly AgentRunner _runner;
    private readonly IHostApplicationLifetime _lifetime;
    private readonly ILogger<AgentWorker> _logger;

    public AgentWorker(AgentRunner runner, IHostApplicationLifetime lifetime, ILogger<AgentWorker> logger)
    {
        _runner = runner;
        _lifetime = lifetime;
        _logger = logger;
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        try
        {
            await _runner.RunAsync(stoppingToken);
        }
        catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested)
        {
            // normal shutdown
        }
        catch (InvalidOperationException ex)
        {
            // e.g. not enrolled and no enrollment token: fatal, stop cleanly rather than crash-loop.
            _logger.LogCritical("Agent cannot start: {Message}", ex.Message);
            _lifetime.StopApplication();
        }
        catch (Exception ex)
        {
            _logger.LogCritical(ex, "Agent terminated unexpectedly");
            _lifetime.StopApplication();
        }
    }
}
