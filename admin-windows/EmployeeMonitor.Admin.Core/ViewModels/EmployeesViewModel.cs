using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

/// <summary>Paged, searchable employee list (results already scoped server-side to what the user may see).</summary>
public partial class EmployeesViewModel : ObservableObject
{
    private const int PageSize = 50;
    private readonly IAdminApi _api;

    public EmployeesViewModel(IAdminApi api)
    {
        _api = api;
    }

    public ObservableCollection<EmployeeDto> Employees { get; } = new();

    [ObservableProperty] private string _searchText = "";
    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private string? _error;
    [ObservableProperty] private int _page;
    [ObservableProperty] private int _totalPages;
    [ObservableProperty] private long _totalElements;

    [RelayCommand]
    public async Task LoadAsync()
    {
        IsLoading = true;
        Error = null;
        try
        {
            var result = await _api.GetEmployeesAsync(SearchText, Page, PageSize);
            Employees.Clear();
            foreach (var e in result.Content)
            {
                Employees.Add(e);
            }
            TotalPages = result.TotalPages;
            TotalElements = result.TotalElements;
        }
        catch (Exception ex)
        {
            Error = ex is ApiException ? ex.Message : "Could not load employees: " + ex.Message;
        }
        finally
        {
            IsLoading = false;
        }
    }

    [RelayCommand]
    private Task SearchAsync()
    {
        Page = 0;
        return LoadAsync();
    }

    [RelayCommand(CanExecute = nameof(CanNext))]
    private Task NextPageAsync()
    {
        Page++;
        return LoadAsync();
    }

    [RelayCommand(CanExecute = nameof(CanPrev))]
    private Task PrevPageAsync()
    {
        Page--;
        return LoadAsync();
    }

    private bool CanNext() => Page + 1 < TotalPages;
    private bool CanPrev() => Page > 0;

    partial void OnPageChanged(int value)
    {
        NextPageCommand.NotifyCanExecuteChanged();
        PrevPageCommand.NotifyCanExecuteChanged();
    }

    partial void OnTotalPagesChanged(int value)
    {
        NextPageCommand.NotifyCanExecuteChanged();
        PrevPageCommand.NotifyCanExecuteChanged();
    }
}
