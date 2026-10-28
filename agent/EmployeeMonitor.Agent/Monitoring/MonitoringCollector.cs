using EmployeeMonitor.Agent.Api;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>
/// Owns the monitoring collectors (application, network, Zscaler, USB, website domains), their change trackers and
/// per-category upload queues. The agent calls <see cref="Poll"/> on the activity cadence and <see cref="FlushAsync"/>
/// on the upload cadence. Every category is gated by its policy flag and the monitoring window. Website usage is
/// domain-level only (registrable domains from the OS DNS cache) - never full URLs, page contents or packet data.
/// </summary>
public sealed class MonitoringCollector
{
    private readonly IForegroundAppProvider _foreground;
    private readonly INetworkProvider _network;
    private readonly IZscalerProvider _zscaler;
    private readonly IRemovableDriveProvider _drives;
    private readonly IDnsVisitProvider _dns;
    private readonly WebAccessManager? _webAccess;
    private readonly ILogger<MonitoringCollector> _logger;

    private readonly AppUsageTracker _appTracker = new();
    private readonly NetworkTracker _networkTracker = new();
    private readonly StatusChangeTracker _zscalerTracker = new();
    private readonly UsbDriveTracker _usbTracker = new();
    private readonly DnsVisitTracker _dnsTracker = new();

    private readonly UploadQueue<MonAppUsage> _apps = new();
    private readonly UploadQueue<MonNetworkEvent> _networks = new();
    private readonly UploadQueue<MonZscalerEvent> _zscalerEvents = new();
    private readonly UploadQueue<MonUsbEvent> _usbEvents = new();
    private readonly UploadQueue<MonWebsiteUsage> _websites = new();

    private readonly object _snap = new();
    private string? _currentApp;
    private string? _networkLabel;
    private string? _zscalerStatus;

    public MonitoringCollector(IForegroundAppProvider foreground, INetworkProvider network, IZscalerProvider zscaler,
        IRemovableDriveProvider drives, IDnsVisitProvider dns, ILogger<MonitoringCollector> logger,
        WebAccessManager? webAccess = null)
    {
        _foreground = foreground;
        _network = network;
        _zscaler = zscaler;
        _drives = drives;
        _dns = dns;
        _webAccess = webAccess;
        _logger = logger;
    }

    /// <summary>Latest live values for the heartbeat (null when the category is off or outside the window).</summary>
    public (string? App, string? Network, string? Zscaler) Snapshot()
    {
        lock (_snap)
        {
            return (_currentApp, _networkLabel, _zscalerStatus);
        }
    }

    public void Poll(PolicySettings policy, bool withinWindow, bool userActive, DateTimeOffset now)
    {
        try
        {
            PollApplications(policy, withinWindow, userActive, now);
            PollNetwork(policy, withinWindow, now);
            PollZscaler(policy, withinWindow, now);
            PollUsb(policy, withinWindow, now);
            PollWebsites(policy, withinWindow, now);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Monitoring poll failed: {Message}", ex.Message);
        }
    }

    private void PollApplications(PolicySettings policy, bool withinWindow, bool userActive, DateTimeOffset now)
    {
        if (policy.ApplicationMonitoring && withinWindow)
        {
            var fg = userActive ? _foreground.GetForeground() : null;
            Emit(_appTracker.Sample(fg?.Application, fg?.Process, now));
            SetApp(fg?.Application);
        }
        else
        {
            Emit(_appTracker.Close(now));
            SetApp(null);
        }
    }

    private void PollNetwork(PolicySettings policy, bool withinWindow, DateTimeOffset now)
    {
        if (!(policy.NetworkMonitoring && withinWindow))
        {
            SetNetwork(null);
            return;
        }
        var snap = _network.GetSnapshot();
        SetNetwork(snap.Name ?? snap.ConnectionType);
        var change = _networkTracker.Sample(snap);
        if (change is not null)
        {
            _networks.Enqueue(new MonNetworkEvent(Guid.NewGuid(), change.EventType, change.Snapshot.ConnectionType,
                change.Snapshot.Name, change.Snapshot.IpAddress, now));
        }
    }

