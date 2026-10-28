package com.employeemonitor.api.security;

import com.employeemonitor.api.users.User;

import java.util.Set;

/** Principal for an authenticated human user (admin app or self-service). */
public record AuthenticatedUser(
        Long id,
        String username,
        Long employeeId,
        Set<String> roles,
        Set<String> permissions) {

    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getUsername(), user.getEmployeeId(),
                user.roleNames(), user.permissionNames());
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }
}
