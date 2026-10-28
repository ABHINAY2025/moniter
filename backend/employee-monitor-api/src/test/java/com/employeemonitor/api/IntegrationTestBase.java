package com.employeemonitor.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the full application against real PostgreSQL and Redis containers (shared across all test classes).
 * Requires a running Docker engine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("mocktest")
public abstract class IntegrationTestBase {

    protected static final String ADMIN_USERNAME = "admin";
    protected static final String ADMIN_PASSWORD = "Bootstrap-Admin-Pass-1";
    protected static final String DEFAULT_PASSWORD = "Correct-Horse-Battery-9";

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.security.jwt-secret", () -> "integration-test-secret-that-is-long-enough-0123456789");
        registry.add("app.security.login-rate-limit-per-minute", () -> 10_000);
        registry.add("app.security.register-rate-limit-per-minute", () -> 10_000);
        registry.add("app.bootstrap.admin-username", () -> ADMIN_USERNAME);
        registry.add("app.bootstrap.admin-email", () -> "admin@example.test");
        registry.add("app.bootstrap.admin-password", () -> ADMIN_PASSWORD);
        registry.add("app.screenshots.directory",
                () -> System.getProperty("java.io.tmpdir") + "/em-screenshots-test-" + UUID.randomUUID());
        registry.add("app.screenshots.encryption-key",
                () -> java.util.Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("app.updates.directory",
                () -> System.getProperty("java.io.tmpdir") + "/em-updates-test-" + UUID.randomUUID());
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    // ---------- HTTP helpers ----------

    protected ResultActions call(MockHttpServletRequestBuilder builder, String bearer, Object body) throws Exception {
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(builder);
    }

    protected JsonNode getJson(String url, String bearer) throws Exception {
        return read(call(get(url), bearer, null).andExpect(status().isOk()));
    }

    protected JsonNode postJson(String url, String bearer, Object body, int expectedStatus) throws Exception {
        return read(call(post(url), bearer, body).andExpect(status().is(expectedStatus)));
    }

    protected JsonNode putJson(String url, String bearer, Object body, int expectedStatus) throws Exception {
        return read(call(put(url), bearer, body).andExpect(status().is(expectedStatus)));
    }

    protected JsonNode patchJson(String url, String bearer, Object body, int expectedStatus) throws Exception {
        return read(call(patch(url), bearer, body).andExpect(status().is(expectedStatus)));
    }

    protected ResultActions deleteReq(String url, String bearer) throws Exception {
        return call(delete(url), bearer, null);
    }

    protected JsonNode read(ResultActions result) throws Exception {
        String content = result.andReturn().getResponse().getContentAsString();
        return content.isEmpty() ? json.nullNode() : json.readTree(content);
    }

    // ---------- Domain helpers ----------

    protected JsonNode login(String username, String password) throws Exception {
        return postJson("/api/auth/login", null, Map.of("username", username, "password", password), 200);
    }

    protected String adminToken() throws Exception {
        return login(ADMIN_USERNAME, ADMIN_PASSWORD).get("accessToken").asText();
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected long createEmployee(String adminToken, String fullName) throws Exception {
        String code = unique("E");
        return postJson("/api/employees", adminToken, Map.of(
                "employeeCode", code,
                "fullName", fullName,
                "email", code.toLowerCase() + "@example.test"), 201).get("id").asLong();
    }

    /** Creates a user with the given role(s) and returns an access token for them. */
    protected UserSession createUserAndLogin(String adminToken, Set<String> roles, Long employeeId) throws Exception {
        String username = unique("user").toLowerCase();
        var body = new java.util.HashMap<String, Object>();
        body.put("username", username);
        body.put("email", username + "@example.test");
        body.put("fullName", "Test " + username);
        body.put("password", DEFAULT_PASSWORD);
        body.put("roles", roles);
        if (employeeId != null) {
            body.put("employeeId", employeeId);
        }
        long id = postJson("/api/users", adminToken, body, 201).get("id").asLong();
        return new UserSession(id, username, login(username, DEFAULT_PASSWORD).get("accessToken").asText());
    }

    protected record UserSession(long id, String username, String token) {
    }

    protected String createEnrollmentToken(String adminToken, Long employeeId, int maxUses) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("description", "test");
        body.put("maxUses", maxUses);
        if (employeeId != null) {
            body.put("employeeId", employeeId);
        }
        return postJson("/api/enrollment-tokens", adminToken, body, 201).get("token").asText();
    }

    protected JsonNode registerDevice(String enrollmentToken, String hostname, String hardwareId) throws Exception {
        var body = new java.util.HashMap<String, Object>();
        body.put("enrollmentToken", enrollmentToken);
        body.put("hostname", hostname);
        body.put("osVersion", "Windows 11 Pro 23H2");
        body.put("agentVersion", "1.0.0");
        body.put("macAddress", "00-11-22-33-44-55");
        if (hardwareId != null) {
            body.put("hardwareId", hardwareId);
        }
        return postJson("/api/devices/register", null, body, 201);
    }
}
