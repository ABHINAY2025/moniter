package com.employeemonitor.api.realtime;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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

/**
 * Boots a real HTTP/WebSocket server (reusing the shared Testcontainers) and verifies that a heartbeat's
 * presence transition is delivered to an authenticated admin WebSocket client, and that an unauthenticated
 * handshake is rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimeWebSocketIntegrationTest {

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
    }

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper json;

    private final RestTemplate rest = new RestTemplateBuilder().build();

    private String base() {
        return "http://localhost:" + port;
    }

    private JsonNode post(String path, String bearer, Map<String, Object> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        var response = rest.postForEntity(base() + path, new HttpEntity<>(json.writeValueAsString(body), headers),
                String.class);
        return json.readTree(response.getBody());
    }

    private String adminToken() throws Exception {
        return post("/api/auth/login", null, Map.of("username", "admin", "password", "Bootstrap-Admin-Pass-1"))
                .get("accessToken").asText();
    }

    @Test
    void heartbeatIsDeliveredToAuthenticatedAdminSocket() throws Exception {
        String admin = adminToken();

        // Create employee + enroll a device so the heartbeat carries an employeeId.
        long employeeId = post("/api/employees", admin,
                Map.of("employeeCode", "WS-" + UUID.randomUUID().toString().substring(0, 8),
                        "fullName", "WS Person", "email", UUID.randomUUID() + "@example.test")).get("id").asLong();
        String enrollment = post("/api/enrollment-tokens", admin,
                Map.of("employeeId", employeeId, "maxUses", 1)).get("token").asText();
        JsonNode reg = post("/api/devices/register", null, Map.of(
                "enrollmentToken", enrollment, "hostname", "WS-PC", "agentVersion", "1.0.0"));
        String deviceToken = reg.get("deviceToken").asText();

        BlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketSession session = connect(admin, received);
        try {
            // Drain the initial CONNECTED frame.
            String hello = received.poll(5, TimeUnit.SECONDS);
            assertThat(hello).contains("CONNECTED");

            // First heartbeat transitions OFFLINE -> ACTIVE and should publish DEVICE_ONLINE.
            post("/api/agent/heartbeat", deviceToken, Map.of("status", "ACTIVE"));

            String event = received.poll(5, TimeUnit.SECONDS);
            assertThat(event).isNotNull();
            JsonNode node = json.readTree(event);
            assertThat(node.get("type").asText()).isEqualTo("DEVICE_ONLINE");
            assertThat(node.at("/payload/employeeId").asLong()).isEqualTo(employeeId);
            assertThat(node.at("/payload/status").asText()).isEqualTo("ACTIVE");
        } finally {
            session.close();
        }
    }

    @Test
    void handshakeWithoutTokenIsRejected() {
        var client = new StandardWebSocketClient();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                client.execute(new TextWebSocketHandler() { }, new WebSocketHttpHeaders(),
                        URI.create("ws://localhost:" + port + "/ws/events")).get(5, TimeUnit.SECONDS))
                .isNotNull();
    }

    private WebSocketSession connect(String token, BlockingQueue<String> sink) throws Exception {
        var client = new StandardWebSocketClient();
        var headers = new WebSocketHttpHeaders();
        headers.setBearerAuth(token);
        return client.execute(new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession s, TextMessage message) {
                sink.add(message.getPayload());
            }

            @Override
            public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            }
        }, headers, URI.create("ws://localhost:" + port + "/ws/events")).get(5, TimeUnit.SECONDS);
    }
}
