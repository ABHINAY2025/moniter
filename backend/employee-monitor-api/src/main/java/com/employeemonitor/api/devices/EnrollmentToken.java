package com.employeemonitor.api.devices;

import com.employeemonitor.api.employees.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Limited-use enrollment code issued by an IT admin and entered into (or deployed with) the installer.
 * It proves the device is being enrolled with company authorization and may pre-assign an employee.
 */
@Entity
@Table(name = "enrollment_tokens")
public class EnrollmentToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    private String description;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @Column(name = "max_uses", nullable = false)
    private int maxUses;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected EnrollmentToken() {
    }

    public EnrollmentToken(String tokenHash, String description, Employee employee, int maxUses, Instant expiresAt,
                           Long createdBy) {
        this.tokenHash = tokenHash;
        this.description = description;
        this.employee = employee;
        this.maxUses = maxUses;
        this.expiresAt = expiresAt;
        this.createdBy = createdBy;
    }

    public boolean isUsable(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now) && useCount < maxUses;
    }

    public void consume() {
        useCount++;
    }

    public void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public Long getId() {
        return id;
    }

    public String getDescription() {
        return description;
    }

    public Employee getEmployee() {
        return employee;
    }

    public int getMaxUses() {
        return maxUses;
    }

    public int getUseCount() {
        return useCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
