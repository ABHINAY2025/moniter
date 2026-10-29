using EmployeeMonitor.Admin.Core;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.ViewModels;
using FluentAssertions;
using Xunit;

namespace EmployeeMonitor.Admin.Core.Tests;

public class LoginViewModelTests
{
    [Fact]
    public void CannotLoginUntilBothFieldsPresent()
    {
        var vm = new LoginViewModel(new FakeAdminApi());
        vm.LoginCommand.CanExecute(null).Should().BeFalse();
        vm.Username = "admin";
        vm.LoginCommand.CanExecute(null).Should().BeFalse();
        vm.Password = "secret";
        vm.LoginCommand.CanExecute(null).Should().BeTrue();
    }

    [Fact]
    public async Task SuccessfulLoginRaisesLoggedIn()
    {
        var api = new FakeAdminApi { LoginHandler = (u, _) => Data.Tokens(u) };
        var vm = new LoginViewModel(api) { Username = "admin", Password = "secret" };
        UserInfo? loggedIn = null;
        vm.LoggedIn += u => loggedIn = u;

        await vm.LoginCommand.ExecuteAsync(null);

        loggedIn.Should().NotBeNull();
        loggedIn!.Username.Should().Be("admin");
        vm.Error.Should().BeNull();
        vm.Password.Should().BeEmpty();
    }

    [Fact]
    public async Task FailedLoginShowsFriendlyError()
    {
        var vm = new LoginViewModel(new FakeAdminApi()) { Username = "admin", Password = "wrong" };
        await vm.LoginCommand.ExecuteAsync(null);
        vm.Error.Should().Be("Invalid username or password");
    }
}

public class DashboardViewModelTests
{
    private static (DashboardViewModel vm, FakeAdminApi api, FakeRealtimeClient rt) Build()
    {
        var api = new FakeAdminApi
        {
            Summary = new DashboardSummary(3, 1, 1, 0, 1, 3, 2, 0, DateTimeOffset.UnixEpoch),
        };
        api.Devices.Add(Data.Device(1, 101, "ACTIVE"));
        api.Devices.Add(Data.Device(2, 102, "IDLE"));
        api.Devices.Add(Data.Device(3, 103, "OFFLINE"));
        var rt = new FakeRealtimeClient();
        var vm = new DashboardViewModel(api, rt, new ImmediateDispatcher());
        vm.Attach();
        return (vm, api, rt);
    }

    [Fact]
    public async Task LoadPopulatesCountsAndRows()
    {
        var (vm, _, _) = Build();
        await vm.LoadAsync();

        vm.Devices.Should().HaveCount(3);
        vm.TotalEmployeesDisplay.Should().Be(3);
        vm.Online.Should().Be(1);
        vm.Idle.Should().Be(1);
        vm.Locked.Should().Be(0);
        vm.Offline.Should().Be(1);
        vm.OnlineDevices.Should().Be(2);
    }

    [Fact]
    public async Task RealtimeDeltaUpdatesRowAndRecomputesCounts()
    {
        var (vm, _, rt) = Build();
        await vm.LoadAsync();

        // Employee 103's offline device comes online as ACTIVE.
        rt.Emit(new PresenceEvent("DEVICE_ONLINE", 3, 103, "ACTIVE", "OFFLINE", DateTimeOffset.UtcNow));

        var row = vm.Devices.First(d => d.DeviceId == 3);
        row.Status.Should().Be("ACTIVE");
        row.Online.Should().BeTrue();
        vm.Online.Should().Be(2);
        vm.Offline.Should().Be(0);
        vm.OnlineDevices.Should().Be(3);
    }

    [Fact]
    public async Task StatusChangeToLockedIsReflected()
    {
        var (vm, _, rt) = Build();
        await vm.LoadAsync();

        rt.Emit(new PresenceEvent("DEVICE_STATUS_CHANGED", 1, 101, "LOCKED", "ACTIVE", DateTimeOffset.UtcNow));

        vm.Devices.First(d => d.DeviceId == 1).Status.Should().Be("LOCKED");
        vm.Online.Should().Be(0);
        vm.Locked.Should().Be(1);
    }
}

