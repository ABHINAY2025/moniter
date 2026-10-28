package com.employeemonitor.api.users;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

public final class UserDtos {

    private UserDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 80) @Pattern(regexp = "[A-Za-z0-9._@-]+",
                    message = "may contain letters, digits and . _ @ - only") String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 160) String fullName,
            @NotBlank @Size(min = 12, max = 200, message = "must be at least 12 characters") String password,
            @NotEmpty Set<String> roles,
            Long employeeId) {
    }

    /** Partial update: null fields are left unchanged. */
    public record UpdateUserRequest(
            @Email @Size(max = 255) String email,
            @Size(min = 1, max = 160) String fullName,
            Boolean enabled,
            Set<String> roles,
            Long employeeId,
            Boolean clearEmployee,
            @Size(min = 12, max = 200, message = "must be at least 12 characters") String newPassword) {
    }

    public record UserResponse(Long id, String username, String email, String fullName, boolean enabled,
                               Long employeeId, Set<String> roles, Instant lastLoginAt, Instant lockedUntil,
                               Instant createdAt) {
        public static UserResponse from(User u) {
            return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getFullName(), u.isEnabled(),
                    u.getEmployeeId(), u.roleNames(), u.getLastLoginAt(), u.getLockedUntil(), u.getCreatedAt());
        }
    }

    public record ManagerAssignmentRequest(boolean liveViewAllowed) {
    }

    public record ManagerAssignmentResponse(Long managerUserId, Long employeeId, String employeeName,
                                            boolean liveViewAllowed, Instant createdAt) {
    }
}
