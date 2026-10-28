package com.employeemonitor.api.realtime;

import com.employeemonitor.api.employees.ManagerAssignment;
import com.employeemonitor.api.employees.ManagerAssignmentRepository;
import com.employeemonitor.api.security.JwtService;
import com.employeemonitor.api.users.Permissions;
import com.employeemonitor.api.users.User;
import com.employeemonitor.api.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authenticates the WebSocket handshake with the user JWT (Authorization header or {@code access_token} query
 * param), requires REPORT_READ, and captures the connection's employee visibility so events can be filtered.
 * Rejecting here means no unauthenticated socket is ever established.
 */
@Component
public class AuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String PRINCIPAL_ATTR = "wsPrincipal";

    private final JwtService jwtService;
    private final UserRepository users;
    private final ManagerAssignmentRepository assignments;

    public AuthHandshakeInterceptor(JwtService jwtService, UserRepository users,
                                    ManagerAssignmentRepository assignments) {
        this.jwtService = jwtService;
        this.users = users;
        this.assignments = assignments;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null) {
            return reject(response);
        }
        WsPrincipal principal = jwtService.validateAccessToken(token)
                .flatMap(users::findById)
                .filter(User::isEnabled)
                .filter(u -> u.permissionNames().contains(Permissions.REPORT_READ))
                .map(this::toPrincipal)
                .orElse(null);
        if (principal == null) {
            return reject(response);
        }
        attributes.put(PRINCIPAL_ATTR, principal);
        return true;
    }

    private WsPrincipal toPrincipal(User user) {
        boolean seeAll = user.permissionNames().contains(Permissions.EMPLOYEE_READ_ALL);
        Set<Long> ids = new HashSet<>();
        if (!seeAll) {
            if (user.permissionNames().contains(Permissions.EMPLOYEE_READ_ASSIGNED)) {
                ids.addAll(assignments.findByManagerUserId(user.getId()).stream()
                        .map(ManagerAssignment::getEmployeeId).collect(Collectors.toSet()));
            }
            if (user.getEmployeeId() != null) {
                ids.add(user.getEmployeeId());
            }
        }
        return new WsPrincipal(user.getId(), user.getUsername(), seeAll, ids);
    }

    private static String extractToken(ServerHttpRequest request) {
        var auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        if (request instanceof ServletServerHttpRequest servlet) {
            String q = servlet.getServletRequest().getParameter("access_token");
            if (q != null && !q.isBlank()) {
                return q.trim();
            }
        }
        return null;
    }

    private static boolean reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }
}