public class EmployeeDetailViewModelTests
{
    [Fact]
    public async Task LoadsEmployeeDevicesAndAllMonitoringCategories()
    {
        var api = new FakeAdminApi();
        api.Employees.Add(new EmployeeDto(7, "EMP-7", "Rahul Kumar", "r@x.com",
            new DepartmentDto(1, "Engineering"), "Engineer", "ACTIVE"));
        api.Devices.Add(Data.Device(1, 7, "ACTIVE"));
        api.ApplicationsReport = new UsageReport(7, "2026-10-06", "UTC", 4500, new List<UsageSlice>
        {
            new("Visual Studio Code", 3600, 2), new("Google Chrome", 900, 1),
        });
        api.Usb.Add(new UsbEventDto(1, "CONNECTED", "USB Mass Storage", "0951", "1666", "E:", "Kingston", DateTimeOffset.UnixEpoch));
        api.Network.Add(new NetworkEventDto(1, "CONNECTED", "ETHERNET", "CORP", "10.0.0.5", DateTimeOffset.UnixEpoch));
        api.Zscaler = new ZscalerStatusDto("CONNECTED", DateTimeOffset.UnixEpoch,
            new List<ZscalerEventDto> { new(1, "CONNECTED", null, DateTimeOffset.UnixEpoch) });
        api.Screenshots.Add(new ScreenshotMetadataDto(99, DateTimeOffset.UnixEpoch, "image/jpeg", 1920, 1080, 54321));
        api.ScreenshotBytes = new byte[] { 9, 8, 7, 6 };

        var vm = new EmployeeDetailViewModel(api);
        vm.Initialize(7);
        await vm.LoadAsync();

        vm.Title.Should().Be("Rahul Kumar");
        vm.Devices.Should().HaveCount(1);
        vm.ApplicationsTotalSeconds.Should().Be(4500);
        vm.Applications.Should().HaveCount(2);
        vm.Applications[0].Label.Should().Be("Visual Studio Code");
        vm.UsbEvents.Should().ContainSingle();
        vm.NetworkEvents.Should().ContainSingle();
        vm.ZscalerCurrent.Should().Be("CONNECTED");
        vm.ZscalerHistory.Should().ContainSingle();
        vm.Screenshots.Should().ContainSingle(s => s.Id == 99);
        (await vm.GetScreenshotBytesAsync(99)).Should().Equal(9, 8, 7, 6);
        vm.Error.Should().BeNull();
    }
}

public class LiveViewViewModelTests
{
    private static LiveViewSessionDto Session() =>
        new(Guid.NewGuid(), 1, "DEV-10001", new EmployeeSummaryDto(7, "EMP-7", "Rahul Kumar", "Engineering"),
            "PENDING", DateTimeOffset.UnixEpoch, 1800, "/ws/live-view");

    [Fact]
    public async Task StartConnectsAndRendersFrames()
    {
        var api = new FakeAdminApi { LiveViewToStart = Session() };
        var signaling = new FakeLiveViewSignaling();
        var vm = new LiveViewViewModel(api, signaling, new ImmediateDispatcher());
        byte[]? rendered = null;
        vm.FrameUpdated += b => rendered = b;

        await vm.StartAsync(1);

        vm.IsLive.Should().BeTrue();
        vm.EmployeeName.Should().Be("Rahul Kumar");
        signaling.StartedSession.Should().Be(vm.SessionId);

        signaling.EmitFrame(new byte[] { 1, 2, 3 });
        rendered.Should().Equal(1, 2, 3);
        vm.FrameCount.Should().Be(1);
        vm.StatusText.Should().Be("Live");
    }

    [Fact]
    public async Task ServerStopEndsTheSession()
    {
        var api = new FakeAdminApi { LiveViewToStart = Session() };
        var signaling = new FakeLiveViewSignaling();
        var vm = new LiveViewViewModel(api, signaling, new ImmediateDispatcher());
        await vm.StartAsync(1);

        signaling.EmitStopped("TIMEOUT");
        vm.IsLive.Should().BeFalse();
        vm.StatusText.Should().Contain("TIMEOUT");
    }

    [Fact]
    public async Task StopCommandCallsApiAndSignaling()
    {
        var api = new FakeAdminApi { LiveViewToStart = Session() };
        var signaling = new FakeLiveViewSignaling();
        var vm = new LiveViewViewModel(api, signaling, new ImmediateDispatcher());
        await vm.StartAsync(1);

        await vm.StopCommand.ExecuteAsync(null);
        api.StopLiveViewCalls.Should().Be(1);
        signaling.Stopping.Should().BeTrue();
        vm.IsLive.Should().BeFalse();
    }

    [Fact]
    public async Task ForbiddenShowsError()
    {
        var vm = new LiveViewViewModel(new FakeAdminApi(), new FakeLiveViewSignaling(), new ImmediateDispatcher());
        await vm.StartAsync(1);
        vm.IsLive.Should().BeFalse();
        vm.Error.Should().Contain("disabled by policy");
    }

    [Fact]
    public async Task LoadingControlPolicyReflectsConsentAndDoesNotSave()
    {
        var api = new FakeAdminApi();
        var vm = new LiveViewViewModel(api, new FakeLiveViewSignaling(), new ImmediateDispatcher());

        await vm.LoadControlPolicyAsync(1);

        vm.ControlPolicyLoaded.Should().BeTrue();
        vm.UnattendedControl.Should().BeFalse();        // default policy requires consent
        api.LastDevicePolicySaved.Should().BeNull();    // merely loading must not write an override
    }

