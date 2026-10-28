using EmployeeMonitor.Agent.Api;

namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>
/// Holds the device's effective web-access policy and turns observed DNS resolutions into web-access events for the
/// domains the policy cares about. Domain comparison is subdomain-safe (host equals or is a subdomain of a rule).
/// The server is authoritative on the enforcement result; the agent only reports what it observed. Pure and testable.
/// </summary>
public sealed class WebAccessManager
{
    private static readonly TimeSpan DedupWindow = TimeSpan.FromMinutes(2);

    private readonly object _gate = new();
    private readonly List<WebAccessEventInput> _pending = new();
    private readonly Dictionary<string, DateTimeOffset> _lastEmitted = new(StringComparer.OrdinalIgnoreCase);

    private HashSet<string> _block = new(StringComparer.OrdinalIgnoreCase);
    private HashSet<string> _allow = new(StringComparer.OrdinalIgnoreCase);
    private HashSet<string> _monitor = new(StringComparer.OrdinalIgnoreCase);

    public string Revision { get; private set; } = "0";

    public void SetPolicy(EffectiveWebPolicy policy)
    {
        lock (_gate)
        {
            _block = Canonical(policy.Block);
            _allow = Canonical(policy.Allow);
            _monitor = Canonical(policy.Monitor);
            Revision = policy.Revision ?? "0";
        }
    }

    /// <summary>Records observed resolved hosts; queues a web-access event for each host a rule applies to.</summary>
    public void Observe(IEnumerable<string> hosts, DateTimeOffset now)
    {
        lock (_gate)
        {
            foreach (var raw in hosts)
            {
                string host = Canonicalize(raw);
                if (host.Length == 0 || !IsRelevant(host))
                {
                    continue;
                }
                if (_lastEmitted.TryGetValue(host, out var last) && now - last < DedupWindow)
                {
                    continue;
                }
                _lastEmitted[host] = now;
                _pending.Add(new WebAccessEventInput(Guid.NewGuid(), host, null, "NAVIGATION_OBSERVED", "DNS_LOG", now));
            }
        }
    }

    public IReadOnlyList<WebAccessEventInput> DrainEvents()
    {
        lock (_gate)
        {
            if (_pending.Count == 0)
            {
                return Array.Empty<WebAccessEventInput>();
            }
            var batch = _pending.ToArray();
            _pending.Clear();
            return batch;
        }
    }

    private bool IsRelevant(string host)
    {
        return MatchesAny(_block, host) || MatchesAny(_allow, host) || MatchesAny(_monitor, host);
    }

    private static bool MatchesAny(HashSet<string> domains, string host)
    {
        foreach (var d in domains)
        {
            if (host.Equals(d, StringComparison.OrdinalIgnoreCase) ||
                host.EndsWith("." + d, StringComparison.OrdinalIgnoreCase))
            {
                return true;
            }
        }
        return false;
    }

    private static HashSet<string> Canonical(IReadOnlyList<string>? domains)
    {
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        if (domains != null)
        {
            foreach (var d in domains)
            {
                string c = Canonicalize(d);
                if (c.Length > 0)
                {
                    set.Add(c);
                }
            }
        }
        return set;
    }

    /// <summary>Lower-cased host with scheme/path/port/trailing-dot removed (mirrors the server's DomainMatcher).</summary>
    public static string Canonicalize(string? input)
    {
        if (string.IsNullOrWhiteSpace(input))
        {
            return "";
        }
        string s = input.Trim().ToLowerInvariant();
        int scheme = s.IndexOf("://", StringComparison.Ordinal);
        if (scheme >= 0)
        {
            s = s[(scheme + 3)..];
        }
        int at = s.IndexOf('@');
        if (at >= 0)
        {
            s = s[(at + 1)..];
        }
        int slash = s.IndexOf('/');
        if (slash >= 0)
        {
            s = s[..slash];
        }
        int colon = s.IndexOf(':');
        if (colon >= 0)
        {
            s = s[..colon];
        }
        return s.Trim('.');
    }
}
