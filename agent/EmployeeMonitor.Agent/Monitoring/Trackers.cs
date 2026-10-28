namespace EmployeeMonitor.Agent.Monitoring;

// Pure, side-effect-free change trackers. The agent samples the OS periodically and feeds the samples here;
// the trackers emit only the deltas worth recording. Fully unit-testable without any Windows API.

/// <summary>A completed foreground-application interval.</summary>
public sealed record AppInterval(string Application, string? Process, DateTimeOffset Start, DateTimeOffset End);

/// <summary>
/// Builds foreground-application usage intervals from a stream of samples. A sample of <c>null</c> (no active
/// foreground app, e.g. the user is idle or locked) closes the current interval, so idle time is never counted
/// as application usage. Never inspects window contents - only the process/app name.
/// </summary>
public sealed class AppUsageTracker
{
    private string? _app;
    private string? _process;
    private DateTimeOffset _start;

    public AppInterval? Sample(string? application, string? process, DateTimeOffset now)
    {
        if (string.Equals(application, _app, StringComparison.Ordinal))
        {
            return null;
        }
        var completed = Close(now);
        _app = application;
        _process = process;
        _start = now;
        return completed;
    }

    /// <summary>Closes any open interval (on shutdown or when application monitoring is turned off).</summary>
    public AppInterval? Close(DateTimeOffset now)
    {
        if (_app is null || now <= _start)
        {
            _app = null;
            return null;
        }
        var interval = new AppInterval(_app, _process, _start, now);
        _app = null;
        _process = null;
        return interval;
    }
}

public sealed record NetworkSnapshot(string? ConnectionType, string? Name, string? IpAddress)
{
    public bool IsConnected => ConnectionType is not null || IpAddress is not null;
}

public sealed record NetworkChange(string EventType, NetworkSnapshot Snapshot);

/// <summary>Emits CONNECTED / DISCONNECTED / CHANGED as the endpoint's network state changes.</summary>
public sealed class NetworkTracker
{
    private NetworkSnapshot? _last;

    public NetworkChange? Sample(NetworkSnapshot current)
    {
        var previous = _last;
        _last = current;

        bool wasConnected = previous?.IsConnected ?? false;
        if (!wasConnected && current.IsConnected)
        {
            return new NetworkChange("CONNECTED", current);
        }
        if (wasConnected && !current.IsConnected)
        {
            return new NetworkChange("DISCONNECTED", current);
        }
        if (wasConnected && current.IsConnected && !current.Equals(previous))
        {
            return new NetworkChange("CHANGED", current);
        }
        return null;
    }
}

/// <summary>Emits a change only when the Zscaler status differs from the last reported one.</summary>
public sealed class StatusChangeTracker
{
    private string? _last;

    public bool Changed(string status)
    {
        if (string.Equals(status, _last, StringComparison.Ordinal))
        {
            return false;
        }
        _last = status;
        return true;
    }
}

/// <summary>A removable drive currently present.</summary>
public sealed record RemovableDrive(string Key, string? Description);

public sealed record UsbChange(string EventType, RemovableDrive Drive);

/// <summary>
/// Normalises resolved host names to a registrable domain and emits a "visit" the first time a domain appears in
/// the DNS cache (and again after it expires and is re-resolved). Domain-level only. Skips reverse-lookup and
/// local names.
/// </summary>
public sealed class DnsVisitTracker
{
    private static readonly string[] TwoLevelTlds =
    {
        "co.uk", "co.in", "co.jp", "co.kr", "co.nz", "co.za", "com.au", "com.br", "com.cn",
        "com.mx", "com.tr", "com.sg", "org.uk", "gov.uk", "ac.uk", "net.au", "org.au",
    };

    private HashSet<string> _present = new();

    /// <summary>Returns the registrable domains that newly appeared since the previous sample.</summary>
    public IReadOnlyList<string> Sample(IEnumerable<string> resolvedHosts)
    {
        var current = new HashSet<string>();
        foreach (var host in resolvedHosts)
        {
            var domain = Normalize(host);
            if (domain is not null)
            {
                current.Add(domain);
            }
        }
        var fresh = current.Where(d => !_present.Contains(d)).ToList();
        _present = current;
        return fresh;
    }

    /// <summary>Reduces a host name to a registrable domain (e.g. www.youtube.com -> youtube.com), or null to skip.</summary>
    public static string? Normalize(string? host)
    {
        if (string.IsNullOrWhiteSpace(host))
        {
            return null;
        }
        host = host.Trim().TrimEnd('.').ToLowerInvariant();
        if (host.Length == 0 || host == "localhost"
            || host.EndsWith(".local") || host.EndsWith(".lan") || host.EndsWith(".internal")
            || host.EndsWith(".in-addr.arpa") || host.EndsWith(".ip6.arpa")
            || System.Net.IPAddress.TryParse(host, out _))
        {
            return null;
        }
        var labels = host.Split('.');
        if (labels.Length < 2)
        {
            return null;
        }
        int take = 2;
        if (labels.Length >= 3)
        {
            var lastTwo = labels[^2] + "." + labels[^1];
            if (TwoLevelTlds.Contains(lastTwo))
            {
                take = 3;
            }
        }
        return string.Join('.', labels[^Math.Min(take, labels.Length)..]);
    }
}

/// <summary>Diffs the set of removable drives between samples into CONNECTED / REMOVED events (metadata only).</summary>
public sealed class UsbDriveTracker
{
    private Dictionary<string, RemovableDrive> _present = new();

    public IReadOnlyList<UsbChange> Sample(IEnumerable<RemovableDrive> drives)
    {
        var current = drives.ToDictionary(d => d.Key, d => d);
        var changes = new List<UsbChange>();
        foreach (var d in current.Values)
        {
            if (!_present.ContainsKey(d.Key))
            {
                changes.Add(new UsbChange("CONNECTED", d));
            }
        }
        foreach (var d in _present.Values)
        {
            if (!current.ContainsKey(d.Key))
            {
                changes.Add(new UsbChange("REMOVED", d));
            }
        }
        _present = current;
        return changes;
    }
}
