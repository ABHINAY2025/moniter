package com.employeemonitor.api.webaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Per-device enforcement health reported by the agent (what mechanism, which revision is applied, last error). */
@Entity
@Table(name = "web_enforcement_status")
public class WebEnforcementStatus {

    @Id
    @Column(name = "device_id")
    private Long deviceId;

    private String mechanism;

    @Column(nullable = false)
    private boolean healthy;

    @Column(name = "applied_revision")
    private String appliedRevision;

    private String browsers;

    @Column(name = "last_sync_at")
    private Instant lastSyncAt;

    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected WebEnforcementStatus() {
    }

    public WebEnforcementStatus(Long deviceId) {
        this.deviceId = deviceId;
    }

    public void report(String mechanism, boolean healthy, String appliedRevision, String browsers, String lastError,
                       Instant now) {
        this.mechanism = mechanism;
        this.healthy = healthy;
        this.appliedRevision = appliedRevision;
        this.browsers = browsers;
        this.lastError = lastError;
        this.lastSyncAt = now;
        this.updatedAt = now;
    }

    public void touchEvent(Instant now) {
        this.lastEventAt = now;
        this.updatedAt = now;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public String getMechanism() {
        return mechanism;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public String getAppliedRevision() {
        return appliedRevision;
    }

    public String getBrowsers() {
        return browsers;
    }

    public Instant getLastSyncAt() {
        return lastSyncAt;
    }

    public Instant getLastEventAt() {
        return lastEventAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
