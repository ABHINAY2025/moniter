using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>
/// Drives the Web access page: the policy list (create/edit/delete/assign), the activity feed with its summary,
/// and live blocked-access events from the realtime stream. All backend shapes are domain-level only.
/// </summary>
public partial class WebAccessViewModel : ObservableObject
{
    private const int MaxEventRows = 200;

    private readonly IAdminApi _api;
    private readonly IRealtimeClient _realtime;
    private readonly IUiDispatcher _ui;
    private bool _subscribed;

    public WebAccessViewModel(IAdminApi api, IRealtimeClient realtime, IUiDispatcher ui)
    {
        _api = api;
        _realtime = realtime;
        _ui = ui;
    }

    public ObservableCollection<WebPolicyRowViewModel> Policies { get; } = new();
    public ObservableCollection<WebEventRowViewModel> Events { get; } = new();
    public ObservableCollection<DomainCountDto> TopBlocked { get; } = new();

    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private bool _hasPolicies;
    [ObservableProperty] private bool _hasEvents;

    [ObservableProperty] private long _observed;
    [ObservableProperty] private long _allowed;
    [ObservableProperty] private long _blocked;
    [ObservableProperty] private long _monitored;
    [ObservableProperty] private long _enforcementErrors;

    public void Start()
    {
        if (!_subscribed)
        {
            _realtime.WebAccessEventReceived += OnWebEvent;
            _subscribed = true;
        }
    }

    public void Stop()
    {
        if (_subscribed)
        {
            _realtime.WebAccessEventReceived -= OnWebEvent;
            _subscribed = false;
        }
    }

    [RelayCommand]
    public async Task LoadPoliciesAsync()
    {
        IsLoading = true;
        Error = null;
        try
        {
            var result = await _api.GetWebPoliciesAsync(null, 0, 100);
            Policies.Clear();
            foreach (var p in result.Content)
            {
                Policies.Add(new WebPolicyRowViewModel(p));
            }
            HasPolicies = Policies.Count > 0;
        }
        catch (Exception ex)
        {
            Error = Describe(ex, "Could not load web policies");
        }
        finally
        {
            IsLoading = false;
        }
    }

    [RelayCommand]
    public async Task LoadActivityAsync()
    {
        Error = null;
        try
        {
            var summary = await _api.GetWebAccessSummaryAsync(7);
            Observed = summary.Observed;
            Allowed = summary.Allowed;
            Blocked = summary.Blocked;
            Monitored = summary.Monitored;
            EnforcementErrors = summary.EnforcementErrors;
            TopBlocked.Clear();
            foreach (var d in summary.TopBlocked)
            {
                TopBlocked.Add(d);
            }

            var events = await _api.GetWebAccessEventsAsync(null, null, null, 0, MaxEventRows);
            Events.Clear();
            foreach (var e in events.Content)
            {
                Events.Add(new WebEventRowViewModel(e));
            }
            HasEvents = Events.Count > 0;
        }
        catch (Exception ex)
        {
            Error = Describe(ex, "Could not load web activity");
        }
    }

    /// <summary>Creates (id null) or updates a policy. Returns true on success and refreshes the list.</summary>
    public async Task<bool> SavePolicyAsync(long? id, WebPolicyInput input)
    {
        Error = null;
        try
        {
            if (id is { } existing)
            {
                await _api.UpdateWebPolicyAsync(existing, input);
            }
            else
            {
                await _api.CreateWebPolicyAsync(input);
            }
            await LoadPoliciesAsync();
            return true;
        }
        catch (Exception ex)
        {
            Error = Describe(ex, "Could not save the policy");
            return false;
        }
    }

    [RelayCommand]
    public async Task DeletePolicyAsync(WebPolicyRowViewModel? row)
    {
        if (row is null)
        {
            return;
        }
        Error = null;
        try
        {
            await _api.DeleteWebPolicyAsync(row.Id);
            Policies.Remove(row);
            HasPolicies = Policies.Count > 0;
        }
        catch (Exception ex)
        {
            Error = Describe(ex, "Could not delete the policy");
        }
    }

    public async Task<IReadOnlyList<WebAssignmentDto>> LoadAssignmentsAsync(long policyId)
    {
        try
        {
            return await _api.GetWebPolicyAssignmentsAsync(policyId);
        }
        catch (Exception ex)
        {
            Error = Describe(ex, "Could not load assignments");
            return Array.Empty<WebAssignmentDto>();
        }
    }

    public async Task<WebAssignmentDto?> AssignAsync(long policyId, string targetType, long targetId)
    {
        Error = null;
        try
        {
            var created = await _api.AssignWebPolicyAsync(policyId, targetType, targetId);
            await LoadPoliciesAsync();
            return created;
        }
        catch (Exception ex)
        {
            Error = Describe(ex, "Could not assign the policy");
            return null;
        }
    }

    public async Task<bool> UnassignAsync(long policyId, long assignmentId)
    {
        Error = null;
        try
        {
            await _api.UnassignWebPolicyAsync(policyId, assignmentId);
            await LoadPoliciesAsync();
            return true;
        }
        catch (Exception ex)
        {
            Error = Describe(ex, "Could not remove the assignment");
            return false;
        }
    }

    public Task<IReadOnlyList<EmployeeDto>> GetEmployeesAsync() => FetchAll(
        (p, s) => _api.GetEmployeesAsync(null, p, s));

    public Task<IReadOnlyList<DeviceDto>> GetDevicesAsync() => FetchAll(
        (p, s) => _api.GetDevicesAsync(null, p, s));

    private static async Task<IReadOnlyList<T>> FetchAll<T>(Func<int, int, Task<PageResponse<T>>> fetch)
    {
        var first = await fetch(0, 200);
        var items = new List<T>(first.Content);
        for (int page = 1; page < first.TotalPages; page++)
        {
            items.AddRange((await fetch(page, 200)).Content);
        }
        return items;
    }

    private void OnWebEvent(WebAccessEventDto e) => _ui.Post(() =>
    {
        Events.Insert(0, new WebEventRowViewModel(e));
        while (Events.Count > MaxEventRows)
        {
            Events.RemoveAt(Events.Count - 1);
        }
        HasEvents = true;
        if (e.EnforcementResult == "BLOCKED" || e.EventType == "ACCESS_BLOCKED")
        {
            Blocked++;
        }
        Observed++;
    });

    private static string Describe(Exception ex, string fallback) =>
        ex is ApiException ? ex.Message : $"{fallback}: {ex.Message}";
}
