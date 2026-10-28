namespace EmployeeMonitor.Agent.Api;

// Wire contracts for the Milestone 4 monitoring-ingest endpoints. camelCase via BackendClient options.

public sealed record MonAppUsage(
    Guid ClientEventId, string ApplicationName, string? ProcessName,
    DateTimeOffset StartedAt, DateTimeOffset EndedAt);

public sealed record MonWebsiteUsage(
    Guid ClientEventId, string Domain, string? Browser, DateTimeOffset StartedAt, DateTimeOffset EndedAt);

public sealed record MonUsbEvent(
    Guid ClientEventId, string EventType, string? DeviceClass, string? VendorId, string? ProductId,
    string? SerialNumber, string? Description, DateTimeOffset OccurredAt);

public sealed record MonNetworkEvent(
    Guid ClientEventId, string EventType, string? ConnectionType, string? NetworkName, string? IpAddress,
    DateTimeOffset OccurredAt);

public sealed record MonZscalerEvent(
    Guid ClientEventId, string Status, string? Detail, DateTimeOffset OccurredAt);

public sealed record MonBatch<T>(IReadOnlyList<T> Events);

public sealed record MonIngestResult(int Accepted, int Duplicates, int Rejected);

public sealed record MonScreenshot(
    Guid ClientEventId, DateTimeOffset CapturedAt, string ContentType, int? Width, int? Height, string ImageBase64);

public sealed record MonScreenshotResult(long Id, bool Duplicate);