    private void PollZscaler(PolicySettings policy, bool withinWindow, DateTimeOffset now)
    {
        if (!(policy.ZscalerMonitoring && withinWindow))
        {
            SetZscaler(null);
            return;
        }
        var status = _zscaler.GetStatus();
        SetZscaler(status);
        if (_zscalerTracker.Changed(status))
        {
            _zscalerEvents.Enqueue(new MonZscalerEvent(Guid.NewGuid(), status, null, now));
        }
    }

    private void PollUsb(PolicySettings policy, bool withinWindow, DateTimeOffset now)
    {
        if (!(policy.UsbMonitoring && withinWindow))
        {
            return;
        }
        foreach (var change in _usbTracker.Sample(_drives.GetRemovableDrives()))
        {
            _usbEvents.Enqueue(new MonUsbEvent(Guid.NewGuid(), change.EventType, "USB Mass Storage",
                null, null, change.Drive.Key, change.Drive.Description, now));
        }
    }

    private void PollWebsites(PolicySettings policy, bool withinWindow, DateTimeOffset now)
    {
        // Two consumers share one DNS read because GetResolvedHosts drains its buffer: website-usage tracking
        // (registrable domains, gated by WebsiteMonitoring) and web-access observation (policy-relevant hosts).
        bool websiteUsage = policy.WebsiteMonitoring && withinWindow;
        bool webAccess = _webAccess is not null && withinWindow;
        if (!websiteUsage && !webAccess)
        {
            return;
        }
        // Domain-level only: the hosts the machine resolved since the last poll - never full URLs or page data.
        var hosts = _dns.GetResolvedHosts();
        if (webAccess)
        {
            _webAccess!.Observe(hosts, now);
        }
        if (websiteUsage)
        {
            foreach (var domain in _dnsTracker.Sample(hosts))
            {
                _websites.Enqueue(new MonWebsiteUsage(Guid.NewGuid(), domain, _currentApp, now, now));
            }
        }
    }

    /// <summary>Closes the open application interval on shutdown so its usage is not lost.</summary>
    public void CloseOpenIntervals(DateTimeOffset now) => Emit(_appTracker.Close(now));

    private void Emit(AppInterval? interval)
    {
        if (interval is not null)
        {
            _apps.Enqueue(new MonAppUsage(Guid.NewGuid(), interval.Application, interval.Process,
                interval.Start, interval.End));
        }
    }

    public async Task FlushAsync(IBackendApi api, string deviceToken, CancellationToken ct)
    {
        await Flush(_apps, b => api.PostApplicationUsageAsync(b, deviceToken, ct));
        await Flush(_networks, b => api.PostNetworkEventsAsync(b, deviceToken, ct));
        await Flush(_zscalerEvents, b => api.PostZscalerEventsAsync(b, deviceToken, ct));
        await Flush(_usbEvents, b => api.PostUsbEventsAsync(b, deviceToken, ct));
        await Flush(_websites, b => api.PostWebsiteUsageAsync(b, deviceToken, ct));
    }

    private async Task Flush<T>(UploadQueue<T> queue, Func<MonBatch<T>, Task<MonIngestResult>> upload)
    {
        var batch = queue.Drain(200);
        if (batch.Count == 0)
        {
            return;
        }
        try
        {
            await upload(new MonBatch<T>(batch));
        }
        catch (BackendException ex) when (ex.IsAuthFailure)
        {
            _logger.LogError("Monitoring upload rejected ({Code}); dropping {Count}", ex.ErrorCode, batch.Count);
        }
        catch (Exception ex)
        {
            _logger.LogWarning("Monitoring upload failed ({Message}); will retry", ex.Message);
            queue.Requeue(batch);
        }
    }

    private void SetApp(string? value)
    {
        lock (_snap)
        {
            _currentApp = value;
        }
    }

    private void SetNetwork(string? value)
    {
        lock (_snap)
        {
            _networkLabel = value;
        }
    }

    private void SetZscaler(string? value)
    {
        lock (_snap)
        {
            _zscalerStatus = value;
        }
    }
}
