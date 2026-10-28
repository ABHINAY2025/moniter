using System.Security.Cryptography;
using System.Text.Json;
using EmployeeMonitor.Agent.Api;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Agent;

/// <summary>
/// Checks for a newer approved agent build and, when one is available, downloads it, verifies its SHA-256 and RSA
/// signature against the embedded public key, and stages it for the installer/service to apply. An update is only
/// ever written to disk after both checks pass, so an unsigned or tampered package is never installed.
/// </summary>
public sealed class AgentUpdater
{
    private readonly IBackendApi _api;
    private readonly string _currentVersion;
    private readonly string? _publicKeyBase64;
    private readonly string _stageDirectory;
    private readonly ILogger _logger;

    public AgentUpdater(IBackendApi api, string currentVersion, string? publicKeyBase64, string stageDirectory,
        ILogger logger)
    {
        _api = api;
        _currentVersion = currentVersion;
        _publicKeyBase64 = publicKeyBase64;
        _stageDirectory = stageDirectory;
        _logger = logger;
    }

    /// <summary>Updates are only attempted when an update public key is configured.</summary>
    public bool Enabled => !string.IsNullOrWhiteSpace(_publicKeyBase64);

    /// <summary>True only when the bytes match the expected SHA-256 and the signature verifies under the public key.</summary>
    public bool VerifyPackage(byte[] bytes, string expectedSha256Hex, string signatureBase64)
    {
        if (!Sha256Hex(bytes).Equals(expectedSha256Hex, StringComparison.OrdinalIgnoreCase))
        {
            return false;
        }
        if (string.IsNullOrWhiteSpace(_publicKeyBase64))
        {
            return false;
        }
        try
        {
            using var rsa = RSA.Create();
            rsa.ImportSubjectPublicKeyInfo(Convert.FromBase64String(_publicKeyBase64), out _);
            return rsa.VerifyData(bytes, Convert.FromBase64String(signatureBase64),
                HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
        }
        catch (Exception)
        {
            return false;
        }
    }

    /// <summary>Checks, downloads, verifies and stages an update. Returns true when one was staged.</summary>
    public async Task<bool> CheckAndStageAsync(string deviceToken, CancellationToken ct)
    {
        if (!Enabled)
        {
            return false;
        }
        UpdateAvailable? update;
        try
        {
            update = await _api.CheckUpdateAsync(_currentVersion, deviceToken, ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            _logger.LogDebug("Update check failed: {Message}", ex.Message);
            return false;
        }
        if (update is null)
        {
            return false;
        }
        _logger.LogInformation("Agent update {Version} available (current {Current})", update.Version, _currentVersion);

        byte[] bytes = await _api.DownloadUpdateAsync(update.DownloadPath, deviceToken, ct);
        if (!VerifyPackage(bytes, update.Sha256, update.Signature))
        {
            _logger.LogError("Rejected update {Version}: SHA-256 or signature verification failed", update.Version);
            return false;
        }

        Directory.CreateDirectory(_stageDirectory);
        string packagePath = Path.Combine(_stageDirectory, $"agent-{update.Version}.pkg");
        await File.WriteAllBytesAsync(packagePath, bytes, ct);
        await File.WriteAllTextAsync(Path.Combine(_stageDirectory, "pending-update.json"),
            JsonSerializer.Serialize(new { update.Version, package = packagePath, update.Mandatory }), ct);

        _logger.LogInformation("Verified and staged update {Version} at {Path}; it will be applied by the installer.",
            update.Version, packagePath);
        return true;
    }

    private static string Sha256Hex(byte[] bytes)
    {
        return Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
    }
}
