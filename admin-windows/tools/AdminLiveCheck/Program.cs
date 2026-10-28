using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using EmployeeMonitor.Admin.Core;
using EmployeeMonitor.Admin.Core.Api;
using EmployeeMonitor.Admin.Core.Realtime;
using Microsoft.Extensions.Logging.Abstractions;

// Headless end-to-end check of the admin client stack (AdminApiClient + RealtimeClient) against a running backend.
//   dotnet run --project admin-windows/tools/AdminLiveCheck -- <baseUrl> <adminUser> <adminPassword>

// Live-view mode: `liveview <baseUrl> <user> <pass> <deviceId>` - drives a session against a running agent.
if (args.Length >= 5 && args[0] == "liveview")
{
    return await LiveViewCheck(args[1], args[2], args[3], long.Parse(args[4]));
}

// Publish-update mode: `publishupdate <baseUrl> <user> <pass> <version>` - generates a key, signs and publishes a
// package, approves it, and prints PUBLIC_KEY=<base64> for the caller to configure the agent with.
if (args.Length >= 5 && args[0] == "publishupdate")
{
    return await PublishUpdate(args[1], args[2], args[3], args[4]);
}

// Key generation: `keygen` - prints a persistent RSA key pair (private PKCS#8 PEM + public SPKI base64).
if (args.Length >= 1 && args[0] == "keygen")
{
    using var rsa = System.Security.Cryptography.RSA.Create(2048);
    Console.WriteLine("PUBLIC_KEY=" + Convert.ToBase64String(rsa.ExportSubjectPublicKeyInfo()));
    Console.WriteLine("-----BEGIN PRIVATE KEY-----");
    Console.WriteLine(rsa.ExportPkcs8PrivateKeyPem().Replace("-----BEGIN PRIVATE KEY-----", "")
        .Replace("-----END PRIVATE KEY-----", "").Trim());
    Console.WriteLine("-----END PRIVATE KEY-----");
    return 0;
}

// Sign & publish a real package: `signpublish <baseUrl> <user> <pass> <version> <privatePemPath> <packagePath> <mandatory>`.
if (args.Length >= 8 && args[0] == "signpublish")
{
    return await SignPublish(args[1], args[2], args[3], args[4], args[5], args[6], bool.Parse(args[7]));
}

string baseUrl = args.Length > 0 ? args[0] : "http://localhost:8080";
string user = args.Length > 1 ? args[1] : "admin";
string password = args.Length > 2 ? args[2] : "";

int failures = 0;
void Check(bool ok, string msg)
{
    Console.ForegroundColor = ok ? ConsoleColor.Green : ConsoleColor.Red;
    Console.WriteLine($"   {(ok ? "OK  " : "FAIL")} {msg}");
    Console.ResetColor();
    if (!ok) failures++;
}

