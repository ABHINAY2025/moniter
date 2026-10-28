package com.employeemonitor.api.liveview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "live_view_sessions")
public class LiveViewSession {

    public enum Status { PENDING, ACTIVE, ENDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, unique = true)
    private UUID sessionId;

    @Column(name = "admin_user_id")
    private Long adminUserId;

    @Column(name = "admin_username")
    private String adminUsername;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "connected_at")
    private Instant connectedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "end_reason")
    private String endReason;

    @Column(name = "created_ip")
    private String createdIp;

    protected LiveViewSession() {
    }

    public LiveViewSession(UUID sessionId, Long adminUserId, String adminUsername, Long deviceId, Long employeeId,
                           String createdIp) {
        this.sessionId = sessionId;
        this.adminUserId = adminUserId;
        this.adminUsername = adminUsername;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.createdIp = createdIp;
    }

    public void markActive(Instant now) {
        if (status == Status.PENDING) {
            status = Status.ACTIVE;
            connectedAt = now;
        }
    }

    public void end(Instant now, String reason) {
        if (status != Status.ENDED) {
            status = Status.ENDED;
            endedAt = now;
            endReason = reason;
            durationSeconds = (int) Math.max(0, Duration.between(startedAt, now).getSeconds());
        }
    }

    public boolean isEnded() {
        return status == Status.ENDED;
    }

    public Long getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public Long getAdminUserId() {
        return adminUserId;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public String getEndReason() {
        return endReason;
    }
}
