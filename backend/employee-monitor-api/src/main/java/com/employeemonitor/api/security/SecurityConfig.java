package com.employeemonitor.api.security;

import com.employeemonitor.api.common.GlobalExceptionHandler;
import com.employeemonitor.api.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final AppProperties.Security props;
    private final ObjectMapper objectMapper;

    public SecurityConfig(AppProperties.Security props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenAuthenticationFilter bearerFilter)
            throws Exception {
        http
                // Stateless bearer-token API: no cookies, so CSRF protection is not applicable.
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'")))
                .authorizeHttpRequests(auth -> auth
                        // Logout only needs the refresh token being revoked (the access token may have expired).
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/devices/register").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        // The WebSocket handshake is authenticated by AuthHandshakeInterceptor (JWT + REPORT_READ).
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/agent/**").hasRole("DEVICE")
                        // Coarse URL-level checks run before request bodies are bound/validated, so callers without
                        // the permission get 403 rather than validation details. @PreAuthorize remains the
                        // fine-grained second layer on every controller method.
                        .requestMatchers("/api/users/**").hasAuthority("USER_MANAGE")
                        .requestMatchers("/api/audit-logs/**").hasAuthority("AUDIT_READ")
                        .requestMatchers("/api/enrollment-tokens/**").hasAuthority("ENROLLMENT_MANAGE")
                        .requestMatchers(HttpMethod.PUT, "/api/policies/**").hasAuthority("POLICY_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/policies/**").hasAuthority("POLICY_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/employees/**").hasAuthority("EMPLOYEE_WRITE")
                        .requestMatchers(HttpMethod.PATCH, "/api/employees/**").hasAuthority("EMPLOYEE_WRITE")
                        .requestMatchers(HttpMethod.POST, "/api/departments/**").hasAuthority("DEPARTMENT_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/departments/**").hasAuthority("DEPARTMENT_WRITE")
                        .requestMatchers(HttpMethod.PUT, "/api/devices/**").hasAuthority("DEVICE_WRITE")
                        .requestMatchers(HttpMethod.DELETE, "/api/devices/**").hasAuthority("DEVICE_WRITE")
                        .requestMatchers("/api/**").hasRole("USER")
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeProblem(res, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeProblem(res, HttpStatus.FORBIDDEN, "FORBIDDEN",
                                        "You do not have permission to perform this action")))
                .addFilterBefore(new HttpsEnforcementFilter(props.requireHttps(), objectMapper),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(bearerFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** The bearer filter is a bean; stop Boot from also registering it in the servlet chain. */
    @Bean
    FilterRegistrationBean<BearerTokenAuthenticationFilter> disableBearerFilterAutoRegistration(
            BearerTokenAuthenticationFilter filter) {
        FilterRegistrationBean<BearerTokenAuthenticationFilter> reg = new FilterRegistrationBean<>(filter);
        reg.setEnabled(false);
        return reg;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.corsAllowedOrigins();
        if (!origins.isEmpty()) {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(origins);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            cfg.setMaxAge(3600L);
            source.registerCorsConfiguration("/api/**", cfg);
        }
        return source;
    }

    private void writeProblem(HttpServletResponse res, HttpStatus status, String code, String detail)
            throws IOException {
        ProblemDetail pd = GlobalExceptionHandler.problem(status, code, detail);
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(res.getOutputStream(), pd);
    }
}
