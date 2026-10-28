using System.Text.Json;
using EmployeeMonitor.Agent.Platform;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Agent.Configuration;

/// <summary>Persisted device identity and credential. Stored encrypted via <see cref="ISecureStore"/>.</summary>
public sealed record DeviceCredential(
    long DeviceId,
    string DeviceCode,
    string DeviceToken,
    DateTimeOffset? TokenExpiresAt,
    long? EmployeeId,
    string? HardwareId);

/// <summary>Reads and writes the <see cref="DeviceCredential"/> through the encrypted store.</summary>
public sealed class CredentialStore
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);

    private readonly ISecureStore _store;
    private readonly ILogger<CredentialStore> _logger;

    public CredentialStore(ISecureStore store, ILogger<CredentialStore> logger)
    {
        _store = store;
        _logger = logger;
    }

    public DeviceCredential? Load()
    {
        var raw = _store.Load();
        if (string.IsNullOrEmpty(raw))
        {
            return null;
        }
        try
        {
            return JsonSerializer.Deserialize<DeviceCredential>(raw, Json);
        }
        catch (JsonException ex)
        {
            _logger.LogError(ex, "Stored device credential is corrupt; re-enrollment required");
            return null;
        }
    }

    public void Save(DeviceCredential credential) =>
        _store.Save(JsonSerializer.Serialize(credential, Json));

    public void Clear() => _store.Clear();
}
