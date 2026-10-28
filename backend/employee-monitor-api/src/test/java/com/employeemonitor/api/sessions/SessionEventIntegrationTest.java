package com.employeemonitor.api.sessions;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SessionEventIntegrationTest extends IntegrationTestBase {

    private ObjectNode event(String type, String occurredAt) {
        ObjectNode n = json.createObjectNode();
        n.put("clientEventId", UUID.randomUUID().toString());
        n.put("type", type);
        n.put("occurredAt", occurredAt);
        return n;
    }

    private JsonNode ingest(String deviceToken, ArrayNode events) throws Exception {
        ObjectNode body = json.createObjectNode();
        body.set("events", events);
        return postJson("/api/agent/session-events", deviceToken, body, 200);
    }

    @Test
    void agentPostsEventsAndTimelineAndSummaryReflectThem() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Timeline Person");
        JsonNode reg = registerDevice(createEnrollmentToken(admin, employee, 1), "TIMELINE-PC", null);
        String deviceToken = reg.get("deviceToken").asText();

        String day = "2026-10-06";
        ArrayNode events = json.createArrayNode();
        events.add(event("AGENT_START", day + "T08:59:00Z"));
        events.add(event("LOGIN", day + "T09:00:00Z"));
        events.add(event("IDLE", day + "T10:00:00Z"));
        events.add(event("ACTIVE", day + "T10:30:00Z"));
        events.add(event("LOCK", day + "T12:00:00Z"));
        events.add(event("UNLOCK", day + "T12:30:00Z"));
        events.add(event("LOGOUT", day + "T13:00:00Z"));

        JsonNode result = ingest(deviceToken, events);
        assertThat(result.get("accepted").asInt()).isEqualTo(7);
        assertThat(result.get("duplicates").asInt()).isZero();

        JsonNode timeline = getJson("/api/employees/" + employee + "/timeline?date=" + day + "&tz=UTC", admin);
        assertThat(timeline.get("entries")).hasSize(7);
        assertThat(timeline.at("/entries/1/type").asText()).isEqualTo("LOGIN");

        JsonNode summary = getJson("/api/employees/" + employee + "/session-summary?date=" + day + "&tz=UTC", admin);
        assertThat(summary.get("activeSeconds").asLong()).isEqualTo(10800);
        assertThat(summary.get("idleSeconds").asLong()).isEqualTo(1800);
        assertThat(summary.get("lockedSeconds").asLong()).isEqualTo(1800);
        assertThat(summary.get("currentlyOnline").asBoolean()).isFalse();
        assertThat(summary.get("sessionSeconds").asLong()).isEqualTo(4 * 3600);
    }

    @Test
    void duplicateEventsAreIgnored() throws Exception {
        String admin = adminToken();
        JsonNode reg = registerDevice(createEnrollmentToken(admin, null, 1), "DUP-PC", null);
        String deviceToken = reg.get("deviceToken").asText();

        ObjectNode e = event("LOGIN", "2026-10-06T09:00:00Z");
        ArrayNode first = json.createArrayNode();
        first.add(e);
        assertThat(ingest(deviceToken, first).get("accepted").asInt()).isEqualTo(1);

        // Same clientEventId re-sent (simulating a retried batch).
        ArrayNode retry = json.createArrayNode();
        retry.add(e.deepCopy());
        JsonNode second = ingest(deviceToken, retry);
        assertThat(second.get("accepted").asInt()).isZero();
        assertThat(second.get("duplicates").asInt()).isEqualTo(1);
    }

    @Test
    void clockSkewEventsAreRejected() throws Exception {
        String admin = adminToken();
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "SKEW-PC", null)
                .get("deviceToken").asText();
        ArrayNode events = json.createArrayNode();
        events.add(event("LOGIN", Instant.now().plusSeconds(3600).toString())); // far future
        JsonNode result = ingest(deviceToken, events);
        assertThat(result.get("rejected").asInt()).isEqualTo(1);
        assertThat(result.get("accepted").asInt()).isZero();
    }

    @Test
    void unknownEventTypeIsRejectedWith400() throws Exception {
        String admin = adminToken();
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "BADTYPE-PC", null)
                .get("deviceToken").asText();
        ArrayNode events = json.createArrayNode();
        events.add(event("DANCING", "2026-10-06T09:00:00Z"));
        ObjectNode body = json.createObjectNode();
        body.set("events", events);
        call(post("/api/agent/session-events"), deviceToken, body).andExpect(status().isBadRequest());
    }

    @Test
    void userTokenCannotIngestEvents() throws Exception {
        String admin = adminToken();
        ArrayNode events = json.createArrayNode();
        events.add(event("LOGIN", "2026-10-06T09:00:00Z"));
        ObjectNode body = json.createObjectNode();
        body.set("events", events);
        call(post("/api/agent/session-events"), admin, body).andExpect(status().isForbidden());
    }
}
