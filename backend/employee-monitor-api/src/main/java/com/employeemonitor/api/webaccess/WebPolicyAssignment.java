package com.employeemonitor.api.webaccess;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Assigns a policy to a DEVICE or an EMPLOYEE. Employee assignments apply to all that employee's devices. */
@Entity
@Table(name = "web_policy_assignments")
public class WebPolicyAssignment {

    public enum TargetType {
        DEVICE,
        EMPLOYEE
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_id", nullable = false)
    private Long policyId;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WebPolicyAssignment() {
    }

    public WebPolicyAssignment(Long policyId, TargetType targetType, Long targetId) {
        this.policyId = policyId;
        this.targetType = targetType.name();
        this.targetId = targetId;
    }

    public Long getId() {
        return id;
    }

    public Long getPolicyId() {
        return policyId;
    }

    public TargetType getTargetType() {
        return TargetType.valueOf(targetType);
    }

    public Long getTargetId() {
        return targetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
