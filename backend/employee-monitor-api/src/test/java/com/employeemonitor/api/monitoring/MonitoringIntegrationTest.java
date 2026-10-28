package com.employeemonitor.api.monitoring;

import com.employeemonitor.api.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MonitoringIntegrationTest extends IntegrationTestBase {

    private ObjectNode obj() {
        return json.createObjectNode();
    }

    private ObjectNode batch(ArrayNode events) {
        ObjectNode b = json.createObjectNode();
        b.set("events", events);
        return b;
    }

    private JsonNode ingest(String path, String deviceToken, ArrayNode events) throws Exception {
        return postJson(path, deviceToken, batch(events), 200);
    }

    @Test
    void applicationUsageIsAggregatedByApp() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "App User");
        String deviceToken = registerDevice(createEnrollmentToken(admin, employee, 1), "APP-PC", null)
                .get("deviceToken").asText();

        String day = "2026-10-06";
        ArrayNode events = json.createArrayNode();
        events.add(appEvent("Visual Studio Code", "Code.exe", day + "T09:00:00Z", day + "T09:30:00Z")); // 1800
        events.add(appEvent("Visual Studio Code", "Code.exe", day + "T10:00:00Z", day + "T10:30:00Z")); // 1800
        events.add(appEvent("Google Chrome", "chrome.exe", day + "T11:00:00Z", day + "T11:15:00Z"));     // 900

        JsonNode result = ingest("/api/agent/application-usage", deviceToken, events);
        assertThat(result.get("accepted").asInt()).isEqualTo(3);

        JsonNode report = getJson("/api/employees/" + employee + "/applications?date=" + day + "&tz=UTC", admin);
        assertThat(report.get("totalSeconds").asLong()).isEqualTo(4500);
        assertThat(report.at("/items/0/label").asText()).isEqualTo("Visual Studio Code");
        assertThat(report.at("/items/0/totalSeconds").asLong()).isEqualTo(3600);
        assertThat(report.at("/items/0/sessions").asLong()).isEqualTo(2);
        assertThat(report.at("/items/1/label").asText()).isEqualTo("Google Chrome");
    }

    @Test
    void ingestIsIdempotentAndRejectsReversedIntervals() throws Exception {
        String admin = adminToken();
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "APP2-PC", null)
                .get("deviceToken").asText();

        ObjectNode e = appEvent("Outlook", "outlook.exe", "2026-10-06T09:00:00Z", "2026-10-06T09:10:00Z");
        ArrayNode first = json.createArrayNode();
        first.add(e);
        assertThat(ingest("/api/agent/application-usage", deviceToken, first).get("accepted").asInt()).isEqualTo(1);

        ArrayNode retry = json.createArrayNode();
        retry.add(e.deepCopy());                                          // duplicate id
        retry.add(appEvent("Teams", "teams.exe", "2026-10-06T09:10:00Z", "2026-10-06T09:00:00Z")); // reversed -> rejected
        JsonNode r = ingest("/api/agent/application-usage", deviceToken, retry);
        assertThat(r.get("accepted").asInt()).isZero();
        assertThat(r.get("duplicates").asInt()).isEqualTo(1);
        assertThat(r.get("rejected").asInt()).isEqualTo(1);
    }

    @Test
    void usbNetworkAndZscalerEventsAreStoredAndQueryable() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Device User");
        String deviceToken = registerDevice(createEnrollmentToken(admin, employee, 1), "DEV-PC", null)
                .get("deviceToken").asText();
        String day = "2026-10-06";

        ArrayNode usb = json.createArrayNode();
        ObjectNode u = obj();
        u.put("clientEventId", UUID.randomUUID().toString());
        u.put("eventType", "CONNECTED");
        u.put("deviceClass", "USB Mass Storage");
        u.put("vendorId", "0951");
        u.put("productId", "1666");
        u.put("description", "Kingston DataTraveler");
        u.put("occurredAt", day + "T09:30:00Z");
        usb.add(u);
        assertThat(ingest("/api/agent/usb-events", deviceToken, usb).get("accepted").asInt()).isEqualTo(1);

        ArrayNode net = json.createArrayNode();
        ObjectNode n = obj();
        n.put("clientEventId", UUID.randomUUID().toString());
        n.put("eventType", "CONNECTED");
        n.put("connectionType", "ETHERNET");
        n.put("networkName", "FISEC-CORP");
        n.put("ipAddress", "10.0.4.17");
        n.put("occurredAt", day + "T09:00:00Z");
        net.add(n);
        assertThat(ingest("/api/agent/network-events", deviceToken, net).get("accepted").asInt()).isEqualTo(1);

        ArrayNode zs = json.createArrayNode();
        for (String s : new String[]{"CONNECTED", "DISCONNECTED", "CONNECTED"}) {
            ObjectNode z = obj();
            z.put("clientEventId", UUID.randomUUID().toString());
            z.put("status", s);
            z.put("occurredAt", day + "T0" + (zs.size() + 1) + ":00:00Z");
            zs.add(z);
        }
        assertThat(ingest("/api/agent/zscaler-events", deviceToken, zs).get("accepted").asInt()).isEqualTo(3);

        assertThat(getJson("/api/employees/" + employee + "/usb-events?date=" + day + "&tz=UTC", admin)
                .get("totalElements").asLong()).isEqualTo(1);
        JsonNode netResult = getJson("/api/employees/" + employee + "/network-events?date=" + day + "&tz=UTC", admin);
        assertThat(netResult.at("/content/0/networkName").asText()).isEqualTo("FISEC-CORP");

        JsonNode zstatus = getJson("/api/employees/" + employee + "/zscaler", admin);
        assertThat(zstatus.get("currentStatus").asText()).isEqualTo("CONNECTED");
        assertThat(zstatus.get("history").size()).isEqualTo(3);
    }

    @Test
    void heartbeatCarriesLiveApplicationAndZscalerToPresence() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Live User");
        JsonNode reg = registerDevice(createEnrollmentToken(admin, employee, 1), "LIVE-PC", null);
        String deviceToken = reg.get("deviceToken").asText();
        long deviceId = reg.get("deviceId").asLong();

        postJson("/api/agent/heartbeat", deviceToken, Map.of(
                "status", "ACTIVE", "currentApplication", "Visual Studio Code",
                "network", "Office LAN", "zscalerStatus", "CONNECTED"), 200);

        JsonNode device = getJson("/api/devices/" + deviceId, admin);
        assertThat(device.at("/presence/currentApplication").asText()).isEqualTo("Visual Studio Code");
        assertThat(device.at("/presence/network").asText()).isEqualTo("Office LAN");
        assertThat(device.at("/presence/zscalerStatus").asText()).isEqualTo("CONNECTED");
    }

    @Test
    void invalidUsbEventTypeIsRejectedWith400() throws Exception {
        String admin = adminToken();
        String deviceToken = registerDevice(createEnrollmentToken(admin, null, 1), "BADUSB-PC", null)
                .get("deviceToken").asText();
        ArrayNode usb = json.createArrayNode();
        ObjectNode u = obj();
        u.put("clientEventId", UUID.randomUUID().toString());
        u.put("eventType", "EXPLODED");
        u.put("occurredAt", "2026-10-06T09:30:00Z");
        usb.add(u);
        call(post("/api/agent/usb-events"), deviceToken, batch(usb)).andExpect(status().isBadRequest());
    }

    @Test
    void managerWithoutAssignmentCannotReadApplications() throws Exception {
        String admin = adminToken();
        long employee = createEmployee(admin, "Private User");
        UserSession manager = createUserAndLogin(admin, Set.of("MANAGER"), null);
        call(get("/api/employees/" + employee + "/applications?date=2026-10-06&tz=UTC"), manager.token(), null)
                .andExpect(status().isNotFound());
    }

    private ObjectNode appEvent(String app, String process, String start, String end) {
        ObjectNode e = obj();
        e.put("clientEventId", UUID.randomUUID().toString());
        e.put("applicationName", app);
        e.put("processName", process);
        e.put("startedAt", start);
        e.put("endedAt", end);
        return e;
    }
}