    [Fact]
    public async Task EnablingUnattendedControlWritesDeviceOverride()
    {
        var api = new FakeAdminApi();
        var vm = new LiveViewViewModel(api, new FakeLiveViewSignaling(), new ImmediateDispatcher());
        await vm.LoadControlPolicyAsync(1);

        vm.UnattendedControl = true;                    // admin opts this one device into unattended control

        api.LastDevicePolicySaved.Should().NotBeNull();
        api.LastDevicePolicySaved!.Value.deviceId.Should().Be(1);
        api.LastDevicePolicySaved.Value.settings.RequireControlConsent.Should().BeFalse();
    }
}

public class EmployeesViewModelTests
{
    [Fact]
    public async Task PagingEnablesAndDisablesCommands()
    {
        var api = new FakeAdminApi();
        for (int i = 1; i <= 120; i++)
        {
            api.Employees.Add(new EmployeeDto(i, $"E{i}", $"Person {i}", $"p{i}@x.com", null, null, "ACTIVE"));
        }
        var vm = new EmployeesViewModel(api);
        await vm.LoadAsync();

        vm.Employees.Should().HaveCount(50);
        vm.TotalElements.Should().Be(120);
        vm.TotalPages.Should().Be(3);
        vm.PrevPageCommand.CanExecute(null).Should().BeFalse();
        vm.NextPageCommand.CanExecute(null).Should().BeTrue();

        await vm.NextPageCommand.ExecuteAsync(null);
        vm.Page.Should().Be(1);
        vm.PrevPageCommand.CanExecute(null).Should().BeTrue();
    }

    [Fact]
    public async Task SearchResetsToFirstPage()
    {
        var api = new FakeAdminApi();
        api.Employees.Add(new EmployeeDto(1, "E1", "Rahul Kumar", "r@x.com", null, null, "ACTIVE"));
        api.Employees.Add(new EmployeeDto(2, "E2", "Priya Sharma", "p@x.com", null, null, "ACTIVE"));
        var vm = new EmployeesViewModel(api) { Page = 2, SearchText = "Rahul" };

        await vm.SearchCommand.ExecuteAsync(null);

        vm.Page.Should().Be(0);
        vm.Employees.Should().ContainSingle(e => e.FullName == "Rahul Kumar");
    }

    [Fact]
    public async Task CreateEmployeeReturnsCreatedAndClearsError()
    {
        var api = new FakeAdminApi();
        var vm = new EmployeesViewModel(api);

        var created = await vm.CreateEmployeeAsync(new CreateEmployeeInput("SUJAY", "Sujay", "sujay@x.com"));

        created.Should().NotBeNull();
        created!.EmployeeCode.Should().Be("SUJAY");
        api.CreateEmployeeCalls.Should().Be(1);
        vm.Error.Should().BeNull();
    }

    [Fact]
    public async Task CreateEmployeeSurfacesApiErrorAndReturnsNull()
    {
        var api = new FakeAdminApi
        {
            CreateEmployeeHandler = _ => throw new ApiException("Employee code already in use",
                System.Net.HttpStatusCode.Conflict, "CONFLICT"),
        };
        var vm = new EmployeesViewModel(api);

        var created = await vm.CreateEmployeeAsync(new CreateEmployeeInput("DUP", "Dup", "d@x.com"));

        created.Should().BeNull();
        vm.Error.Should().Be("Employee code already in use");
    }
}

public class PolicyViewModelTests
{
    [Fact]
    public async Task LoadMapsSettingsIntoEditableFields()
    {
        var api = new FakeAdminApi();
        var vm = new PolicyViewModel(api);

        await vm.LoadAsync();

        vm.LiveView.Should().BeFalse();
        vm.SessionMonitoring.Should().BeTrue();
        vm.IdleThresholdSeconds.Should().Be(300);
        vm.ScreenshotIntervalMinutes.Should().Be(15);      // 900s -> 15 min
        vm.WorkMonday.Should().BeTrue();
        vm.WorkSunday.Should().BeFalse();
        vm.Error.Should().BeNull();
    }

    [Fact]
    public async Task SaveSendsEditedSettings()
    {
        var api = new FakeAdminApi();
        var vm = new PolicyViewModel(api);
        await vm.LoadAsync();

        vm.LiveView = true;
        vm.ScreenshotIntervalMinutes = 30;   // -> 1800s

        await vm.SaveAsync();

        api.LastSavedPolicy.Should().NotBeNull();
        api.LastSavedPolicy!.LiveView.Should().BeTrue();
        api.LastSavedPolicy.ScreenshotIntervalSeconds.Should().Be(1800);
        vm.StatusMessage.Should().NotBeNull();
        vm.Error.Should().BeNull();
    }

