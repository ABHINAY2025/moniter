package com.employeemonitor.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Typed application configuration. Every secret is supplied through environment variables;
 * nothing sensitive has a default value.
 */
public final class AppProperties {

    private AppProperties() {
    }

    @ConfigurationProperties("app.security")
    public record Security(
            String jwtSecret,
            String jwtIssuer,
            Duration accessTokenTtl,
            Duration refreshTokenTtl,
            int maxFailedLogins,
            Duration lockoutDuration,
            int loginRateLimitPerMinute,
            int registerRateLimitPerMinute,
            List<String> corsAllowedOrigins,
            boolean requireHttps) {

        public List<String> corsAllowedOrigins() {
            return corsAllowedOrigins == null ? List.of()
                    : corsAllowedOrigins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    @ConfigurationProperties("app.bootstrap")
    public record Bootstrap(String adminUsername, String adminEmail, String adminPassword) {
    }

    @ConfigurationProperties("app.presence")
    public record Presence(Duration offlineTimeout, long sweepIntervalMs) {
    }

    @ConfigurationProperties("app.devices")
    public record Devices(Duration tokenTtl) {
    }

    @ConfigurationProperties("app.screenshots")
    public record Screenshots(String directory, String encryptionKey, long maxBytes) {

        public String directory() {
            return directory == null || directory.isBlank() ? "./data/screenshots" : directory;
        }

        public long maxBytes() {
            return maxBytes <= 0 ? 8 * 1024 * 1024 : maxBytes;
        }
    }

    @ConfigurationProperties("app.updates")
    public record Updates(String directory, long maxBytes) {

        public String directory() {
            return directory == null || directory.isBlank() ? "./data/updates" : directory;
        }

        public long maxBytes() {
            return maxBytes <= 0 ? 200L * 1024 * 1024 : maxBytes;
        }
    }
}
