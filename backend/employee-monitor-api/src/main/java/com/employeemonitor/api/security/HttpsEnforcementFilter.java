package com.employeemonitor.api.security;

import com.employeemonitor.api.common.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Rejects plain-HTTP requests when REQUIRE_HTTPS=true. TLS is normally terminated at the reverse proxy;
 * with server.forward-headers-strategy=framework the proxy's X-Forwarded-Proto makes {@code isSecure()} true.
 * Health probes stay reachable for container orchestration.
 */
public class HttpsEnforcementFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final ObjectMapper objectMapper;

    public HttpsEnforcementFilter(boolean enabled, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || request.getRequestURI().startsWith("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!request.isSecure()) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(),
                    GlobalExceptionHandler.problem(HttpStatus.FORBIDDEN, "HTTPS_REQUIRED", "HTTPS is required"));
            return;
        }
        chain.doFilter(request, response);
    }
}
