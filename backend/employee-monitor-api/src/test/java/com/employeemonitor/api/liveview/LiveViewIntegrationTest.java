package com.employeemonitor.api.liveview;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveViewIntegrationTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTestBase.POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", IntegrationTestBase.POSTGRES::getUsername);
        registry.add("spring.datasource.password", IntegrationTestBase.POSTGRES::getPassword);
        registry.add("spring.data.redis.host", IntegrationTestBase.REDIS::getHost);
        registry.add("spring.data.redis.port", () -> IntegrationTestBase.REDIS.getMappedPort(6379));
        registry.add("app.security.jwt-secret", () -> "integration-test-secret-that-is-long-enough-0123456789");
        registry.add("app.security.login-rate-limit-per-minute", () -> 10_000);
        registry.add("app.security.register-rate-limit-per-minute", () -> 10_000);
        registry.add("app.bootstrap.admin-username", () -> "admin");
        registry.add("app.bootstrap.admin-email", () -> "admin@example.test");
        registry.add("app.bootstrap.admin-password", () -> "Bootstrap-Admin-Pass-1");
        registry.add("app.screenshots.directory",
                () -> System.getProperty("java.io.tmpdir") + "/em-lv-test-" + UUID.randomUUID());
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final RestTemplate rest = new RestTemplateBuilder().build();

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode api(HttpMethod method, String path, String bearer, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        var entity = new HttpEntity<>(body == null ? null : json.writeValueAsString(body), headers);
        ResponseEntity<String> resp = rest.exchange(base() + path, method, entity, String.class);
        return resp.getBody() == null || resp.getBody().isEmpty() ? json.nullNode() : json.readTree(resp.getBody());
    }

    private HttpStatusCode status(HttpMethod method, String path, String bearer, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (bearer != null) {
                headers.setBearerAuth(bearer);
            }
            var entity = new HttpEntity<>(body == null ? null : json.writeValueAsString(body), headers);
            return rest.exchange(base() + path, method, entity, String.class).getStatusCode();
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            return e.getStatusCode();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String adminToken() throws Exception {
        return api(HttpMethod.POST, "/api/auth/login", null,
                Map.of("username", "admin", "password", "Bootstrap-Admin-Pass-1")).get("accessToken").asText();
    }

    private record Enrolled(long employeeId, long deviceId, String deviceToken) {
    }

    private Enrolled enrollOnlineDeviceWithLiveView(String admin) throws Exception {
        String code = "LV" + UUID.randomUUID().toString().substring(0, 8);
        long employeeId = api(HttpMethod.POST, "/api/employees", admin, Map.of(
                "employeeCode", code, "fullName", "Live Person", "email", code + "@example.test")).get("id").asLong();
        String enrollment = api(HttpMethod.POST, "/api/enrollment-tokens", admin,
                Map.of("employeeId", employeeId, "maxUses", 1)).get("token").asText();
        JsonNode reg = api(HttpMethod.POST, "/api/devices/register", null, Map.of(
                "enrollmentToken", enrollment, "hostname", "LIVE-PC", "agentVersion", "1.0.0"));
        long deviceId = reg.get("deviceId").asLong();
        String deviceToken = reg.get("deviceToken").asText();

        api(HttpMethod.POST, "/api/agent/heartbeat", deviceToken, Map.of("status", "ACTIVE")); // online

        // Enable live view on this device's policy.
        ObjectNode settings = (ObjectNode) api(HttpMethod.GET, "/api/policies/" + deviceId, admin, null).get("settings");
        settings.put("liveView", true);
        api(HttpMethod.PUT, "/api/policies/" + deviceId, admin, settings);
        return new Enrolled(employeeId, deviceId, deviceToken);
    }

    @Test
    void authorizedSessionRelaysSignalingBothWaysAndIsAudited() throws Exception {
        String admin = adminToken();
        Enrolled d = enrollOnlineDeviceWithLiveView(admin);

        // Agent connects its signaling endpoint first.
        BlockingQueue<String> agentInbox = new LinkedBlockingQueue<>();
        WebSocketSession agent = connect(d.deviceToken(), null, agentInbox);

        // Admin starts a session, then connects the admin signaling endpoint.
        JsonNode session = api(HttpMethod.POST, "/api/live-view/sessions", admin, Map.of("deviceId", d.deviceId()));
        String sessionId = session.get("sessionId").asText();
        assertThat(session.get("maxDurationSeconds").asInt()).isGreaterThan(0);

        BlockingQueue<String> adminInbox = new LinkedBlockingQueue<>();
        WebSocketSession adminWs = connect(admin, sessionId, adminInbox);

        // Agent is told to START.
        String start = poll(agentInbox);
        assertThat(json.readTree(start).get("type").asText()).isEqualTo("START");
        assertThat(json.readTree(start).get("sessionId").asText()).isEqualTo(sessionId);

        // Agent sends a frame; the admin receives it and the session becomes ACTIVE.
        agent.sendMessage(new TextMessage("{\"type\":\"FRAME\",\"sessionId\":\"" + sessionId + "\",\"data\":\"AAAA\"}"));
        String relayed = pollUntil(adminInbox, "FRAME");
        assertThat(json.readTree(relayed).get("data").asText()).isEqualTo("AAAA");

        // The on-device indicator reports active.
        JsonNode agentStatus = api(HttpMethod.GET, "/api/agent/live-view/status", d.deviceToken(), null);
        assertThat(agentStatus.get("active").asBoolean()).isTrue();

        // Admin stops the session; the agent is told to STOP.
        assertThat(status(HttpMethod.DELETE, "/api/live-view/sessions/" + sessionId, admin, null).value())
                .isEqualTo(204);
        String stop = pollUntil(agentInbox, "STOP");
        assertThat(json.readTree(stop).get("type").asText()).isEqualTo("STOP");

        // Both start and stop are audited.
        JsonNode audit = api(HttpMethod.GET,
                "/api/audit-logs?targetType=LIVE_VIEW&targetId=" + sessionId, admin, null);
        var actions = audit.get("content");
        assertThat(actions.toString()).contains("LIVE_VIEW_STARTED").contains("LIVE_VIEW_STOPPED");

        safeClose(agent);
        safeClose(adminWs);
    }

    @Test
    void liveViewDisabledByPolicyIsForbidden() throws Exception {
        String admin = adminToken();
        String code = "LVX" + UUID.randomUUID().toString().substring(0, 8);
        long employeeId = api(HttpMethod.POST, "/api/employees", admin, Map.of(
                "employeeCode", code, "fullName", "No Live", "email", code + "@example.test")).get("id").asLong();
        String enrollment = api(HttpMethod.POST, "/api/enrollment-tokens", admin,
                Map.of("employeeId", employeeId, "maxUses", 1)).get("token").asText();
        JsonNode reg = api(HttpMethod.POST, "/api/devices/register", null, Map.of(
                "enrollmentToken", enrollment, "hostname", "NOLIVE-PC", "agentVersion", "1.0.0"));
        api(HttpMethod.POST, "/api/agent/heartbeat", reg.get("deviceToken").asText(), Map.of("status", "ACTIVE"));

        // Default policy has liveView=false.
        assertThat(status(HttpMethod.POST, "/api/live-view/sessions", admin,
                Map.of("deviceId", reg.get("deviceId").asLong())).value()).isEqualTo(403);
    }

    @Test
    void offlineDeviceCannotBeViewed() throws Exception {
        String admin = adminToken();
        String code = "LVO" + UUID.randomUUID().toString().substring(0, 8);
        long employeeId = api(HttpMethod.POST, "/api/employees", admin, Map.of(
                "employeeCode", code, "fullName", "Offline", "email", code + "@example.test")).get("id").asLong();
        String enrollment = api(HttpMethod.POST, "/api/enrollment-tokens", admin,
                Map.of("employeeId", employeeId, "maxUses", 1)).get("token").asText();
        JsonNode reg = api(HttpMethod.POST, "/api/devices/register", null, Map.of(
                "enrollmentToken", enrollment, "hostname", "OFF-PC", "agentVersion", "1.0.0"));
        long deviceId = reg.get("deviceId").asLong();
        ObjectNode settings = (ObjectNode) api(HttpMethod.GET, "/api/policies/" + deviceId, admin, null).get("settings");
        settings.put("liveView", true);
        api(HttpMethod.PUT, "/api/policies/" + deviceId, admin, settings);

        // Never sent a heartbeat -> offline.
        assertThat(status(HttpMethod.POST, "/api/live-view/sessions", admin, Map.of("deviceId", deviceId)).value())
                .isEqualTo(409);
    }

    @Test
    void handshakeWithoutCredentialsIsRejected() {
        var client = new StandardWebSocketClient();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.execute(new TextWebSocketHandler() { }, new WebSocketHttpHeaders(),
                        URI.create("ws://localhost:" + port + "/ws/live-view")).get(5, TimeUnit.SECONDS))
                .isNotNull();
    }

    // ---------- WS helpers ----------

    private WebSocketSession connect(String token, String sessionId, BlockingQueue<String> sink) throws Exception {
        var client = new StandardWebSocketClient();
        var headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(token);
        String url = "ws://localhost:" + port + "/ws/live-view" + (sessionId == null ? "" : "?sessionId=" + sessionId);
        return client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                sink.add(message.getPayload());
            }
        }, headers, URI.create(url)).get(5, TimeUnit.SECONDS);
    }

    private String poll(BlockingQueue<String> q) throws InterruptedException {
        String msg = q.poll(5, TimeUnit.SECONDS);
        assertThat(msg).as("expected a signaling message").isNotNull();
        return msg;
    }

    private String pollUntil(BlockingQueue<String> q, String type) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            String msg = q.poll(5, TimeUnit.SECONDS);
            if (msg != null && type.equals(json.readTree(msg).path("type").asText())) {
                return msg;
            }
        }
        throw new AssertionError("Did not receive a " + type + " message");
    }

    private void safeClose(WebSocketSession s) {
        try {
            s.close(CloseStatus.NORMAL);
        } catch (Exception ignored) {
        }
    }
}
