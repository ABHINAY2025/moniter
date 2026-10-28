package com.employeemonitor.api.auth;

import com.employeemonitor.api.users.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Set;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(
            @NotBlank @Size(max = 80) String username,
            @NotBlank @Size(max = 200) String password) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 200) String refreshToken) {
    }

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 200) String currentPassword,
            @NotBlank @Size(min = 12, max = 200, message = "must be at least 12 characters") String newPassword) {
    }

    public record TokenResponse(
            String tokenType,
            String accessToken,
            Instant accessTokenExpiresAt,
            String refreshToken,
            Instant refreshTokenExpiresAt,
            UserInfo user) {
    }

    public record UserInfo(Long id, String username, String fullName, String email, Long employeeId,
                           Set<String> roles, Set<String> permissions) {
        public static UserInfo from(User u) {
            return new UserInfo(u.getId(), u.getUsername(), u.getFullName(), u.getEmail(), u.getEmployeeId(),
                    u.roleNames(), u.permissionNames());
        }
    }
}
