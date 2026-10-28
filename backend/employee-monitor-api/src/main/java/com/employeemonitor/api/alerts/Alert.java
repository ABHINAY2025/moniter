package com.employeemonitor.api.alerts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A suspicious-activity alert raised by a server-side rule. Denormalised device/employee labels for easy display. */
@Entity
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String message;

    @Column(name = "device_id")
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "device_code")
    private String deviceCode;

    @Column(name = "employee_name")
    private String employeeName;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private Long acknowledgedBy;

    protected Alert() {
    }

    public Alert(String category, String severity, String message, Long deviceId, Long employeeId,
                 String deviceCode, String employeeName, String dedupKey, Instant createdAt) {
        this.category = category;
        this.severity = severity;
        this.message = message;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.deviceCode = deviceCode;
        this.employeeName = employeeName;
        this.dedupKey = dedupKey;
        this.createdAt = createdAt;
    }

    public void acknowledge(Long userId, Instant at) {
        this.acknowledgedBy = userId;
        this.acknowledgedAt = at;
    }

    public Long getId() {
        return id;
    }

    public String getCategory() {
        return category;
    }

    public String getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public Long getDeviceId() {
        return deviceId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getEmployeeName() {
        return employeeName;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Long getAcknowledgedBy() {
        return acknowledgedBy;
    }
}
