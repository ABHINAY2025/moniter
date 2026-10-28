package com.employeemonitor.api.users;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.auth.RefreshTokenRepository;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.PageResponse;
import com.employeemonitor.api.employees.Employee;
import com.employeemonitor.api.employees.EmployeeRepository;
import com.employeemonitor.api.employees.ManagerAssignment;
import com.employeemonitor.api.employees.ManagerAssignmentRepository;
import com.employeemonitor.api.security.CurrentUser;
import com.employeemonitor.api.users.UserDtos.CreateUserRequest;
import com.employeemonitor.api.users.UserDtos.ManagerAssignmentResponse;
import com.employeemonitor.api.users.UserDtos.UpdateUserRequest;
import com.employeemonitor.api.users.UserDtos.UserResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class UserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final EmployeeRepository employees;
    private final ManagerAssignmentRepository assignments;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    public UserService(UserRepository users, RoleRepository roles, EmployeeRepository employees,
                       ManagerAssignmentRepository assignments, RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder, AuditService audit, Clock clock) {
        this.users = users;
        this.roles = roles;
        this.employees = employees;
        this.assignments = assignments;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(String q, Pageable pageable) {
        return PageResponse.of(users.search(q == null || q.isBlank() ? null : q.trim(), pageable), UserResponse::from);
    }

    @Transactional(readOnly = true)
    public UserResponse get(Long id) {
        return UserResponse.from(load(id));
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        if (users.existsByUsernameIgnoreCase(request.username())) {
            throw ApiException.conflict("Username already exists");
        }
        if (users.existsByEmailIgnoreCase(request.email())) {
            throw ApiException.conflict("Email already exists");
        }
        User user = new User(request.username().trim(), request.email().trim().toLowerCase(),
                request.fullName().trim(), passwordEncoder.encode(request.password()));
        user.setRoles(resolveRoles(request.roles()));
        if (request.employeeId() != null) {
            linkEmployee(user, request.employeeId());
        }
        user = users.save(user);
        audit.record(AuditAction.USER_CREATED, "USER", user.getId(),
                Map.of("username", user.getUsername(), "roles", user.roleNames()));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest request) {
        User user = load(id);
        Long actorId = CurrentUser.require().id();
        Map<String, Object> changed = new HashMap<>();
        boolean revokeSessions = false;

        if (request.email() != null && !request.email().equalsIgnoreCase(user.getEmail())) {
            if (users.existsByEmailIgnoreCase(request.email())) {
                throw ApiException.conflict("Email already exists");
            }
            user.setEmail(request.email().trim().toLowerCase());
            changed.put("email", true);
        }
        if (request.fullName() != null) {
            user.setFullName(request.fullName().trim());
            changed.put("fullName", true);
        }
        if (request.enabled() != null && request.enabled() != user.isEnabled()) {
            if (!request.enabled() && id.equals(actorId)) {
                throw ApiException.badRequest("You cannot disable your own account");
            }
            user.setEnabled(request.enabled());
            changed.put("enabled", request.enabled());
            revokeSessions |= !request.enabled();
        }
        if (request.roles() != null) {
            Set<Role> newRoles = resolveRoles(request.roles());
            boolean losingSuperAdmin = user.roleNames().contains(Permissions.ROLE_SUPER_ADMIN)
                    && newRoles.stream().noneMatch(r -> r.getName().equals(Permissions.ROLE_SUPER_ADMIN));
            if (losingSuperAdmin && id.equals(actorId)) {
                throw ApiException.badRequest("You cannot remove your own SUPER_ADMIN role");
            }
            changed.put("roles", Map.of("from", user.roleNames(), "to",
                    newRoles.stream().map(Role::getName).toList()));
            user.setRoles(newRoles);
        }
        if (Boolean.TRUE.equals(request.clearEmployee())) {
            user.setEmployeeId(null);
            changed.put("employeeId", "cleared");
        } else if (request.employeeId() != null) {
            linkEmployee(user, request.employeeId());
            changed.put("employeeId", request.employeeId());
        }
        if (request.newPassword() != null) {
            user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
            user.setLockedUntil(null);
            changed.put("passwordReset", true);
            revokeSessions = true;
        }
        if (revokeSessions) {
            refreshTokens.revokeAllForUser(id, clock.instant());
        }
        audit.record(AuditAction.USER_UPDATED, "USER", id, Map.of("changed", changed));
        return UserResponse.from(user);
    }

    // ---------- Manager assignments ----------

    @Transactional(readOnly = true)
    public List<ManagerAssignmentResponse> assignmentsFor(Long managerUserId) {
        load(managerUserId);
        return assignments.findByManagerUserId(managerUserId).stream()
                .map(a -> new ManagerAssignmentResponse(a.getManagerUserId(), a.getEmployeeId(),
                        employees.findById(a.getEmployeeId()).map(Employee::getFullName).orElse(null),
                        a.isLiveViewAllowed(), a.getCreatedAt()))
                .toList();
    }

    @Transactional
    public ManagerAssignmentResponse assign(Long managerUserId, Long employeeId, boolean liveViewAllowed) {
        User manager = load(managerUserId);
        if (!manager.roleNames().contains(Permissions.ROLE_MANAGER)) {
            throw ApiException.badRequest("User does not have the MANAGER role");
        }
        Employee employee = employees.findById(employeeId)
                .orElseThrow(() -> ApiException.notFound("Employee", employeeId));
        ManagerAssignment assignment = assignments.findById(new ManagerAssignment.Key(managerUserId, employeeId))
                .orElseGet(() -> new ManagerAssignment(managerUserId, employeeId, liveViewAllowed,
                        CurrentUser.require().id()));
        assignment.setLiveViewAllowed(liveViewAllowed);
        assignment = assignments.save(assignment);
        audit.record(AuditAction.MANAGER_ASSIGNMENT_SET, "USER", managerUserId,
                Map.of("employeeId", employeeId, "liveViewAllowed", liveViewAllowed));
        return new ManagerAssignmentResponse(managerUserId, employeeId, employee.getFullName(),
                assignment.isLiveViewAllowed(), assignment.getCreatedAt());
    }

    @Transactional
    public void unassign(Long managerUserId, Long employeeId) {
        ManagerAssignment.Key key = new ManagerAssignment.Key(managerUserId, employeeId);
        if (!assignments.existsById(key)) {
            throw ApiException.notFound("Assignment", managerUserId + "/" + employeeId);
        }
        assignments.deleteById(key);
        audit.record(AuditAction.MANAGER_ASSIGNMENT_REMOVED, "USER", managerUserId, Map.of("employeeId", employeeId));
    }

    private void linkEmployee(User user, Long employeeId) {
        if (!employees.existsById(employeeId)) {
            throw ApiException.notFound("Employee", employeeId);
        }
        users.findByEmployeeId(employeeId)
                .filter(other -> !other.getId().equals(user.getId()))
                .ifPresent(other -> {
                    throw ApiException.conflict("Employee is already linked to another user");
                });
        user.setEmployeeId(employeeId);
    }

    private Set<Role> resolveRoles(Set<String> names) {
        Set<String> normalized = new HashSet<>();
        names.forEach(n -> normalized.add(n.trim().toUpperCase()));
        List<Role> found = roles.findByNameIn(normalized);
        if (found.size() != normalized.size()) {
            throw ApiException.badRequest("Unknown role in " + normalized);
        }
        return new HashSet<>(found);
    }

    private User load(Long id) {
        return users.findById(id).orElseThrow(() -> ApiException.notFound("User", id));
    }
}
