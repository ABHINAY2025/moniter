using System.Net;

namespace EmployeeMonitor.Admin.Core.Api;

public sealed class ApiException : Exception
{
    public HttpStatusCode? StatusCode { get; }
    public string? Code { get; }

    public ApiException(string message, HttpStatusCode? statusCode = null, string? code = null, Exception? inner = null)
        : base(message, inner)
    {
        StatusCode = statusCode;
        Code = code;
    }

    public bool IsUnauthorized => StatusCode == HttpStatusCode.Unauthorized;
}
