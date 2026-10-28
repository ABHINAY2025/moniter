package com.employeemonitor.api.auth;

import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditService;
import com.employeemonitor.api.auth.AuthDtos.TokenResponse;
import com.employeemonitor.api.auth.AuthDtos.UserInfo;
import com.employeemonitor.api.common.ApiException;
import com.employeemonitor.api.common.RateLimiter;
import com.employeemonitor.api.common.RequestContext;
import com.employeemonitor.api.common.SecureTokens;
import com.employeemonitor.api.config.AppProperties;
import com.employeemonitor.api.security.JwtService;
import com.employeemonitor.api.users.User;
import com.employeemonitor.api.users.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    private static final String INVALID_CREDENTIALS = "Invalid username or password";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AppProperties.Security props;
    private final Clock clock;
    private final TransactionTemplate tx;
    /** Hash compared against when the username does not exist, so timing does not reveal valid usernames. */
    private final String dummyHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService audit, RateLimiter rateLimiter,
                       AppProperties.Security props, Clock clock, TransactionTemplate tx) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
        this.tx = tx;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    public TokenResponse login(String username, String password) {
        String ip = RequestContext.clientIp();
        rateLimiter.check("login:ip:" + ip, props.loginRateLimitPerMinute(), Duration.ofMinutes(1));

        // The state change (failed-attempt counter / lockout) must commit even though we then reject the login,
        // so the transaction returns an outcome rather than throwing.
        LoginOutcome outcome = tx.execute(status -> attemptLogin(username, password));
        if (outcome.user() == null) {
            audit.record(outcome.userId(), username, AuditAction.LOGIN_FAILURE, "USER", outcome.userId(), "FAILURE",
                    Map.of("reason", outcome.failureReason()));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", INVALID_CREDENTIALS);
        }
        User user = outcome.user();
        audit.record(user.getId(), user.getUsername(), AuditAction.LOGIN_SUCCESS, "USER", user.getId(), "SUCCESS",
                null);
        return tx.execute(status -> issue(user, UUID.randomUUID(), ip).response());
    }

    private record LoginOutcome(User user, Long userId, String failureReason) {
    }

    private LoginOutcome attemptLogin(String username, String password) {
        Instant now = clock.instant();
        Optional<User> found = users.findByUsernameIgnoreCase(username.trim());
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyHash);
            return new LoginOutcome(null, null, "UNKNOWN_USER");
        }
        User user = found.get();
        if (!user.isEnabled()) {
            passwordEncoder.matches(password, dummyHash);
            return new LoginOutcome(null, user.getId(), "DISABLED");
        }
        if (user.isLocked(now)) {
            passwordEncoder.matches(password, dummyHash);
            return new LoginOutcome(null, user.getId(), "LOCKED");
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            user.recordFailedLogin(props.maxFailedLogins(), now.plus(props.lockoutDuration()));
            return new LoginOutcome(null, user.getId(), user.isLocked(now) ? "BAD_PASSWORD_NOW_LOCKED" : "BAD_PASSWORD");
        }
        user.recordSuccessfulLogin(now);
        return new LoginOutcome(user, user.getId(), null);
    }

    /** Rotates the refresh token. Not rolled back on rejection so that reuse-triggered revocation sticks. */
    @Transactional(noRollbackFor = ApiException.class)
    public TokenResponse refresh(String rawRefreshToken) {
        Instant now = clock.instant();
        RefreshToken current = refreshTokens.findByHashForUpdate(SecureTokens.sha256Hex(rawRefreshToken))
                .orElseThrow(AuthService::invalidRefresh);

        if (current.getRevokedAt() != null) {
            // A rotated/revoked token was presented again: assume it was stolen and kill the whole session family.
            refreshTokens.revokeFamily(current.getFamilyId(), now);
            audit.record(current.getUserId(), null, AuditAction.REFRESH_TOKEN_REUSE, "USER", current.getUserId(),
                    "FAILURE", Map.of("familyId", current.getFamilyId().toString()));
            throw invalidRefresh();
        }
        if (!current.isActive(now)) {
            throw invalidRefresh();
        }
        User user = users.findById(current.getUserId())
                .filter(User::isEnabled)
                .orElseThrow(AuthService::invalidRefresh);

        Issued issued = issue(user, current.getFamilyId(), RequestContext.clientIp());
        current.revoke(now, issued.refreshTokenId());
        return issued.response();
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokens.findByHashForUpdate(SecureTokens.sha256Hex(rawRefreshToken)).ifPresent(token -> {
            refreshTokens.revokeFamily(token.getFamilyId(), clock.instant());
            audit.record(token.getUserId(), null, AuditAction.LOGOUT, "USER", token.getUserId(), "SUCCESS", null);
        });
    }

    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User", userId));
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "Current password is incorrect");
        }
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        refreshTokens.revokeAllForUser(userId, clock.instant());
        audit.record(user.getId(), user.getUsername(), AuditAction.PASSWORD_CHANGED, "USER", user.getId(),
                "SUCCESS", null);
    }

    private record Issued(TokenResponse response, Long refreshTokenId) {
    }

    private Issued issue(User user, UUID familyId, String ip) {
        Instant now = clock.instant();
        JwtService.IssuedToken access = jwtService.issueAccessToken(user);
        String rawRefresh = SecureTokens.generate(SecureTokens.REFRESH_PREFIX);
        Instant refreshExpires = now.plus(props.refreshTokenTtl());
        RefreshToken saved = refreshTokens.save(new RefreshToken(user.getId(), SecureTokens.sha256Hex(rawRefresh),
                familyId, now, refreshExpires, ip));
        return new Issued(new TokenResponse("Bearer", access.token(), access.expiresAt(), rawRefresh, refreshExpires,
                UserInfo.from(user)), saved.getId());
    }

    private static ApiException invalidRefresh() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Refresh token is invalid or expired");
    }

    /** Housekeeping: drop refresh tokens that expired more than a day ago. */
    @Scheduled(cron = "0 17 3 * * *")
    @Transactional
    public void purgeExpiredRefreshTokens() {
        refreshTokens.deleteExpiredBefore(clock.instant().minus(Duration.ofDays(1)));
    }
}
