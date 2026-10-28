using System.Diagnostics.Eventing.Reader;
using System.Runtime.Versioning;
using System.Text.RegularExpressions;
using EmployeeMonitor.Agent.Monitoring;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Captures the host names the machine resolves by subscribing to the Windows DNS-Client operational event log
/// (Microsoft-Windows-DNS-Client/Operational, event 3008 "query completed"). This is the query NAME only -
/// domain-level metadata - never full URLs, page contents or packet payloads, and it works even when the local
/// DNS cache is empty or disabled. The channel is off by default; the agent enables it once (requires the service
/// to run with administrative rights, which the installed Windows service does). The tracker downstream reduces
/// each name to its registrable domain and records a visit.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class WindowsDnsVisitProvider : IDnsVisitProvider, IDisposable
{
    private const string ChannelName = "Microsoft-Windows-DNS-Client/Operational";

    // <Data Name="QueryName">www.youtube.com</Data> in the rendered event XML.
    private static readonly Regex QueryNameRx = new(
        "<Data Name=['\"]QueryName['\"]>(?<q>[^<]*)</Data>",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    private readonly ILogger<WindowsDnsVisitProvider> _log;
    private readonly object _gate = new();
    private readonly HashSet<string> _buffer = new(StringComparer.OrdinalIgnoreCase);
    private EventLogWatcher? _watcher;
    private bool _warnedUnavailable;

    public WindowsDnsVisitProvider(ILogger<WindowsDnsVisitProvider> log)
    {
        _log = log;
        TryStart();
    }

    private void TryStart()
    {
        try
        {
            EnableChannel();
            // Event 3008 = DNS query completed (carries QueryName + results).
            var query = new EventLogQuery(ChannelName, PathType.LogName, "*[System[(EventID=3008)]]");
            _watcher = new EventLogWatcher(query);
            _watcher.EventRecordWritten += OnEvent;
            _watcher.Enabled = true;
            _log.LogInformation("DNS visit capture started on {Channel} (domain-level query names only).", ChannelName);
        }
        catch (Exception ex)
        {
            // Most commonly: the channel is disabled and the agent lacks rights to enable it (running un-elevated).
            _log.LogWarning(ex,
                "DNS visit capture unavailable - website monitoring will report nothing until the agent runs " +
                "with admin rights (installed service) so the {Channel} channel can be enabled.", ChannelName);
            DisposeWatcher();
        }
    }

    private void EnableChannel()
    {
        var cfg = new EventLogConfiguration(ChannelName);
        if (!cfg.IsEnabled)
        {
            cfg.IsEnabled = true;
            cfg.SaveChanges();
        }
    }

    private void OnEvent(object? sender, EventRecordWrittenEventArgs e)
    {
        if (e.EventException is not null || e.EventRecord is null)
        {
            return;
        }
        try
        {
            using var record = e.EventRecord;
            var match = QueryNameRx.Match(record.ToXml());
            if (match.Success)
            {
                var host = match.Groups["q"].Value;
                if (!string.IsNullOrWhiteSpace(host))
                {
                    lock (_gate)
                    {
                        _buffer.Add(host);
                    }
                }
            }
        }
        catch (Exception ex)
        {
            if (!_warnedUnavailable)
            {
                _warnedUnavailable = true;
                _log.LogDebug(ex, "Failed to read a DNS-Client event.");
            }
        }
    }

    /// <summary>Returns the host names resolved since the previous poll, then clears the buffer.</summary>
    public IReadOnlyList<string> GetResolvedHosts()
    {
        lock (_gate)
        {
            if (_buffer.Count == 0)
            {
                return Array.Empty<string>();
            }
            var snapshot = _buffer.ToArray();
            _buffer.Clear();
            return snapshot;
        }
    }

    private void DisposeWatcher()
    {
        if (_watcher is not null)
        {
            try
            {
                _watcher.Enabled = false;
                _watcher.EventRecordWritten -= OnEvent;
                _watcher.Dispose();
            }
            catch
            {
                // ignore teardown errors
            }
            _watcher = null;
        }
    }

    public void Dispose() => DisposeWatcher();
}
