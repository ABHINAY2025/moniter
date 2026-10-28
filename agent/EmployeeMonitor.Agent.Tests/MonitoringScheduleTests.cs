using EmployeeMonitor.Agent.Monitoring;
using FluentAssertions;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class MonitoringScheduleTests
{
    [Fact]
    public void InsideWorkHoursOnWorkdayIsWithinWindow()
    {
        var policy = TestData.Policy(start: "09:00", end: "18:00") with { Timezone = "UTC" };
        // 2026-10-06 is a Tuesday.
        var noon = new DateTimeOffset(2026, 10, 6, 12, 0, 0, TimeSpan.Zero);
        MonitoringSchedule.IsWithinWindow(policy, noon).Should().BeTrue();
    }

    [Fact]
    public void BeforeStartOrAfterEndIsOutsideWindow()
    {
        var policy = TestData.Policy(start: "09:00", end: "18:00") with { Timezone = "UTC" };
        var early = new DateTimeOffset(2026, 10, 6, 8, 0, 0, TimeSpan.Zero);
        var late = new DateTimeOffset(2026, 10, 6, 19, 0, 0, TimeSpan.Zero);
        MonitoringSchedule.IsWithinWindow(policy, early).Should().BeFalse();
        MonitoringSchedule.IsWithinWindow(policy, late).Should().BeFalse();
    }

    [Fact]
    public void NonWorkdayIsOutsideWindow()
    {
        var policy = TestData.Policy(start: "00:00", end: "23:59") with
        {
            Timezone = "UTC",
            WorkDays = new[] { "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY" },
        };
        // 2026-10-10 is a Saturday.
        var saturday = new DateTimeOffset(2026, 10, 10, 12, 0, 0, TimeSpan.Zero);
        MonitoringSchedule.IsWithinWindow(policy, saturday).Should().BeFalse();
    }

    [Fact]
    public void TimezoneIsApplied()
    {
        var policy = TestData.Policy(start: "09:00", end: "18:00") with { Timezone = "Asia/Kolkata" };
        // 05:00 UTC == 10:30 IST on a Tuesday -> within window.
        var utc = new DateTimeOffset(2026, 10, 6, 5, 0, 0, TimeSpan.Zero);
        MonitoringSchedule.IsWithinWindow(policy, utc).Should().BeTrue();
        // 14:00 UTC == 19:30 IST -> outside.
        MonitoringSchedule.IsWithinWindow(policy, new DateTimeOffset(2026, 10, 6, 14, 0, 0, TimeSpan.Zero))
            .Should().BeFalse();
    }

    [Fact]
    public void UnknownTimezoneFallsBackToUtcAndDoesNotThrow()
    {
        var policy = TestData.Policy(start: "09:00", end: "18:00") with { Timezone = "Mars/Olympus" };
        var noon = new DateTimeOffset(2026, 10, 6, 12, 0, 0, TimeSpan.Zero);
        MonitoringSchedule.IsWithinWindow(policy, noon).Should().BeTrue();
    }
}
