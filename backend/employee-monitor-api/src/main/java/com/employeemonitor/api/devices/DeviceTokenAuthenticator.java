package com.employeemonitor.api.devices;

import com.employeemonitor.api.common.SecureTokens;
import com.employeemonitor.api.security.DevicePrincipal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Resolves a raw {@code dvt_...} token to an active device. */
@Component
public class DeviceTokenAuthenticator {

    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private final DeviceTokenRepository tokens;
    private final Clock clock;

    public DeviceTokenAuthenticator(DeviceTokenRepository tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Transactional
    public Optional<DevicePrincipal> authenticate(String rawToken) {
        Instant now = clock.instant();
        Optional<DevicePrincipal> principal = tokens.findValidPrincipal(SecureTokens.sha256Hex(rawToken), now,
                Device.Status.ACTIVE);
        // Record usage at most every few minutes to avoid a write on every heartbeat.
        principal.ifPresent(p -> tokens.touch(p.tokenId(), now, now.minus(TOUCH_INTERVAL)));
        return principal;
    }
}
