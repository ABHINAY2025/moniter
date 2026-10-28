package com.employeemonitor.api.liveview;

import com.employeemonitor.api.common.SecureTokens;
import com.employeemonitor.api.devices.DeviceTokenAuthenticator;
import com.employeemonitor.api.security.DevicePrincipal;
import com.employeemonitor.api.security.JwtService;
import com.employeemonitor.api.users.Permissions;
import com.employeemonitor.api.users.User;
import com.employeemonitor.api.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.UUID;

/**
 * Authenticates the live-view signaling handshake. Two kinds of participant connect to {@code /ws/live-view}:
 * <ul>
 *   <li><b>Agent</b>: a {@code dvt_} device token, no session id — registered as its device's media endpoint.</li>
 *   <li><b>Admin</b>: a user JWT with LIVE_VIEW plus {@code ?sessionId=}, where the session must be the caller's
 *       own (or the caller holds EMPLOYEE_READ_ALL) and not yet ended.</li>
 * </ul>
 */
@Component
public class LiveViewHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ROLE = "role";
    public static final String DEVICE_ID = "deviceId";
    public static final String SESSION_ID = "sessionId";
    public static final String USER_ID = "userId";
    public static final String ROLE_AGENT = "AGENT";
    public static final String ROLE_ADMIN = "ADMIN";

    private final JwtService jwtService;
    private final UserRepository users;
    private final DeviceTokenAuthenticator deviceTokenAuthenticator;
    private final LiveViewSessionRepository sessions;

    public LiveViewHandshakeInterceptor(JwtService jwtService, UserRepository users,
                                        DeviceTokenAuthenticator deviceTokenAuthenticator,
                                        LiveViewSessionRepository sessions) {
        this.jwtService = jwtService;
        this.users = users;
        this.deviceTokenAuthenticator = deviceTokenAuthenticator;
        this.sessions = sessions;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                                   Map<String, Object> attributes) {
        String token = extractToken(request);
        if (token == null) {
            return reject(response);
        }
        if (token.startsWith(SecureTokens.DEVICE_PREFIX)) {
            return authenticateAgent(token, attributes, response);
        }
        return authenticateAdmin(token, param(request, "sessionId"), attributes, response);
    }

    private boolean authenticateAgent(String token, Map<String, Object> attributes, ServerHttpResponse response) {
        DevicePrincipal device = deviceTokenAuthenticator.authenticate(token).orElse(null);
        if (device == null) {
            return reject(response);
        }
        attributes.put(ROLE, ROLE_AGENT);
        attributes.put(DEVICE_ID, device.deviceId());
        return true;
    }

    private boolean authenticateAdmin(String token, String sessionIdParam, Map<String, Object> attributes,
                                      ServerHttpResponse response) {
        if (sessionIdParam == null) {
            return reject(response);
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdParam);
        } catch (IllegalArgumentException e) {
            return reject(response);
        }
        User user = jwtService.validateAccessToken(token)
                .flatMap(users::findById)
                .filter(User::isEnabled)
                .filter(u -> u.permissionNames().contains(Permissions.LIVE_VIEW))
                .orElse(null);
        if (user == null) {
            return reject(response);
        }
        LiveViewSession session = sessions.findBySessionId(sessionId).orElse(null);
        if (session == null || session.isEnded()) {
            return reject(response);
        }
        boolean owner = session.getAdminUserId() != null && session.getAdminUserId().equals(user.getId());
        if (!owner && !user.permissionNames().contains(Permissions.EMPLOYEE_READ_ALL)) {
            return reject(response);
        }
        attributes.put(ROLE, ROLE_ADMIN);
        attributes.put(USER_ID, user.getId());
        attributes.put(SESSION_ID, sessionId);
        attributes.put(DEVICE_ID, session.getDeviceId());
        return true;
    }

    private static String extractToken(ServerHttpRequest request) {
        var auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return auth.substring(7).trim();
        }
        return param(request, "access_token");
    }

    private static String param(ServerHttpRequest request, String name) {
        if (request instanceof ServletServerHttpRequest servlet) {
            String value = servlet.getServletRequest().getParameter(name);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static boolean reject(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler,
                               Exception exception) {
    }
}
