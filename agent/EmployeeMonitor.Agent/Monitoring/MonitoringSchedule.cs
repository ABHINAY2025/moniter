using System.Globalization;
using EmployeeMonitor.Agent.Api;

namespace EmployeeMonitor.Agent.Monitoring;

/// <summary>
/// Evaluates whether a given instant falls inside the policy's monitoring window (work hours and days, in the
/// policy timezone). Pure and testable. Heartbeats are always sent; only activity/session capture is gated.
/// </summary>
public static class MonitoringSchedule
{
    public static bool IsWithinWindow(PolicySettings policy, DateTimeOffset utcNow)
    {
        TimeZoneInfo tz;
        try
        {
            tz = TimeZoneInfo.FindSystemTimeZoneById(policy.Timezone);
        }
        catch (TimeZoneNotFoundException)
        {
            tz = TimeZoneInfo.Utc;
        }
        catch (InvalidTimeZoneException)
        {
            tz = TimeZoneInfo.Utc;
        }

        var local = TimeZoneInfo.ConvertTime(utcNow, tz);
        var day = local.DayOfWeek.ToString().ToUpperInvariant();
        if (policy.WorkDays is { Count: > 0 } && !policy.WorkDays.Any(d => d.ToUpperInvariant() == day))
        {
            return false;
        }

        if (!TryParse(policy.MonitoringStart, out var start) || !TryParse(policy.MonitoringEnd, out var end))
        {
            return true; // malformed window => do not suppress monitoring
        }
        var now = local.TimeOfDay;
        return now >= start && now < end;
    }

    private static bool TryParse(string value, out TimeSpan time) =>
        TimeSpan.TryParseExact(value, "hh\\:mm", CultureInfo.InvariantCulture, out time);
}
