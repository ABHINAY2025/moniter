package com.employeemonitor.api.sessions;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "session_events")
public class SessionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false)
    private UUID clientEventId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private SessionEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionEventType.Category category;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected SessionEvent() {
    }

    public SessionEvent(UUID clientEventId, Long deviceId, Long employeeId, SessionEventType eventType,
                        Instant occurredAt) {
        this.clientEventId = clientEventId;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.eventType = eventType;
        this.category = eventType.category();
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getClientEventId() {
        return clientEventId;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public SessionEventType getEventType() {
        return eventType;
    }

    public SessionEventType.Category getCategory() {
        return category;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
