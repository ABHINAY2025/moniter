using System.Security.Cryptography;
using EmployeeMonitor.Agent;
using EmployeeMonitor.Agent.Api;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace EmployeeMonitor.Agent.Tests;

public class AgentUpdaterTests
{
    private static string Sha256Hex(byte[] bytes) => Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();

    private static (RSA rsa, string publicKeyBase64) NewKey()
    {
        var rsa = RSA.Create(2048);
        return (rsa, Convert.ToBase64String(rsa.ExportSubjectPublicKeyInfo()));
    }

    private static string Sign(RSA rsa, byte[] data) =>
        Convert.ToBase64String(rsa.SignData(data, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1));

    [Fact]
    public void VerifiesCorrectlySignedPackage()
    {
        var (rsa, pub) = NewKey();
        var updater = new AgentUpdater(new FakeBackendApi(), "1.0.0", pub, Path.GetTempPath(), NullLogger.Instance);
        var pkg = new byte[] { 1, 2, 3, 4, 5 };

        updater.VerifyPackage(pkg, Sha256Hex(pkg), Sign(rsa, pkg)).Should().BeTrue();
    }

    [Fact]
    public void RejectsTamperedBytesWrongHashAndWrongKey()
    {
        var (rsa, pub) = NewKey();
        var updater = new AgentUpdater(new FakeBackendApi(), "1.0.0", pub, Path.GetTempPath(), NullLogger.Instance);
        var pkg = new byte[] { 1, 2, 3, 4, 5 };
        var sig = Sign(rsa, pkg);

        // Tampered bytes (valid sig for the original) -> fails.
        updater.VerifyPackage(new byte[] { 9, 9, 9 }, Sha256Hex(pkg), sig).Should().BeFalse();
        // Wrong declared hash -> fails before signature check.
        updater.VerifyPackage(pkg, "deadbeef", sig).Should().BeFalse();
        // Signature from a different key -> fails.
        var (other, _) = NewKey();
        updater.VerifyPackage(pkg, Sha256Hex(pkg), Sign(other, pkg)).Should().BeFalse();
    }

    [Fact]
    public void DisabledWhenNoPublicKey()
    {
        var updater = new AgentUpdater(new FakeBackendApi(), "1.0.0", null, Path.GetTempPath(), NullLogger.Instance);
        updater.Enabled.Should().BeFalse();
    }

    [Fact]
    public async Task StagesAVerifiedUpdateAndSkipsAnInvalidOne()
    {
        var (rsa, pub) = NewKey();
        var stageDir = Path.Combine(Path.GetTempPath(), "em-upd-" + Guid.NewGuid().ToString("N"));
        var pkg = new byte[] { 10, 20, 30, 40 };

        var api = new FakeBackendApi { UpdatePackage = pkg };
        var updater = new AgentUpdater(api, "1.0.0", pub, stageDir, NullLogger.Instance);

        // Valid signature -> staged.
        api.Update = new UpdateAvailable("1.2.0", Sha256Hex(pkg), Sign(rsa, pkg), false, pkg.Length,
            "/api/agent/update/1.2.0/package");
        (await updater.CheckAndStageAsync("dvt_token", CancellationToken.None)).Should().BeTrue();
        File.Exists(Path.Combine(stageDir, "agent-1.2.0.pkg")).Should().BeTrue();
        File.Exists(Path.Combine(stageDir, "pending-update.json")).Should().BeTrue();

        // Bad signature -> not staged.
        var (other, _) = NewKey();
        api.Update = new UpdateAvailable("1.3.0", Sha256Hex(pkg), Sign(other, pkg), false, pkg.Length,
            "/api/agent/update/1.3.0/package");
        (await updater.CheckAndStageAsync("dvt_token", CancellationToken.None)).Should().BeFalse();
        File.Exists(Path.Combine(stageDir, "agent-1.3.0.pkg")).Should().BeFalse();

        try { Directory.Delete(stageDir, true); } catch { /* best effort */ }
    }

    [Fact]
    public async Task NoUpdateWhenServerOffersNone()
    {
        var (_, pub) = NewKey();
        var api = new FakeBackendApi { Update = null };
        var updater = new AgentUpdater(api, "1.0.0", pub, Path.GetTempPath(), NullLogger.Instance);
        (await updater.CheckAndStageAsync("dvt_token", CancellationToken.None)).Should().BeFalse();
    }
}
