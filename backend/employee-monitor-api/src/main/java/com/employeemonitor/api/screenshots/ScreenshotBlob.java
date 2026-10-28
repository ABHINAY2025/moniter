package com.employeemonitor.api.screenshots;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Screenshot bytes stored in PostgreSQL (bytea) when {@code app.screenshots.storage=database}. Encrypted at rest. */
@Entity
@Table(name = "screenshot_blobs")
public class ScreenshotBlob {

    @Id
    @Column(name = "storage_key", length = 80, nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ScreenshotBlob() {
    }

    public ScreenshotBlob(String storageKey, byte[] data, Instant createdAt) {
        this.storageKey = storageKey;
        this.data = data;
        this.createdAt = createdAt;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public byte[] getData() {
        return data;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
