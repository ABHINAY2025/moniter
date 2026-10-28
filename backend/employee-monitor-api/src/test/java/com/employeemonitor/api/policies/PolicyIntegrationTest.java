package com.employeemonitor.api.policies;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PolicyIntegrationTest extends IntegrationTestBase {

    @Test
    void deviceOverrideChangesRevisionSeenByAgent() throws Exception {
        String admin = adminToken();
        JsonNode reg = registerDevice(createEnrollmentToken(admin, null, 1), "POLICY-PC", null);
        long deviceId = reg.get("deviceId").asLong();
        String deviceToken = reg.get("deviceToken").asText();

        JsonNode effective = getJson("/api/policies/" + deviceId, admin);
        assertThat(effective.get("source").asText()).isEqualTo("DEFAULT");

        ObjectNode settings = (ObjectNode) effective.get("settings").deepCopy();
        settings.put("screenshots", true);
        settings.put("screenshotIntervalSeconds", 600);
        JsonNode override = putJson("/api/policies/" + deviceId, admin, settings, 200);
        assertThat(override.get("source").asText()).isEqualTo("DEVICE_OVERRIDE");
        assertThat(override.get("revision").asText()).isNotEqualTo(reg.get("policyRevision").asText());

        JsonNode hb = postJson("/api/agent/heartbeat", deviceToken, Map.of("status", "ACTIVE"), 200);
        assertThat(hb.get("policyRevision").asText()).isEqualTo(override.get("revision").asText());

        JsonNode agentPolicy = getJson("/api/agent/policy", deviceToken);
        assertThat(agentPolicy.at("/settings/screenshots").asBoolean()).isTrue();
        assertThat(agentPolicy.at("/settings/screenshotIntervalSeconds").asInt()).isEqualTo(600);

        // Updating the override again bumps the revision.
        settings.put("usbMonitoring", false);
        JsonNode override2 = putJson("/api/policies/" + deviceId, admin, settings, 200);
        assertThat(override2.get("revision").asText()).isNotEqualTo(override.get("revision").asText());

        JsonNode audit = getJson("/api/audit-logs?action=POLICY_CHANGED&targetType=DEVICE&targetId=" + deviceId, admin);
        assertThat(audit.get("totalElements").asLong()).isEqualTo(2);

        // Reset falls back to default.
        JsonNode reset = read(deleteReq("/api/policies/" + deviceId, admin).andExpect(status().isOk()));
        assertThat(reset.get("source").asText()).isEqualTo("DEFAULT");
    }

    @Test
    void invalidPolicyIsRejected() throws Exception {
        String admin = adminToken();
        ObjectNode settings = (ObjectNode) getJson("/api/policies/default", admin).get("settings").deepCopy();
        settings.put("screenshotIntervalSeconds", 7);
        settings.put("timezone", "Mars/Olympus");
        settings.put("monitoringStart", "18:00");
        settings.put("monitoringEnd", "09:00");

        call(put("/api/policies/default"), admin, settings)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void policyJsonDoesNotLeakValidatorProperties() throws Exception {
        JsonNode policy = getJson("/api/policies/default", adminToken());
        assertThat(policy.get("settings").has("timezoneValid")).isFalse();
        assertThat(policy.get("settings").has("idleThresholdSeconds")).isTrue();
    }
}
