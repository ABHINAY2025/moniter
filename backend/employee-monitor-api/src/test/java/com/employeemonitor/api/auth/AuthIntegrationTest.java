package com.employeemonitor.api.auth;

import com.employeemonitor.api.IntegrationTestBase;
import com.employeemonitor.api.audit.AuditAction;
import com.employeemonitor.api.audit.AuditLogRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTestBase {

    @Autowired
    AuditLogRepository auditLogs;

    @Test
    void bootstrapAdminCanLoginAndReadProfile() throws Exception {
        JsonNode tokens = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        assertThat(tokens.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(tokens.get("refreshToken").asText()).startsWith("rt_");
        assertThat(tokens.at("/user/roles").toString()).contains("SUPER_ADMIN");

        JsonNode me = getJson("/api/auth/me", tokens.get("accessToken").asText());
        assertThat(me.get("username").asText()).isEqualTo(ADMIN_USERNAME);
        assertThat(me.get("permissions").toString()).contains("AUDIT_READ", "USER_MANAGE");
    }

    @Test
    void wrongPasswordIsRejectedAndAudited() throws Exception {
        long before = auditLogs.count();
        call(post("/api/auth/login"), null, Map.of("username", ADMIN_USERNAME, "password", "nope-nope-nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertThat(auditLogs.findAll()).anyMatch(a -> a.getAction().equals(AuditAction.LOGIN_FAILURE));
        assertThat(auditLogs.count()).isGreaterThan(before);
    }

    @Test
    void unknownUserGetsSameErrorAsWrongPassword() throws Exception {
        call(post("/api/auth/login"), null, Map.of("username", "ghost-user", "password", "whatever-password"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        call(get("/api/employees"), null, null).andExpect(status().isUnauthorized());
        call(get("/api/employees"), "not-a-jwt", null).andExpect(status().isUnauthorized());
    }

    @Test
    void refreshRotatesTokensAndReuseRevokesTheFamily() throws Exception {
        JsonNode first = login(ADMIN_USERNAME, ADMIN_PASSWORD);
        String rt1 = first.get("refreshToken").asText();

        JsonNode second = postJson("/api/auth/refresh", null, Map.of("refreshToken", rt1), 200);
        String rt2 = second.get("refreshToken").asText();
        assertThat(rt2).isNotEqualTo(rt1);
        getJson("/api/auth/me", second.get("accessToken").asText());

        // Re-using the rotated token is treated as theft ...
        call(post("/api/auth/refresh"), null, Map.of("refreshToken", rt1))
                .andExpect(status().isUnauthorized());
        // ... and kills the legitimate successor too.
        call(post("/api/auth/refresh"), null, Map.of("refreshToken", rt2))
                .andExpect(status().isUnauthorized());
        assertThat(auditLogs.findAll()).anyMatch(a -> a.getAction().equals(AuditAction.REFRESH_TOKEN_REUSE));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String rt = login(ADMIN_USERNAME, ADMIN_PASSWORD).get("refreshToken").asText();
        call(post("/api/auth/logout"), null, Map.of("refreshToken", rt)).andExpect(status().isNoContent());
        call(post("/api/auth/refresh"), null, Map.of("refreshToken", rt)).andExpect(status().isUnauthorized());
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        UserSession user = createUserAndLogin(adminToken(), Set.of("HR_ADMIN"), null);
        for (int i = 0; i < 5; i++) {
            call(post("/api/auth/login"), null, Map.of("username", user.username(), "password", "wrong-password-x"))
                    .andExpect(status().isUnauthorized());
        }
        // Correct password is now refused while the lock is active.
        call(post("/api/auth/login"), null, Map.of("username", user.username(), "password", DEFAULT_PASSWORD))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserLosesAccessImmediately() throws Exception {
        String admin = adminToken();
        UserSession user = createUserAndLogin(admin, Set.of("HR_ADMIN"), null);
        getJson("/api/auth/me", user.token());

        patchJson("/api/users/" + user.id(), admin, Map.of("enabled", false), 200);

        call(get("/api/auth/me"), user.token(), null).andExpect(status().isUnauthorized());
    }

    @Test
    void validationErrorsAreReported() throws Exception {
        call(post("/api/auth/login"), null, Map.of("username", "", "password", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.username").exists());
    }
}
