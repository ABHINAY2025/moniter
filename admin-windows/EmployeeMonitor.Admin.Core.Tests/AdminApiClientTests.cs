using System.Net;
using System.Text;
using EmployeeMonitor.Admin.Core;
using EmployeeMonitor.Admin.Core.Api;
using FluentAssertions;
using Microsoft.Extensions.Logging.Abstractions;
using Xunit;

namespace EmployeeMonitor.Admin.Core.Tests;

public class AdminApiClientTests
{
    /// <summary>Scripts HTTP responses per (method, path) so refresh-on-401 can be exercised deterministically.</summary>
    private sealed class StubHandler : HttpMessageHandler
    {
        private readonly Queue<Func<HttpRequestMessage, HttpResponseMessage>> _responses = new();
        public List<string> Requests { get; } = new();

        public StubHandler Enqueue(Func<HttpRequestMessage, HttpResponseMessage> responder)
        {
            _responses.Enqueue(responder);
            return this;
        }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct)
        {
            Requests.Add($"{request.Method} {request.RequestUri!.AbsolutePath}");
            var responder = _responses.Dequeue();
            return Task.FromResult(responder(request));
        }
    }

    private static HttpResponseMessage Json(HttpStatusCode code, string body) =>
        new(code) { Content = new StringContent(body, Encoding.UTF8, "application/json") };

    private static AdminApiClient Build(StubHandler handler, SessionState session) =>
        new(new HttpClient(handler) { BaseAddress = new Uri("http://localhost:8080") },
            session, NullLogger<AdminApiClient>.Instance);

    [Fact]
    public async Task RefreshesOnceOn401ThenRetries()
    {
        var handler = new StubHandler()
            .Enqueue(_ => Json(HttpStatusCode.Unauthorized, """{"code":"UNAUTHORIZED","detail":"nope"}"""))
            .Enqueue(req =>
            {
                req.RequestUri!.AbsolutePath.Should().Be("/api/auth/refresh");
                return Json(HttpStatusCode.OK, """
                    {"tokenType":"Bearer","accessToken":"new-access","accessTokenExpiresAt":"2030-01-01T00:00:00Z",
                     "refreshToken":"rt_new","refreshTokenExpiresAt":"2030-01-08T00:00:00Z"}
                    """);
            })
            .Enqueue(req =>
            {
                req.Headers.Authorization!.Parameter.Should().Be("new-access");
                return Json(HttpStatusCode.OK, """
                    {"totalEmployees":5,"online":2,"idle":1,"locked":0,"offline":2,"activeDevices":4,
                     "onlineDevices":3,"unassignedDevices":1,"generatedAt":"2026-10-07T00:00:00Z"}
                    """);
            });

        var session = new SessionState();
        session.Set(Data.Tokens());
        var client = Build(handler, session);

        var summary = await client.GetDashboardSummaryAsync();

        summary.TotalEmployees.Should().Be(5);
        session.AccessToken.Should().Be("new-access");
        session.RefreshToken.Should().Be("rt_new");
        handler.Requests.Should().Equal(
            "GET /api/dashboard/summary", "POST /api/auth/refresh", "GET /api/dashboard/summary");
    }

    [Fact]
    public async Task FailedRefreshClearsSessionAndThrows()
    {
        var handler = new StubHandler()
            .Enqueue(_ => Json(HttpStatusCode.Unauthorized, """{"code":"UNAUTHORIZED"}"""))
            .Enqueue(_ => Json(HttpStatusCode.Unauthorized, """{"code":"INVALID_REFRESH_TOKEN"}"""));

        var session = new SessionState();
        session.Set(Data.Tokens());
        var client = Build(handler, session);

        await FluentActions.Awaiting(() => client.GetDashboardSummaryAsync())
            .Should().ThrowAsync<ApiException>();
        session.IsAuthenticated.Should().BeFalse();
    }

    [Fact]
    public async Task LoginStoresTokensInSession()
    {
        var handler = new StubHandler().Enqueue(_ => Json(HttpStatusCode.OK, """
            {"tokenType":"Bearer","accessToken":"acc","accessTokenExpiresAt":"2030-01-01T00:00:00Z",
             "refreshToken":"rt","refreshTokenExpiresAt":"2030-01-08T00:00:00Z",
             "user":{"id":1,"username":"admin","fullName":"Admin","email":"a@b.c","employeeId":null,
                     "roles":["SUPER_ADMIN"],"permissions":["REPORT_READ"]}}
            """));
        var session = new SessionState();
        var client = Build(handler, session);

        var tokens = await client.LoginAsync("admin", "pw");

        tokens.User.Username.Should().Be("admin");
        session.IsAuthenticated.Should().BeTrue();
        session.User!.Has("REPORT_READ").Should().BeTrue();
    }
}
