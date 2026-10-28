package com.employeemonitor.api.webaccess;

import com.employeemonitor.api.presence.PresenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes web-access realtime events onto the existing em:events channel, so the existing /ws/events fan-out and
 * per-employee filtering apply unchanged. Include payload.employeeId for scoped delivery (null = all-seeing admins).
 */
@Component
public class WebRealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(WebRealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public WebRealtimePublisher(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    public void publish(String type, Map<String, Object> payload) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", type);
            envelope.put("payload", payload);
            redis.convertAndSend(PresenceService.EVENTS_CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.warn("Failed to publish {} realtime event: {}", type, e.getMessage());
        }
    }
}
