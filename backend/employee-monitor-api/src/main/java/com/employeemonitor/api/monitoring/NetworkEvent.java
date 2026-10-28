package com.employeemonitor.api.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Endpoint network status change. Status/visibility only; no packet capture or TLS inspection. */
@Entity
@Table(name = "network_events")
public class NetworkEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false)
    private UUID clientEventId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "connection_type")
    private String connectionType;

    @Column(name = "network_name")
    private String networkName;

    @Column(name = "ip_address")
    private String ipAddress;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected NetworkEvent() {
    }

    public NetworkEvent(UUID clientEventId, Long deviceId, Long employeeId, String eventType, String connectionType,
                        String networkName, String ipAddress, Instant occurredAt) {
        this.clientEventId = clientEventId;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.eventType = eventType;
        this.connectionType = connectionType;
        this.networkName = networkName;
        this.ipAddress = ipAddress;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getClientEventId() {
        return clientEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getConnectionType() {
        return connectionType;
    }

    public String getNetworkName() {
        return networkName;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
