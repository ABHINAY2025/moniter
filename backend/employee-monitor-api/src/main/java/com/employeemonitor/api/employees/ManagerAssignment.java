package com.employeemonitor.api.employees;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;

/** Grants a MANAGER visibility of one employee. Managers see nobody by default. */
@Entity
@Table(name = "manager_assignments")
@IdClass(ManagerAssignment.Key.class)
public class ManagerAssignment {

    public record Key(Long managerUserId, Long employeeId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Id
    @Column(name = "employee_id")
    private Long employeeId;

    /** Live view (later milestone) additionally requires this explicit flag. */
    @Column(name = "live_view_allowed", nullable = false)
    private boolean liveViewAllowed;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected ManagerAssignment() {
    }

    public ManagerAssignment(Long managerUserId, Long employeeId, boolean liveViewAllowed, Long createdBy) {
        this.managerUserId = managerUserId;
        this.employeeId = employeeId;
        this.liveViewAllowed = liveViewAllowed;
        this.createdBy = createdBy;
    }

    public Long getManagerUserId() {
        return managerUserId;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public boolean isLiveViewAllowed() {
        return liveViewAllowed;
    }

    public void setLiveViewAllowed(boolean liveViewAllowed) {
        this.liveViewAllowed = liveViewAllowed;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
