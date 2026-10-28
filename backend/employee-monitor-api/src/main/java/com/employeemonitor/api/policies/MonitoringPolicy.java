package com.employeemonitor.api.policies;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "monitoring_policies")
public class MonitoringPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean defaultPolicy;

    @Column(name = "device_specific", nullable = false)
    private boolean deviceSpecific;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private PolicySettings settings;

    /** Incremented on every change; agents compare it to decide whether to re-download the policy. */
    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected MonitoringPolicy() {
    }

    public MonitoringPolicy(String name, boolean deviceSpecific, PolicySettings settings) {
        this.name = name;
        this.deviceSpecific = deviceSpecific;
        this.settings = settings;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isDefaultPolicy() {
        return defaultPolicy;
    }

    public boolean isDeviceSpecific() {
        return deviceSpecific;
    }

    public PolicySettings getSettings() {
        return settings;
    }

    public void setSettings(PolicySettings settings) {
        this.settings = settings;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
