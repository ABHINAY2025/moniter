package com.employeemonitor.api.updates;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentUpdateIntegrationTest extends IntegrationTestBase {

    @Autowired
    AgentVersionRepository versions;

    @BeforeEach
    void clearVersions() {
        // The "latest approved version" is global, so isolate each test from others' published versions.
        versions.deleteAll();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static String sign(PrivateKey key, byte[] data) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update(data);
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    private static boolean verify(PublicKey key, byte[] data, String signatureB64) throws Exception {
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initVerify(key);
        sig.update(data);
        return sig.verify(Base64.getDecoder().decode(signatureB64));
    }

    @Test
    void publishApproveCheckAndDownloadWithSignatureVerification() throws Exception {
        String admin = adminToken();
        KeyPair keys = rsa();
        byte[] pkg = "fake-agent-installer-bytes-v1.1.0".getBytes();
        String signature = sign(keys.getPrivate(), pkg);

        JsonNode published = postJson("/api/agent-versions", admin, Map.of(
                "version", "1.1.0", "notes", "Bug fixes", "mandatory", false,
                "signature", signature, "packageBase64", Base64.getEncoder().encodeToString(pkg)), 201);
        long id = published.get("id").asLong();
        assertThat(published.get("approved").asBoolean()).isFalse();

        // Enrol a device so we can call the agent update endpoints.
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "UPD-PC", null)
                .get("deviceToken").asText();

        // Not approved yet -> no update offered.
        call(get("/api/agent/update?current=1.0.0"), deviceToken, null).andExpect(status().isNoContent());

        // Approve it.
        putJson("/api/agent-versions/" + id + "/approval", admin, Map.of("approved", true), 200);

        JsonNode update = getJson("/api/agent/update?current=1.0.0", deviceToken);
        assertThat(update.get("version").asText()).isEqualTo("1.1.0");
        assertThat(update.get("downloadPath").asText()).isEqualTo("/api/agent/update/1.1.0/package");

        byte[] downloaded = call(get("/api/agent/update/1.1.0/package"), deviceToken, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(pkg);

        // The SHA-256 matches and the signature the agent will verify is valid for these bytes.
        assertThat(update.get("sha256").asText()).isEqualTo(sha256Hex(downloaded));
        assertThat(verify(keys.getPublic(), downloaded, update.get("signature").asText())).isTrue();

        // A tampered package fails signature verification (what the agent would reject).
        byte[] tampered = "tampered".getBytes();
        assertThat(verify(keys.getPublic(), tampered, update.get("signature").asText())).isFalse();
    }

    @Test
    void agentIsNotOfferedOlderOrEqualVersions() throws Exception {
        String admin = adminToken();
        byte[] pkg = "pkg".getBytes();
        long id = postJson("/api/agent-versions", admin, Map.of(
                "version", "2.0.0", "mandatory", false, "signature", "c2ln",
                "packageBase64", Base64.getEncoder().encodeToString(pkg)), 201).get("id").asLong();
        putJson("/api/agent-versions/" + id + "/approval", admin, Map.of("approved", true), 200);
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "UPD2-PC", null)
                .get("deviceToken").asText();

        call(get("/api/agent/update?current=2.0.0"), deviceToken, null).andExpect(status().isNoContent());
        call(get("/api/agent/update?current=2.1.0"), deviceToken, null).andExpect(status().isNoContent());
        assertThat(getJson("/api/agent/update?current=1.9.9", deviceToken).get("version").asText()).isEqualTo("2.0.0");
    }

    @Test
    void onlyAgentManagersCanPublish() throws Exception {
        String admin = adminToken();
        UserSession hr = createUserAndLogin(admin, Set.of("HR_ADMIN"), null);
        call(post("/api/agent-versions"), hr.token(), Map.of(
                "version", "9.9.9", "mandatory", false, "signature", "x",
                "packageBase64", Base64.getEncoder().encodeToString("p".getBytes()))).andExpect(status().isForbidden());

        UserSession it = createUserAndLogin(admin, Set.of("IT_ADMIN"), null);
        call(post("/api/agent-versions"), it.token(), Map.of(
                "version", "9.9.9", "mandatory", false, "signature", "x",
                "packageBase64", Base64.getEncoder().encodeToString("p".getBytes()))).andExpect(status().isCreated());
    }

    @Test
    void duplicateVersionIsRejected() throws Exception {
        String admin = adminToken();
        Map<String, Object> body = Map.of("version", "3.0.0", "mandatory", false, "signature", "x",
                "packageBase64", Base64.getEncoder().encodeToString("p".getBytes()));
        postJson("/api/agent-versions", admin, body, 201);
        call(post("/api/agent-versions"), admin, body).andExpect(status().isConflict());
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(bytes));
    }
}
