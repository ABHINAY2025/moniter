package com.employeemonitor.api.monitoring;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MonitoringDtos {

    private MonitoringDtos() {
    }

    public enum UsbEventType { CONNECTED, REMOVED }

    public enum NetworkEventType { CONNECTED, DISCONNECTED, CHANGED }

    public enum ConnectionType { WIFI, ETHERNET, VPN, OTHER }

    public enum ZscalerStatus { CONNECTED, DISCONNECTED, ERROR, UNKNOWN }

    // ---------- Agent ingest inputs ----------

    public record AppUsageInput(
            @NotNull UUID clientEventId,
            @NotBlank @Size(max = 255) String applicationName,
            @Size(max = 255) String processName,
            @NotNull Instant startedAt,
            @NotNull Instant endedAt) {
    }

    public record WebsiteUsageInput(
            @NotNull UUID clientEventId,
            @NotBlank @Size(max = 255) String domain,
            @Size(max = 120) String browser,
            @NotNull Instant startedAt,
            @NotNull Instant endedAt) {
    }

    public record UsbEventInput(
            @NotNull UUID clientEventId,
            @NotNull UsbEventType eventType,
            @Size(max = 80) String deviceClass,
            @Size(max = 16) String vendorId,
            @Size(max = 16) String productId,
            @Size(max = 128) String serialNumber,
            @Size(max = 255) String description,
            @NotNull Instant occurredAt) {
    }

    public record NetworkEventInput(
            @NotNull UUID clientEventId,
            @NotNull NetworkEventType eventType,
            ConnectionType connectionType,
            @Size(max = 255) String networkName,
            @Size(max = 64) String ipAddress,
            @NotNull Instant occurredAt) {
    }

    public record ZscalerEventInput(
            @NotNull UUID clientEventId,
            @NotNull ZscalerStatus status,
            @Size(max = 255) String detail,
            @NotNull Instant occurredAt) {
    }

    public record Batch<T>(@NotEmpty @Size(max = 500) @Valid List<T> events) {
    }

    public record IngestResult(int accepted, int duplicates, int rejected) {
    }

    // ---------- Admin read responses ----------

    public record UsageReport(Long employeeId, String date, String timezone, long totalSeconds,
                              List<UsageSlice> items) {
    }

    public record UsbEventResponse(Long id, String eventType, String deviceClass, String vendorId, String productId,
                                   String serialNumber, String description, Instant occurredAt) {
        public static UsbEventResponse from(UsbEvent e) {
            return new UsbEventResponse(e.getId(), e.getEventType(), e.getDeviceClass(), e.getVendorId(),
                    e.getProductId(), e.getSerialNumber(), e.getDescription(), e.getOccurredAt());
        }
    }

    public record NetworkEventResponse(Long id, String eventType, String connectionType, String networkName,
                                       String ipAddress, Instant occurredAt) {
        public static NetworkEventResponse from(NetworkEvent e) {
            return new NetworkEventResponse(e.getId(), e.getEventType(), e.getConnectionType(), e.getNetworkName(),
                    e.getIpAddress(), e.getOccurredAt());
        }
    }

    public record ZscalerEventResponse(Long id, String status, String detail, Instant occurredAt) {
        public static ZscalerEventResponse from(ZscalerEvent e) {
            return new ZscalerEventResponse(e.getId(), e.getStatus(), e.getDetail(), e.getOccurredAt());
        }
    }

    public record ZscalerStatusResponse(String currentStatus, Instant since, List<ZscalerEventResponse> history) {
    }
}
