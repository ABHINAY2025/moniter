package com.employeemonitor.api.screenshots;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "screenshots")
public class Screenshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "client_event_id", nullable = false)
    private UUID clientEventId;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    private Integer width;

    private Integer height;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private boolean encrypted;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    protected Screenshot() {
    }

    public Screenshot(UUID clientEventId, Long deviceId, Long employeeId, Instant capturedAt, String storageKey,
                      String contentType, Integer width, Integer height, int sizeBytes, boolean encrypted) {
        this.clientEventId = clientEventId;
        this.deviceId = deviceId;
        this.employeeId = employeeId;
        this.capturedAt = capturedAt;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.width = width;
        this.height = height;
        this.sizeBytes = sizeBytes;
        this.encrypted = encrypted;
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

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public int getSizeBytes() {
        return sizeBytes;
    }

    public boolean isEncrypted() {
        return encrypted;
    }
}
