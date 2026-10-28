using System.Net;

namespace EmployeeMonitor.Agent.Api;

/// <summary>Raised when the backend returns a non-success status. Carries the HTTP status and error code.</summary>
public sealed class BackendException : Exception
{
    public HttpStatusCode? StatusCode { get; }
    public string? ErrorCode { get; }

    public BackendException(string message, HttpStatusCode? statusCode = null, string? errorCode = null,
        Exception? inner = null)
        : base(message, inner)
    {
        StatusCode = statusCode;
        ErrorCode = errorCode;
    }

    /// <summary>True for transient conditions worth retrying (network, 5xx, 429).</summary>
    public bool IsTransient =>
        StatusCode is null
        || (int)StatusCode >= 500
        || StatusCode == HttpStatusCode.TooManyRequests;

    /// <summary>True when the device credential is no longer accepted (disabled/retired/revoked).</summary>
    public bool IsAuthFailure =>
        StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden;
}
