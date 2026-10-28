package com.employeemonitor.api.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "application_usage")
public class ApplicationUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false)
    private UUID clientEventId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "application_name", nullable = false)
    private String applicationName;

    @Column(name = "process_name")
    private String processName;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected ApplicationUsage() {
    }

    public ApplicationUsage(UUID clientEventId, Long deviceId, Long employeeId, String applicationName,
                            String processName, Instant startedAt, Instant endedAt, int durationSeconds) {
        this.clientEventId = clientEventId;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.applicationName = applicationName;
        this.processName = processName;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationSeconds = durationSeconds;
    }

    public Long getId() {
        return id;
    }

    public UUID getClientEventId() {
        return clientEventId;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getProcessName() {
        return processName;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }
}
