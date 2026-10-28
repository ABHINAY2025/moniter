package com.employeemonitor.api.liveview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Relays live-view signaling between the authorized admin and the target device for a session. It carries the
 * WebRTC handshake (OFFER/ANSWER/ICE) and, for the frame-relay transport, FRAME messages - it never interprets
 * media. The admin and agent only ever reach each other through a session the backend authorized. Connection
 * registry is in-memory (single instance); multi-instance deployments need sticky routing or a Redis relay.
 */
@Component
public class LiveViewSignalingHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveViewSignalingHandler.class);

    private final LiveViewService liveView;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, WebSocketSession> agents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, WebSocketSession> admins = new ConcurrentHashMap<>();

    public LiveViewSignalingHandler(LiveViewService liveView, ObjectMapper objectMapper) {
        this.liveView = liveView;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (isAgent(session)) {
            agents.put(deviceId(session), session);
            log.debug("Live-view agent connected for device {}", deviceId(session));
            return;
        }
        // Admin: wire it to the device's agent and ask the agent to start.
        UUID sessionId = sessionId(session);
        admins.put(sessionId, session);
        WebSocketSession agent = agents.get(deviceId(session));
        if (agent == null || !agent.isOpen()) {
            send(session, "{\"type\":\"ERROR\",\"reason\":\"DEVICE_NOT_CONNECTED\"}");
            liveView.endBySystem(sessionId, "DEVICE_NOT_CONNECTED");
            session.close(CloseStatus.GOING_AWAY);
            admins.remove(sessionId);
            return;
        }
        send(agent, control("START", sessionId));
        send(session, control("WAITING", sessionId));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message.getPayload());
        } catch (IOException e) {
            return;
        }
        String type = envelope.path("type").asText("");
        if (isAgent(session)) {
            relayFromAgent(session, type, message);
        } else {
            relayFromAdmin(session, type, message);
        }
    }

    private void relayFromAdmin(WebSocketSession admin, String type, TextMessage message) {
        UUID sessionId = sessionId(admin);
        if ("STOP".equals(type)) {
            liveView.endBySystem(sessionId, "STOPPED_BY_ADMIN");
            closeSession(sessionId, "STOPPED_BY_ADMIN");
            return;
        }
        // OFFER / ICE / control destined for the agent.
        WebSocketSession agent = agents.get(deviceId(admin));
        if (agent != null) {
            send(agent, message.getPayload());
        }
    }

    private void relayFromAgent(WebSocketSession agent, String type, TextMessage message) {
        UUID sessionId = sessionId(message);
        if (sessionId == null) {
            return;
        }
        if ("READY".equals(type) || "FRAME".equals(type) || "ANSWER".equals(type)) {
            liveView.markActive(sessionId);
        }
        WebSocketSession admin = admins.get(sessionId);
        if (admin != null && admin.isOpen()) {
            send(admin, message.getPayload());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (isAgent(session)) {
            Long deviceId = deviceId(session);
            agents.remove(deviceId, session);
            liveView.activeForDevice(deviceId).ifPresent(s -> {
                liveView.endBySystem(s.getSessionId(), "DEVICE_DISCONNECTED");
                closeSession(s.getSessionId(), "DEVICE_DISCONNECTED");
            });
        } else {
            UUID sessionId = sessionId(session);
            admins.remove(sessionId, session);
            liveView.endBySystem(sessionId, "ADMIN_DISCONNECTED");
            // Tell the agent to stop capturing.
            WebSocketSession agent = agents.get(deviceId(session));
            if (agent != null) {
                send(agent, control("STOP", sessionId));
            }
        }
    }

    /** Called by the REST stop endpoint and the timeout sweep to tear down both ends. */
    public void closeSession(UUID sessionId, String reason) {
        WebSocketSession admin = admins.remove(sessionId);
        if (admin != null && admin.isOpen()) {
            send(admin, "{\"type\":\"STOP\",\"reason\":\"" + reason + "\"}");
            try {
                admin.close(CloseStatus.NORMAL);
            } catch (IOException ignored) {
            }
        }
        if (admin != null) {
            WebSocketSession agent = agents.get(deviceId(admin));
            if (agent != null) {
                send(agent, control("STOP", sessionId));
            }
        }
    }

    @Scheduled(fixedDelayString = "${app.live-view.sweep-interval-ms:15000}")
    public void sweepTimedOut() {
        try {
            for (UUID sessionId : liveView.findTimedOut()) {
                liveView.endBySystem(sessionId, "TIMEOUT");
                closeSession(sessionId, "TIMEOUT");
            }
        } catch (RuntimeException e) {
            log.warn("Live-view timeout sweep failed: {}", e.getMessage());
        }
    }

    // ---------- helpers ----------

    private static boolean isAgent(WebSocketSession s) {
        return LiveViewHandshakeInterceptor.ROLE_AGENT.equals(s.getAttributes().get(LiveViewHandshakeInterceptor.ROLE));
    }

    private static Long deviceId(WebSocketSession s) {
        return (Long) s.getAttributes().get(LiveViewHandshakeInterceptor.DEVICE_ID);
    }

    private static UUID sessionId(WebSocketSession s) {
        return (UUID) s.getAttributes().get(LiveViewHandshakeInterceptor.SESSION_ID);
    }

    private UUID sessionId(TextMessage message) {
        try {
            String raw = objectMapper.readTree(message.getPayload()).path("sessionId").asText(null);
            return raw == null ? null : UUID.fromString(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private String control(String type, UUID sessionId) {
        return "{\"type\":\"" + type + "\",\"sessionId\":\"" + sessionId + "\"}";
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Live-view send failed: {}", e.getMessage());
        }
    }
}
