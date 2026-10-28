package com.employeemonitor.api.presence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.employeemonitor.api.alerts.AlertService;
import com.employeemonitor.api.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Realtime device presence in Redis.
 * <ul>
 *   <li>{@code em:presence:device:{id}} - JSON {@link Presence}, TTL = offline timeout, refreshed by each heartbeat.</li>
 *   <li>{@code em:presence:online} - set of device ids currently considered online; used by the sweeper to detect
 *       devices whose key has expired so an OFFLINE event can be emitted.</li>
 *   <li>{@code em:events} - pub/sub channel with presence transitions (consumed by the WebSocket gateway).</li>
 * </ul>
 */
@Service
public class PresenceService {

    public static final String EVENTS_CHANNEL = "em:events";
    private static final String KEY_PREFIX = "em:presence:device:";
    private static final String ONLINE_SET = "em:presence:online";
    // deviceId -> employeeId, so expiry-driven OFFLINE events (which have no live presence row) still carry the
    // employee for per-user filtering in the realtime WebSocket.
    private static final String DEVICE_EMPLOYEE = "em:device-employee";

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AppProperties.Presence props;
    private final AlertService alerts;
    private final Clock clock;

    public PresenceService(StringRedisTemplate redis, ObjectMapper objectMapper, AppProperties.Presence props,
                           AlertService alerts, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
        this.alerts = alerts;
        this.clock = clock;
    }

    public record PresenceEvent(String type, Long deviceId, Long employeeId, ActivityStatus status,
                                ActivityStatus previousStatus, Instant at) {
    }

    /** Records a heartbeat and publishes an event when the device's state changed. */
    public Presence recordHeartbeat(Long deviceId, Long employeeId, ActivityStatus status, String ipAddress,
                                    String currentApplication, String network, String zscalerStatus) {
        if (status == ActivityStatus.OFFLINE) {
            throw new IllegalArgumentException("Agents cannot report OFFLINE");
        }
        Instant now = clock.instant();
        Presence presence = new Presence(deviceId, employeeId, status, now, ipAddress,
                currentApplication, network, zscalerStatus);
        String previousJson = redis.opsForValue().getAndSet(key(deviceId), write(presence));
        redis.expire(key(deviceId), props.offlineTimeout());
        redis.opsForSet().add(ONLINE_SET, deviceId.toString());
        if (employeeId != null) {
            redis.opsForHash().put(DEVICE_EMPLOYEE, deviceId.toString(), employeeId.toString());
        }

        ActivityStatus previous = read(previousJson).map(Presence::status).orElse(ActivityStatus.OFFLINE);
        if (previous != status) {
            publish(new PresenceEvent(previous == ActivityStatus.OFFLINE ? "DEVICE_ONLINE" : "DEVICE_STATUS_CHANGED",
                    deviceId, employeeId, status, previous, now));
        }
        return presence;
    }

    public Optional<Presence> get(Long deviceId) {
        return read(redis.opsForValue().get(key(deviceId)));
    }

    /** Batch lookup; devices without a live presence entry are absent from the result. */
    public Map<Long, Presence> getAll(Collection<Long> deviceIds) {
        if (deviceIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = new ArrayList<>(deviceIds);
        List<String> values = redis.opsForValue().multiGet(ids.stream().map(PresenceService::key).toList());
        Map<Long, Presence> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String value = values == null ? null : values.get(i);
            int idx = i;
            read(value).ifPresent(p -> result.put(ids.get(idx), p));
        }
        return result;
    }

    /** Marks a device offline immediately (e.g. it was disabled or removed). */
    public void forceOffline(Long deviceId, Long employeeId) {
        Boolean existed = redis.delete(key(deviceId));
        redis.opsForSet().remove(ONLINE_SET, deviceId.toString());
        if (Boolean.TRUE.equals(existed)) {
            publish(new PresenceEvent("DEVICE_OFFLINE", deviceId, employeeId, ActivityStatus.OFFLINE, null,
                    clock.instant()));
        }
    }

    /** Emits DEVICE_OFFLINE for devices whose presence key has expired. */
    @Scheduled(fixedDelayString = "${app.presence.sweep-interval-ms:15000}")
    public void sweepExpired() {
        try {
            Set<String> members = redis.opsForSet().members(ONLINE_SET);
            if (members == null) {
                return;
            }
            for (String member : members) {
                Long deviceId = Long.valueOf(member);
                if (!Boolean.TRUE.equals(redis.hasKey(key(deviceId)))) {
                    Long removed = redis.opsForSet().remove(ONLINE_SET, member);
                    // Only the instance that actually removed the member publishes, so events are not duplicated.
                    if (removed != null && removed > 0) {
                        Object employee = redis.opsForHash().get(DEVICE_EMPLOYEE, member);
                        Long employeeId = employee == null ? null : Long.valueOf(employee.toString());
                        publish(new PresenceEvent("DEVICE_OFFLINE", deviceId, employeeId, ActivityStatus.OFFLINE, null,
                                clock.instant()));
                        alerts.raiseOfflineDuringWork(deviceId, employeeId);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Presence sweep failed: {}", e.getMessage());
        }
    }

    private void publish(PresenceEvent event) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", event.type());
            envelope.put("payload", event);
            redis.convertAndSend(EVENTS_CHANNEL, objectMapper.writeValueAsString(envelope));
        } catch (JsonProcessingException | RuntimeException e) {
            log.warn("Failed to publish presence event {}: {}", event.type(), e.getMessage());
        }
    }

    private static String key(Long deviceId) {
        return KEY_PREFIX + deviceId;
    }

    private String write(Presence presence) {
        try {
            return objectMapper.writeValueAsString(presence);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Optional<Presence> read(String json) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, Presence.class));
        } catch (JsonProcessingException e) {
            log.warn("Discarding unreadable presence entry: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