    [Fact]
    public async Task SavePersistsRequireControlConsentToggle()
    {
        var api = new FakeAdminApi();
        var vm = new PolicyViewModel(api);
        await vm.LoadAsync();
        vm.RequireControlConsent.Should().BeTrue();   // safe default comes back from the server

        vm.RequireControlConsent = false;             // admin opts the default policy into unattended control
        await vm.SaveAsync();

        api.LastSavedPolicy.Should().NotBeNull();
        api.LastSavedPolicy!.RequireControlConsent.Should().BeFalse();
        vm.Error.Should().BeNull();
    }

    [Fact]
    public async Task SaveWithNoWorkDaysIsRejectedLocally()
    {
        var api = new FakeAdminApi();
        var vm = new PolicyViewModel(api);
        await vm.LoadAsync();

        vm.WorkMonday = vm.WorkTuesday = vm.WorkWednesday = false;
        vm.WorkThursday = vm.WorkFriday = vm.WorkSaturday = vm.WorkSunday = false;

        await vm.SaveAsync();

        vm.Error.Should().Be("Select at least one work day.");
        api.LastSavedPolicy.Should().BeNull();   // never hit the server
    }
}

public class WebAccessViewModelTests
{
    private static WebPolicyDto Policy(long id, string name, params (string action, string domain)[] rules) =>
        new(id, name, null, true, 100, null, null, 1,
            rules.Select(r => new WebRuleDto(null, r.domain, null, r.action)).ToList(),
            0, DateTimeOffset.UnixEpoch, DateTimeOffset.UnixEpoch);

    private static (WebAccessViewModel vm, FakeAdminApi api, FakeRealtimeClient rt) Build()
    {
        var api = new FakeAdminApi();
        var rt = new FakeRealtimeClient();
        var vm = new WebAccessViewModel(api, rt, new ImmediateDispatcher());
        return (vm, api, rt);
    }

    [Fact]
    public async Task LoadPoliciesSplitsRulesByAction()
    {
        var (vm, api, _) = Build();
        api.WebPolicies.Add(Policy(1, "No streaming",
            ("BLOCK", "youtube.com"), ("BLOCK", "netflix.com"), ("ALLOW", "learning.youtube.com"),
            ("MONITOR", "reddit.com")));

        await vm.LoadPoliciesAsync();

        vm.HasPolicies.Should().BeTrue();
        var row = vm.Policies.Should().ContainSingle().Subject;
        row.BlockDomains.Should().BeEquivalentTo("youtube.com", "netflix.com");
        row.AllowDomains.Should().BeEquivalentTo("learning.youtube.com");
        row.MonitorDomains.Should().BeEquivalentTo("reddit.com");
        row.RuleSummary.Should().Be("2 blocked · 1 allowed · 1 monitored");
    }

    [Fact]
    public async Task SavePolicyCreatesWhenNoId()
    {
        var (vm, api, _) = Build();
        var input = new WebPolicyInput("Block social", null, true, 200, false,
            new[] { new WebRuleDto(null, "facebook.com", null, "BLOCK") });

        var ok = await vm.SavePolicyAsync(null, input);

        ok.Should().BeTrue();
        api.LastCreated.Should().Be(input);
        vm.Policies.Should().ContainSingle(p => p.Name == "Block social");
    }

    [Fact]
    public async Task LoadActivityPopulatesSummaryAndEvents()
    {
        var (vm, api, _) = Build();
        api.WebSummary = new WebAccessSummaryDto(10, 4, 3, 2, 1,
            new[] { new DomainCountDto("youtube.com", 3L) });
        api.WebEvents.Add(new WebAccessEventDto(1, 5, "DEV-1", 7, "Rahul", "youtube.com", null,
            "ACCESS_BLOCKED", "BLOCKED", 1, "DNS_LOG", DateTimeOffset.UnixEpoch));

        await vm.LoadActivityAsync();

        vm.Observed.Should().Be(10);
        vm.Blocked.Should().Be(3);
        vm.TopBlocked.Should().ContainSingle(d => d.Domain == "youtube.com");
        vm.Events.Should().ContainSingle();
        vm.Events[0].ResultLabel.Should().Be("Blocked");
        vm.Events[0].IsBlocked.Should().BeTrue();
    }

    [Fact]
    public void LiveBlockedEventPrependsAndBumpsCount()
    {
        var (vm, _, rt) = Build();
        vm.Start();

        rt.EmitWebEvent(new WebAccessEventDto(0, 5, "DEV-1", null, null, "tiktok.com", null,
            "ACCESS_BLOCKED", "BLOCKED", null, null, DateTimeOffset.UnixEpoch));

        vm.Events.Should().ContainSingle();
        vm.Events[0].Domain.Should().Be("tiktok.com");
        vm.Blocked.Should().Be(1);
        vm.Observed.Should().Be(1);
        vm.HasEvents.Should().BeTrue();
    }
}
