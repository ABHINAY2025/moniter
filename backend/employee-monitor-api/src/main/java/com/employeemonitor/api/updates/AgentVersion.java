package com.employeemonitor.api.updates;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** A published agent build. Bytes live in update storage keyed by {@code downloadKey}. */
@Entity
@Table(name = "agent_versions")
public class AgentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String version;

    @Column(name = "download_key")
    private String downloadKey;

    private String sha256;

    @Column(columnDefinition = "text")
    private String signature;

    @Column(nullable = false)
    private boolean approved;

    @Column(nullable = false)
    private boolean mandatory;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "content_type")
    private String contentType;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "released_at", nullable = false, updatable = false)
    private Instant releasedAt = Instant.now();

    private String notes;

    protected AgentVersion() {
    }

    public AgentVersion(String version, String downloadKey, String sha256, String signature, long sizeBytes,
                        String contentType, Long createdBy, String notes) {
        this.version = version;
        this.downloadKey = downloadKey;
        this.sha256 = sha256;
        this.signature = signature;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.createdBy = createdBy;
        this.notes = notes;
    }

    public Long getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getDownloadKey() {
        return downloadKey;
    }

    public String getSha256() {
        return sha256;
    }

    public String getSignature() {
        return signature;
    }

    public boolean isApproved() {
        return approved;
    }

    public void setApproved(boolean approved) {
        this.approved = approved;
    }

    public boolean isMandatory() {
        return mandatory;
    }

    public void setMandatory(boolean mandatory) {
        this.mandatory = mandatory;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getContentType() {
        return contentType;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public String getNotes() {
        return notes;
    }
}
