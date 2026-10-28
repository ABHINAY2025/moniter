package com.employeemonitor.api.screenshots;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ScreenshotIntegrationTest extends IntegrationTestBase {

    // A real 1x1 PNG.
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMCAoFCqzv0AAAAAElFTkSuQmCC";

    private String uploadOne(String deviceToken, String capturedAt, String clientEventId) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("clientEventId", clientEventId);
        body.put("capturedAt", capturedAt);
        body.put("contentType", "image/png");
        body.put("width", 1);
        body.put("height", 1);
        body.put("imageBase64", PNG_BASE64);
        return postJson("/api/agent/screenshots", deviceToken, body, 200).toString();
    }

    @Test
    void uploadListViewAndAudit() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Shot User");
        JsonNode reg = registerDevice(createEnrollmentToken(admin, employee, 1), "SHOT-PC", null);
        String deviceToken = reg.get("deviceToken").asText();

        String capturedAt = "2026-10-06T09:30:00Z";
        JsonNode up = postJson("/api/agent/screenshots", deviceToken, Map.of(
                "clientEventId", UUID.randomUUID().toString(), "capturedAt", capturedAt,
                "contentType", "image/png", "width", 1, "height", 1, "imageBase64", PNG_BASE64), 200);
        assertThat(up.get("duplicate").asBoolean()).isFalse();
        long screenshotId = up.get("id").asLong();

        JsonNode list = getJson("/api/employees/" + employee + "/screenshots?date=2026-10-06&tz=UTC", admin);
        assertThat(list.get("totalElements").asLong()).isEqualTo(1);
        assertThat(list.at("/content/0/contentType").asText()).isEqualTo("image/png");
        assertThat(list.at("/content/0/sizeBytes").asInt()).isEqualTo(Base64.getDecoder().decode(PNG_BASE64).length);

        // View returns the exact original bytes (decrypted round-trip through storage).
        byte[] content = call(get("/api/screenshots/" + screenshotId + "/content"), admin, null)
                .andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getContentType()).startsWith("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(content).isEqualTo(Base64.getDecoder().decode(PNG_BASE64));

        JsonNode audit = getJson("/api/audit-logs?action=SCREENSHOT_VIEWED&targetType=SCREENSHOT&targetId="
                + screenshotId, admin);
        assertThat(audit.get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void duplicateUploadIsIdempotent() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Dup Shot");
        String deviceToken = registerDevice(createEnrollmentToken(admin, employee, 1), "DUPSHOT-PC", null)
                .get("deviceToken").asText();
        String id = UUID.randomUUID().toString();

        JsonNode first = postJson("/api/agent/screenshots", deviceToken, Map.of(
                "clientEventId", id, "capturedAt", "2026-10-06T09:30:00Z",
                "contentType", "image/png", "imageBase64", PNG_BASE64), 200);
        JsonNode second = postJson("/api/agent/screenshots", deviceToken, Map.of(
                "clientEventId", id, "capturedAt", "2026-10-06T09:30:00Z",
                "contentType", "image/png", "imageBase64", PNG_BASE64), 200);

        assertThat(first.get("duplicate").asBoolean()).isFalse();
        assertThat(second.get("duplicate").asBoolean()).isTrue();
        assertThat(second.get("id").asLong()).isEqualTo(first.get("id").asLong());
        assertThat(getJson("/api/employees/" + employee + "/screenshots?date=2026-10-06&tz=UTC", admin)
                .get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void invalidContentTypeIsRejected() throws Exception {
        String admin = adminToken();
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "BADSHOT-PC", null)
                .get("deviceToken").asText();
        call(post("/api/agent/screenshots"), deviceToken, Map.of(
                "clientEventId", UUID.randomUUID().toString(), "capturedAt", "2026-10-06T09:30:00Z",
                "contentType", "image/gif", "imageBase64", PNG_BASE64)).andExpect(status().isBadRequest());
    }

    @Test
    void managerWithoutAssignmentCannotListOrView() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Private Shot");
        String deviceToken = registerDevice(createEnrollmentToken(admin, employee, 1), "PRIVSHOT-PC", null)
                .get("deviceToken").asText();
        long screenshotId = postJson("/api/agent/screenshots", deviceToken, Map.of(
                "clientEventId", UUID.randomUUID().toString(), "capturedAt", "2026-10-06T09:30:00Z",
                "contentType", "image/png", "imageBase64", PNG_BASE64), 200).get("id").asLong();

        UserSession manager = createUserAndLogin(admin, Set.of("MANAGER"), null);
        call(get("/api/employees/" + employee + "/screenshots?date=2026-10-06&tz=UTC"), manager.token(), null)
                .andExpect(status().isNotFound());
        call(get("/api/screenshots/" + screenshotId + "/content"), manager.token(), null)
                .andExpect(status().isNotFound());

        // Device token cannot read screenshots either.
        call(get("/api/screenshots/" + screenshotId + "/content"), deviceToken, null)
                .andExpect(status().isForbidden());
    }
}
