package com.employeemonitor.api.monitoring;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Domain-level browsing usage. Never stores full URLs, query strings, page contents or history. */
@Entity
@Table(name = "website_usage")
public class WebsiteUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false)
    private UUID clientEventId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(nullable = false)
    private String domain;

    private String browser;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at", nullable = false)
    private Instant endedAt;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected WebsiteUsage() {
    }

    public WebsiteUsage(UUID clientEventId, Long deviceId, Long employeeId, String domain, String browser,
                        Instant startedAt, Instant endedAt, int durationSeconds) {
        this.clientEventId = clientEventId;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.domain = domain;
        this.browser = browser;
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

    public String getDomain() {
        return domain;
    }

    public String getBrowser() {
        return browser;
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
