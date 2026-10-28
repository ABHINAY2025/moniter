package com.employeemonitor.api.realtime;

import com.employeemonitor.api.presence.PresenceService;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Subscribes to the Redis {@code em:events} channel and forwards each presence event to the WebSocket handler,
 * which applies per-connection filtering. Using Redis pub/sub means events published by any API instance reach
 * the clients connected to every instance.
 */
@Component
public class RedisEventBridge implements MessageListener {

    private final PresenceWebSocketHandler handler;

    public RedisEventBridge(RedisMessageListenerContainer container, PresenceWebSocketHandler handler) {
        this.handler = handler;
        container.addMessageListener(this, new ChannelTopic(PresenceService.EVENTS_CHANNEL));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String json = new String(message.getBody(), StandardCharsets.UTF_8);
        handler.broadcast(handler.employeeIdOf(json), json);
    }
}
