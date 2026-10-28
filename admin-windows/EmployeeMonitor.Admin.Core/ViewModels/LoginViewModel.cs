using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core.ViewModels;

public partial class LoginViewModel : ObservableObject
{
    private readonly IAdminApi _api;

    public LoginViewModel(IAdminApi api)
    {
        _api = api;
    }

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(LoginCommand))]
    private string _username = "";

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(LoginCommand))]
    private string _password = "";

    [ObservableProperty]
    [NotifyCanExecuteChangedFor(nameof(LoginCommand))]
    private bool _isBusy;

    [ObservableProperty]
    private string? _error;

    /// <summary>True when there is an error to display (used instead of a value converter at window scope).</summary>
    public bool HasError => !string.IsNullOrWhiteSpace(Error);

    partial void OnErrorChanged(string? value) => OnPropertyChanged(nameof(HasError));

    /// <summary>Raised after a successful login so the shell can navigate on.</summary>
    public event Action<UserInfo>? LoggedIn;

    private bool CanLogin() => !IsBusy && !string.IsNullOrWhiteSpace(Username) && !string.IsNullOrWhiteSpace(Password);

    [RelayCommand(CanExecute = nameof(CanLogin))]
    private async Task LoginAsync()
    {
        IsBusy = true;
        Error = null;
        try
        {
            var tokens = await _api.LoginAsync(Username.Trim(), Password);
            Password = "";
            LoggedIn?.Invoke(tokens.User);
        }
        catch (ApiException ex)
        {
            Error = ex.Message;
        }
        catch (Exception ex)
        {
            Error = "Sign-in failed: " + ex.Message;
        }
        finally
        {
            IsBusy = false;
        }
    }
}
