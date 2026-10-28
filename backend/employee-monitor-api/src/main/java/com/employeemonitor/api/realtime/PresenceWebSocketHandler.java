package com.employeemonitor.api.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Fans out presence events to connected admin clients, filtered by each connection's visibility. Clients are
 * receive-only; inbound frames are ignored. Initial state comes from REST; this stream delivers deltas.
 */
@Component
public class PresenceWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(PresenceWebSocketHandler.class);

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final ObjectMapper objectMapper;

    public PresenceWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        sessions.add(session);
        WsPrincipal principal = principal(session);
        log.debug("WS connected: user={} seeAll={}", principal == null ? "?" : principal.username(),
                principal != null && principal.seeAll());
        session.sendMessage(new TextMessage("{\"type\":\"CONNECTED\"}"));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    /**
     * Broadcasts one event envelope (already JSON) to every session allowed to see {@code employeeId}.
     */
    public void broadcast(Long employeeId, String envelopeJson) {
        TextMessage message = new TextMessage(envelopeJson);
        for (WebSocketSession session : sessions) {
            WsPrincipal principal = principal(session);
            if (principal == null || !principal.canSee(employeeId) || !session.isOpen()) {
                continue;
            }
            try {
                session.sendMessage(message);
            } catch (IOException | IllegalStateException e) {
                log.debug("Dropping unwritable WS session {}: {}", session.getId(), e.getMessage());
                try {
                    session.close(CloseStatus.SERVER_ERROR);
                } catch (IOException ignored) {
                    // already gone
                }
                sessions.remove(session);
            }
        }
    }

    public int sessionCount() {
        return sessions.size();
    }

    private static WsPrincipal principal(WebSocketSession session) {
        return (WsPrincipal) session.getAttributes().get(AuthHandshakeInterceptor.PRINCIPAL_ATTR);
    }

    /** Extracts the employeeId (may be null) from an event envelope's payload for filtering. */
    public Long employeeIdOf(String envelopeJson) {
        try {
            JsonNode payload = objectMapper.readTree(envelopeJson).path("payload");
            JsonNode employee = payload.path("employeeId");
            return employee.isNumber() ? employee.asLong() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
