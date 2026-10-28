using EmployeeMonitor.Admin.Core.Api;

namespace EmployeeMonitor.Admin.Core;

/// <summary>Holds the current tokens and signed-in user for the lifetime of the app session (in memory only).</summary>
public sealed class SessionState
{
    public string? AccessToken { get; private set; }
    public string? RefreshToken { get; private set; }
    public DateTimeOffset AccessTokenExpiresAt { get; private set; }
    public UserInfo? User { get; private set; }

    public bool IsAuthenticated => AccessToken is not null && User is not null;

    public event Action? Changed;

    public void Set(TokenResponse tokens)
    {
        AccessToken = tokens.AccessToken;
        RefreshToken = tokens.RefreshToken;
        AccessTokenExpiresAt = tokens.AccessTokenExpiresAt;
        User = tokens.User;
        Changed?.Invoke();
    }

    public void UpdateTokens(TokenResponse tokens)
    {
        AccessToken = tokens.AccessToken;
        RefreshToken = tokens.RefreshToken;
        AccessTokenExpiresAt = tokens.AccessTokenExpiresAt;
        if (tokens.User is not null)
        {
            User = tokens.User;
        }
    }

    public void Clear()
    {
        AccessToken = RefreshToken = null;
        User = null;
        Changed?.Invoke();
    }
}
