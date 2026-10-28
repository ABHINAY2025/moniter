package com.employeemonitor.api.updates;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Signed agent-package bytes stored in PostgreSQL (bytea) when {@code app.updates.storage=database}. */
@Entity
@Table(name = "update_blobs")
public class UpdateBlob {

    @Id
    @Column(name = "storage_key", length = 160, nullable = false, updatable = false)
    private String storageKey;

    @Column(name = "data", nullable = false)
    private byte[] data;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UpdateBlob() {
    }

    public UpdateBlob(String storageKey, byte[] data, Instant createdAt) {
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