var json = new JsonSerializerOptions(JsonSerializerDefaults.Web) { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
var session = new SessionState();
using var http = new HttpClient { BaseAddress = new Uri(baseUrl) };
var api = new AdminApiClient(http, session, NullLogger<AdminApiClient>.Instance);

Console.WriteLine("== Admin client login");
await api.LoginAsync(user, password);
Check(session.IsAuthenticated, $"Logged in as {session.User?.Username} ({string.Join(",", session.User?.Roles ?? [])})");

Console.WriteLine("\n== Dashboard + devices over REST");
var summary = await api.GetDashboardSummaryAsync();
Check(summary.TotalEmployees >= 0, $"Dashboard: total={summary.TotalEmployees} online={summary.Online} idle={summary.Idle} offline={summary.Offline} activeDevices={summary.ActiveDevices}");
var devices = await api.GetDevicesAsync(null, 0, 50);
Check(true, $"Devices page: {devices.Content.Count} of {devices.TotalElements}");

Console.WriteLine("\n== Realtime WebSocket");
var realtime = new RealtimeClient(baseUrl, session, NullLogger<RealtimeClient>.Instance);
var received = new List<PresenceEvent>();
var gotEvent = new TaskCompletionSource();
realtime.PresenceReceived += e => { lock (received) { received.Add(e); } gotEvent.TrySetResult(); };
await realtime.StartAsync();

// Enrol a throwaway device and heartbeat it, which must produce a realtime presence event.
async Task<T> AdminPost<T>(string path, object body)
{
    using var req = new HttpRequestMessage(HttpMethod.Post, path) { Content = JsonContent.Create(body, options: json) };
    req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", session.AccessToken);
    using var resp = await http.SendAsync(req);
    resp.EnsureSuccessStatusCode();
    return (await resp.Content.ReadFromJsonAsync<T>(json))!;
}

var suffix = Guid.NewGuid().ToString("N")[..8];
var employee = await AdminPost<JsonElement>("/api/employees",
    new { employeeCode = $"ADM-{suffix}", fullName = $"Admin LiveCheck {suffix}", email = $"adm.{suffix}@example.com" });
long employeeId = employee.GetProperty("id").GetInt64();
var enroll = await AdminPost<JsonElement>("/api/enrollment-tokens", new { employeeId, maxUses = 1, expiresInHours = 1 });
string enrollToken = enroll.GetProperty("token").GetString()!;

using var devHttp = new HttpClient { BaseAddress = new Uri(baseUrl) };
var reg = await (await devHttp.PostAsJsonAsync("/api/devices/register",
    new { enrollmentToken = enrollToken, hostname = $"ADMLIVE-{suffix}", agentVersion = "1.0.0" }, json))
    .Content.ReadFromJsonAsync<JsonElement>(json);
long deviceId = reg.GetProperty("deviceId").GetInt64();
string deviceToken = reg.GetProperty("deviceToken").GetString()!;

// Wait until realtime is connected, then heartbeat.
for (int i = 0; i < 50 && realtime.Status != RealtimeStatus.Connected; i++) await Task.Delay(100);
Check(realtime.Status == RealtimeStatus.Connected, "Realtime connected");

using (var hb = new HttpRequestMessage(HttpMethod.Post, "/api/agent/heartbeat")
       { Content = JsonContent.Create(new { status = "ACTIVE" }, options: json) })
{
    hb.Headers.Authorization = new AuthenticationHeaderValue("Bearer", deviceToken);
    (await devHttp.SendAsync(hb)).EnsureSuccessStatusCode();
}

await Task.WhenAny(gotEvent.Task, Task.Delay(TimeSpan.FromSeconds(8)));
PresenceEvent? mine;
lock (received) { mine = received.FirstOrDefault(e => e.DeviceId == deviceId); }
Check(mine is not null, mine is null
    ? "No realtime event for the test device within 8s"
    : $"Realtime event received: {mine.Type} device={mine.DeviceId} employee={mine.EmployeeId} status={mine.Status}");

await realtime.StopAsync();
await api.LogoutAsync();
Check(!session.IsAuthenticated, "Logged out");

Console.WriteLine();
if (failures == 0) { Console.ForegroundColor = ConsoleColor.Green; Console.WriteLine("ADMIN LIVE CHECK PASSED"); }
else { Console.ForegroundColor = ConsoleColor.Red; Console.WriteLine($"{failures} CHECK(S) FAILED"); }
Console.ResetColor();
return failures == 0 ? 0 : 1;

// ---------- Publish a signed agent update (prints PUBLIC_KEY=... for the agent) ----------
async Task<int> PublishUpdate(string url, string u, string pw, string version)
{
    using var rsa = System.Security.Cryptography.RSA.Create(2048);
    string publicKey = Convert.ToBase64String(rsa.ExportSubjectPublicKeyInfo());
    var pkg = new byte[4096];
    System.Security.Cryptography.RandomNumberGenerator.Fill(pkg);
    string signature = Convert.ToBase64String(rsa.SignData(pkg,
        System.Security.Cryptography.HashAlgorithmName.SHA256,
        System.Security.Cryptography.RSASignaturePadding.Pkcs1));

    var jsonOpts = new JsonSerializerOptions(JsonSerializerDefaults.Web) { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    var st = new SessionState();
    using var c = new HttpClient { BaseAddress = new Uri(url), Timeout = TimeSpan.FromSeconds(30) };
    var backend = new AdminApiClient(c, st, NullLogger<AdminApiClient>.Instance);
    await backend.LoginAsync(u, pw);

    async Task<JsonElement> Post(string path, object body)
    {
        using var req = new HttpRequestMessage(HttpMethod.Post, path) { Content = JsonContent.Create(body, options: jsonOpts) };
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", st.AccessToken);
        var resp = await c.SendAsync(req);
        resp.EnsureSuccessStatusCode();
        return JsonSerializer.Deserialize<JsonElement>(await resp.Content.ReadAsStringAsync(), jsonOpts);
    }

    var published = await Post("/api/agent-versions",
        new { version, notes = "update-smoke", mandatory = false, signature, packageBase64 = Convert.ToBase64String(pkg) });
    long id = published.GetProperty("id").GetInt64();
    using (var req = new HttpRequestMessage(HttpMethod.Put, $"/api/agent-versions/{id}/approval")
           { Content = JsonContent.Create(new { approved = true }, options: jsonOpts) })
    {
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", st.AccessToken);
        (await c.SendAsync(req)).EnsureSuccessStatusCode();
    }
    Console.WriteLine($"PUBLISHED version {version} (id {id}), approved");
    Console.WriteLine("PUBLIC_KEY=" + publicKey);
    return 0;
}

// ---------- Sign a real package with a persistent key and publish it ----------
async Task<int> SignPublish(string url, string u, string pw, string version, string pemPath, string packagePath,
    bool mandatory)
{
    byte[] pkg = await File.ReadAllBytesAsync(packagePath);
    using var rsa = System.Security.Cryptography.RSA.Create();
    rsa.ImportFromPem(await File.ReadAllTextAsync(pemPath));
    string signature = Convert.ToBase64String(rsa.SignData(pkg,
        System.Security.Cryptography.HashAlgorithmName.SHA256,
        System.Security.Cryptography.RSASignaturePadding.Pkcs1));

    var jsonOpts = new JsonSerializerOptions(JsonSerializerDefaults.Web) { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    var st = new SessionState();
    using var c = new HttpClient { BaseAddress = new Uri(url), Timeout = TimeSpan.FromMinutes(5) };
    var backend = new AdminApiClient(c, st, NullLogger<AdminApiClient>.Instance);
    await backend.LoginAsync(u, pw);

    using var req = new HttpRequestMessage(HttpMethod.Post, "/api/agent-versions")
    {
        Content = JsonContent.Create(new { version, mandatory, signature, packageBase64 = Convert.ToBase64String(pkg) },
            options: jsonOpts),
    };
    req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", st.AccessToken);
    var resp = await c.SendAsync(req);
    if (!resp.IsSuccessStatusCode)
    {
        Console.Error.WriteLine($"Publish failed: {(int)resp.StatusCode} {await resp.Content.ReadAsStringAsync()}");
        return 1;
    }
    var published = JsonSerializer.Deserialize<JsonElement>(await resp.Content.ReadAsStringAsync(), jsonOpts);
    Console.WriteLine($"PUBLISHED version {version} (id {published.GetProperty("id").GetInt64()}). " +
        "Approve it in the admin app or via PUT /api/agent-versions/{id}/approval.");
    return 0;
}

// ---------- Live-view end-to-end (requires a running agent for the device) ----------
async Task<int> LiveViewCheck(string url, string u, string pw, long deviceId)
{
    int fail = 0;
    void C(bool ok, string m)
    {
        Console.ForegroundColor = ok ? ConsoleColor.Green : ConsoleColor.Red;
        Console.WriteLine($"   {(ok ? "OK  " : "FAIL")} {m}");
        Console.ResetColor();
        if (!ok) fail++;
    }

    var opts = new JsonSerializerOptions(JsonSerializerDefaults.Web) { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
    var state = new SessionState();
    using var client = new HttpClient { BaseAddress = new Uri(url), Timeout = TimeSpan.FromSeconds(15) };
    var backend = new AdminApiClient(client, state, NullLogger<AdminApiClient>.Instance);

    Console.WriteLine("== Live view");
    await backend.LoginAsync(u, pw);
    var session = await backend.StartLiveViewAsync(deviceId);
    C(session.SessionId != Guid.Empty, $"Session created for {session.DeviceCode} (max {session.MaxDurationSeconds}s)");

    var signaling = new LiveViewSignalingClient(url, state, NullLogger<LiveViewSignalingClient>.Instance);
    var gotFrame = new TaskCompletionSource();
    int frames = 0;
    signaling.FrameReceived += _ => { frames++; gotFrame.TrySetResult(); };
    await signaling.StartAsync(session.SessionId);

    await Task.WhenAny(gotFrame.Task, Task.Delay(TimeSpan.FromSeconds(15)));
    C(frames > 0, frames > 0 ? $"Received live frame(s) from the agent ({frames})" : "No live frames within 15s");

    await backend.StopLiveViewAsync(session.SessionId);
    await signaling.StopAsync();

    // Both start and stop are audited.
    using (var req = new HttpRequestMessage(HttpMethod.Get,
               $"/api/audit-logs?targetType=LIVE_VIEW&targetId={session.SessionId}"))
    {
        req.Headers.Authorization = new AuthenticationHeaderValue("Bearer", state.AccessToken);
        var auditBody = await (await client.SendAsync(req)).Content.ReadAsStringAsync();
        C(auditBody.Contains("LIVE_VIEW_STARTED") && auditBody.Contains("LIVE_VIEW_STOPPED"),
            "Live-view start and stop were audited");
    }

    Console.WriteLine();
    if (fail == 0) { Console.ForegroundColor = ConsoleColor.Green; Console.WriteLine("LIVE VIEW CHECK PASSED"); }
    else { Console.ForegroundColor = ConsoleColor.Red; Console.WriteLine($"{fail} CHECK(S) FAILED"); }
    Console.ResetColor();
    return fail == 0 ? 0 : 1;
}
