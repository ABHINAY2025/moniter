package com.employeemonitor.api.devices;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeviceIntegrationTest extends IntegrationTestBase {

    @Test
    void registrationRequiresValidEnrollmentToken() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("enrollmentToken", "enr_invalid");
        body.put("hostname", "PC-1");
        body.put("agentVersion", "1.0.0");
        call(post("/api/devices/register"), null, body)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ENROLLMENT_TOKEN"));
    }

    @Test
    void registrationIssuesDeviceTokenAssignsEmployeeAndReturnsPolicy() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Rahul Kumar");
        String enrollment = createEnrollmentToken(admin, employee, 1);

        JsonNode reg = registerDevice(enrollment, "RAHUL-PC", UUID.randomUUID().toString());
        assertThat(reg.get("deviceCode").asText()).matches("DEV-\\d{5}");
        assertThat(reg.get("deviceToken").asText()).startsWith("dvt_");
        assertThat(reg.at("/employee/id").asLong()).isEqualTo(employee);
        assertThat(reg.at("/policy/idleThresholdSeconds").asInt()).isEqualTo(300);
        assertThat(reg.get("policyRevision").asText()).isNotBlank();

        // Single-use token cannot be replayed.
        Map<String, Object> again = new HashMap<>();
        again.put("enrollmentToken", enrollment);
        again.put("hostname", "OTHER-PC");
        again.put("agentVersion", "1.0.0");
        call(post("/api/devices/register"), null, again).andExpect(status().isUnauthorized());

        JsonNode audit = getJson("/api/audit-logs?action=DEVICE_REGISTERED&targetId=" + reg.get("deviceId").asLong(),
                admin);
        assertThat(audit.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void heartbeatUpdatesPresenceAndDashboard() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Presence Person");
        JsonNode reg = registerDevice(createEnrollmentToken(admin, employee, 1), "PRESENCE-PC", null);
        String deviceToken = reg.get("deviceToken").asText();
        long deviceId = reg.get("deviceId").asLong();

        JsonNode before = getJson("/api/devices/" + deviceId, admin);
        assertThat(before.at("/presence/status").asText()).isEqualTo("OFFLINE");

        JsonNode hb = postJson("/api/agent/heartbeat", deviceToken, Map.of("status", "ACTIVE"), 200);
        assertThat(hb.get("policyRevision").asText()).isEqualTo(reg.get("policyRevision").asText());
        assertThat(hb.get("nextHeartbeatSeconds").asInt()).isEqualTo(30);

        JsonNode after = getJson("/api/devices/" + deviceId, admin);
        assertThat(after.at("/presence/status").asText()).isEqualTo("ACTIVE");
        assertThat(after.at("/presence/online").asBoolean()).isTrue();
        assertThat(after.get("lastSeenAt").isNull()).isFalse();

        postJson("/api/agent/heartbeat", deviceToken, Map.of("status", "IDLE"), 200);
        JsonNode detail = getJson("/api/employees/" + employee, admin);
        assertThat(detail.at("/devices/0/presence/status").asText()).isEqualTo("IDLE");

        JsonNode summary = getJson("/api/dashboard/summary", admin);
        assertThat(summary.get("idle").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(summary.get("onlineDevices").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode me = getJson("/api/agent/me", deviceToken);
        assertThat(me.get("deviceCode").asText()).isEqualTo(reg.get("deviceCode").asText());
    }

    @Test
    void agentCannotReportOfflineOrInvalidStatus() throws Exception {
        String admin = adminToken();
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "BAD-PC", null)
                .get("deviceToken").asText();
        call(post("/api/agent/heartbeat"), deviceToken, Map.of("status", "SLEEPING"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deviceAndUserCredentialsAreNotInterchangeable() throws Exception {
        String admin = adminToken();
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "SEP-PC", null)
                .get("deviceToken").asText();

        call(post("/api/agent/heartbeat"), admin, Map.of("status", "ACTIVE")).andExpect(status().isForbidden());
        call(get("/api/employees"), deviceToken, null).andExpect(status().isForbidden());
        call(get("/api/agent/policy"), "dvt_forged", null).andExpect(status().isUnauthorized());
    }

    @Test
    void retiringDeviceRevokesItsToken() throws Exception {
        String admin = adminToken();
        JsonNode reg = registerDevice(createEnrollmentToken(admin, null, 1), "RETIRE-PC", null);
        String deviceToken = reg.get("deviceToken").asText();
        getJson("/api/agent/policy", deviceToken);

        deleteReq("/api/devices/" + reg.get("deviceId").asLong(), admin).andExpect(status().isNoContent());

        call(get("/api/agent/policy"), deviceToken, null).andExpect(status().isUnauthorized());
        assertThat(getJson("/api/devices/" + reg.get("deviceId").asLong(), admin).get("status").asText())
                .isEqualTo("RETIRED");
    }

    @Test
    void reinstallOnSameHardwareReusesDeviceAndRotatesToken() throws Exception {
        String admin = adminToken();
        String hardwareId = UUID.randomUUID().toString();
        String enrollment = createEnrollmentToken(admin, null, 2);

        JsonNode first = registerDevice(enrollment, "REINSTALL-PC", hardwareId);
        JsonNode second = registerDevice(enrollment, "REINSTALL-PC", hardwareId);

        assertThat(second.get("deviceId").asLong()).isEqualTo(first.get("deviceId").asLong());
        call(get("/api/agent/policy"), first.get("deviceToken").asText(), null).andExpect(status().isUnauthorized());
        getJson("/api/agent/policy", second.get("deviceToken").asText());
    }

    @Test
    void itAdminAssignsDeviceToEmployee() throws Exception {
        String admin = adminToken();
        UserSession it = createUserAndLogin(admin, Set.of("IT_ADMIN"), null);
        long employee = createEmployee(admin, "Assignee");
        long deviceId = registerDevice(createEnrollmentToken(admin, null, 1), "UNASSIGNED-PC", null)
                .get("deviceId").asLong();

        JsonNode updated = putJson("/api/devices/" + deviceId + "/assignment", it.token(),
                Map.of("employeeId", employee), 200);
        assertThat(updated.at("/employee/id").asLong()).isEqualTo(employee);
    }

    @Test
    void disabledDeviceCannotAuthenticate() throws Exception {
        String admin = adminToken();
        JsonNode reg = registerDevice(createEnrollmentToken(admin, null, 1), "DISABLE-PC", null);
        long deviceId = reg.get("deviceId").asLong();
        putJson("/api/devices/" + deviceId + "/status", admin, Map.of("status", "DISABLED"), 200);
        call(get("/api/agent/policy"), reg.get("deviceToken").asText(), null).andExpect(status().isUnauthorized());

        putJson("/api/devices/" + deviceId + "/status", admin, Map.of("status", "ACTIVE"), 200);
        getJson("/api/agent/policy", reg.get("deviceToken").asText());
    }
}
