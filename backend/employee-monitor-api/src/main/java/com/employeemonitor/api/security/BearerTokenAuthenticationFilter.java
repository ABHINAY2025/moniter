package com.employeemonitor.api.security;

import com.employeemonitor.api.common.SecureTokens;
import com.employeemonitor.api.devices.DeviceTokenAuthenticator;
import com.employeemonitor.api.users.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Authenticates {@code Authorization: Bearer ...} requests.
 * <ul>
 *   <li>{@code dvt_...} tokens are device tokens and yield a {@link DevicePrincipal} with ROLE_DEVICE.</li>
 *   <li>Anything else is treated as a user access JWT and yields an {@link AuthenticatedUser} with ROLE_USER
 *       plus one authority per permission. The user is re-loaded on every request so that disabling an
 *       account or changing roles takes effect immediately.</li>
 * </ul>
 * Invalid credentials leave the context unauthenticated; the entry point then returns 401.
 */
@Component
public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final DeviceTokenAuthenticator deviceTokenAuthenticator;

    public BearerTokenAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                           DeviceTokenAuthenticator deviceTokenAuthenticator) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.deviceTokenAuthenticator = deviceTokenAuthenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String token = header.substring(7).trim();
            if (token.startsWith(SecureTokens.DEVICE_PREFIX)) {
                authenticateDevice(token, request);
            } else {
                authenticateUser(token, request);
            }
        }
        chain.doFilter(request, response);
    }

    private void authenticateDevice(String token, HttpServletRequest request) {
        deviceTokenAuthenticator.authenticate(token).ifPresent(device -> {
            var auth = new UsernamePasswordAuthenticationToken(device, null,
                    List.of(new SimpleGrantedAuthority("ROLE_DEVICE")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        });
    }

    private void authenticateUser(String token, HttpServletRequest request) {
        jwtService.validateAccessToken(token)
                .flatMap(userRepository::findById)
                .filter(user -> user.isEnabled())
                .ifPresent(user -> {
                    AuthenticatedUser principal = AuthenticatedUser.from(user);
                    List<GrantedAuthority> authorities = new ArrayList<>();
                    authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                    principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
                    principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                    var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    SecurityContextHolder.getContext().setAuthentication(auth);
                });
    }
}
