using System.Runtime.Versioning;
using System.Security.Cryptography;
using System.Text;
using EmployeeMonitor.Agent.Platform;
using Microsoft.Extensions.Logging;

namespace EmployeeMonitor.Sessions;

/// <summary>
/// Stores the device credential encrypted with DPAPI at machine scope, so only code on this machine can read it.
/// The file lives under %ProgramData% and is written atomically.
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class DpapiSecureStore : ISecureStore
{
    // Extra entropy bound into the ciphertext; not a secret, just domain separation.
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("EmployeeMonitor.Agent.v1");

    private readonly string _path;
    private readonly ILogger<DpapiSecureStore> _logger;

    public DpapiSecureStore(string dataDirectory, ILogger<DpapiSecureStore> logger)
    {
        Directory.CreateDirectory(dataDirectory);
        _path = Path.Combine(dataDirectory, "device.dat");
        _logger = logger;
    }

    public void Save(string plaintext)
    {
        byte[] protectedBytes = ProtectedData.Protect(
            Encoding.UTF8.GetBytes(plaintext), Entropy, DataProtectionScope.LocalMachine);
        string tmp = _path + ".tmp";
        File.WriteAllBytes(tmp, protectedBytes);
        File.Move(tmp, _path, overwrite: true);
    }

    public string? Load()
    {
        if (!File.Exists(_path))
        {
            return null;
        }
        try
        {
            byte[] protectedBytes = File.ReadAllBytes(_path);
            byte[] plain = ProtectedData.Unprotect(protectedBytes, Entropy, DataProtectionScope.LocalMachine);
            return Encoding.UTF8.GetString(plain);
        }
        catch (CryptographicException ex)
        {
            _logger.LogError(ex, "Could not decrypt device credential at {Path}", _path);
            return null;
        }
    }

    public void Clear()
    {
        if (File.Exists(_path))
        {
            File.Delete(_path);
        }
    }
}
