package com.employeemonitor.api.employees;

import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.security.AuthenticatedUser;
import com.employeemonitor.api.users.Permissions;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

/**
 * Central row-level authorization for employee data. Every endpoint that returns data about a specific
 * employee (or that employee's devices) must go through here.
 * <ul>
 *   <li>EMPLOYEE_READ_ALL: every employee</li>
 *   <li>EMPLOYEE_READ_ASSIGNED: employees explicitly assigned via manager_assignments</li>
 *   <li>SELF_READ: the employee record linked to the user's own account</li>
 * </ul>
 */
@Service
public class EmployeeAccessService {

    private final ManagerAssignmentRepository assignments;

    public EmployeeAccessService(ManagerAssignmentRepository assignments) {
        this.assignments = assignments;
    }

    public boolean canRead(AuthenticatedUser user, Long employeeId) {
        if (employeeId == null) {
            return user.has(Permissions.EMPLOYEE_READ_ALL);
        }
        if (user.has(Permissions.EMPLOYEE_READ_ALL)) {
            return true;
        }
        if (user.has(Permissions.EMPLOYEE_READ_ASSIGNED)
                && assignments.existsByManagerUserIdAndEmployeeId(user.id(), employeeId)) {
            return true;
        }
        return user.has(Permissions.SELF_READ) && employeeId.equals(user.employeeId());
    }

    public void requireRead(AuthenticatedUser user, Long employeeId) {
        if (!canRead(user, employeeId)) {
            // 404 rather than 403 so callers cannot probe which employee ids exist.
            throw ApiException.notFound("Employee", employeeId);
        }
    }

    /** True when the user is restricted to a subset of employees (and lists must be filtered). */
    public boolean isScoped(AuthenticatedUser user) {
        return !user.has(Permissions.EMPLOYEE_READ_ALL);
    }

    /** Specification restricting an employee query to what {@code user} may see. */
    public Specification<Employee> visibleEmployees(AuthenticatedUser user) {
        if (!isScoped(user)) {
            return (root, q, cb) -> cb.conjunction();
        }
        return (root, query, cb) -> {
            var predicates = cb.disjunction();
            if (user.has(Permissions.EMPLOYEE_READ_ASSIGNED)) {
                Subquery<Long> sub = query.subquery(Long.class);
                var ma = sub.from(ManagerAssignment.class);
                sub.select(ma.get("employeeId")).where(cb.equal(ma.get("managerUserId"), user.id()));
                predicates = cb.or(predicates, root.get("id").in(sub));
            }
            if (user.has(Permissions.SELF_READ) && user.employeeId() != null) {
                predicates = cb.or(predicates, cb.equal(root.get("id"), user.employeeId()));
            }
            return predicates;
        };
    }
}
