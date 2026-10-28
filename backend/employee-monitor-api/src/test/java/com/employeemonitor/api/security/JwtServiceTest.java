package com.employeemonitor.api.security;

import com.employeemonitor.api.config.AppProperties;
import com.employeemonitor.api.users.User;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-that-is-definitely-32-bytes";

    private static AppProperties.Security props(String secret) {
        return new AppProperties.Security(secret, "employee-monitor", Duration.ofMinutes(15), Duration.ofDays(7),
                5, Duration.ofMinutes(15), 10, 20, List.of(), false);
    }

    private static User user(long id) {
        User u = new User("rahul", "rahul@example.test", "Rahul", "x");
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    void issuedTokenValidates() {
        JwtService jwt = new JwtService(props(SECRET), Clock.systemUTC());
        JwtService.IssuedToken token = jwt.issueAccessToken(user(42));
        assertThat(jwt.validateAccessToken(token.token())).contains(42L);
    }

    @Test
    void expiredTokenIsRejected() {
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        JwtService issuer = new JwtService(props(SECRET), Clock.fixed(issued, ZoneOffset.UTC));
        String token = issuer.issueAccessToken(user(1)).token();

        JwtService later = new JwtService(props(SECRET), Clock.fixed(issued.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        assertThat(later.validateAccessToken(token)).isEmpty();
    }

    @Test
    void tokenSignedWithOtherKeyIsRejected() {
        String token = new JwtService(props(SECRET), Clock.systemUTC()).issueAccessToken(user(1)).token();
        JwtService other = new JwtService(props("another-secret-that-is-also-32-bytes-long"), Clock.systemUTC());
        assertThat(other.validateAccessToken(token)).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService jwt = new JwtService(props(SECRET), Clock.systemUTC());
        String token = jwt.issueAccessToken(user(1)).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(jwt.validateAccessToken(tampered)).isEmpty();
        assertThat(jwt.validateAccessToken("garbage")).isEmpty();
    }

    @Test
    void shortSecretIsRefusedAtStartup() {
        assertThatThrownBy(() -> new JwtService(props("too-short"), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(props(null), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
