package com.employeemonitor.api.realtime;

import com.employeemonitor.api.liveview.LiveViewHandshakeInterceptor;
import com.employeemonitor.api.liveview.LiveViewSignalingHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final PresenceWebSocketHandler handler;
    private final AuthHandshakeInterceptor authInterceptor;
    private final LiveViewSignalingHandler liveViewHandler;
    private final LiveViewHandshakeInterceptor liveViewInterceptor;

    public WebSocketConfig(PresenceWebSocketHandler handler, AuthHandshakeInterceptor authInterceptor,
                           LiveViewSignalingHandler liveViewHandler,
                           LiveViewHandshakeInterceptor liveViewInterceptor) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.liveViewHandler = liveViewHandler;
        this.liveViewInterceptor = liveViewInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/events")
                .addInterceptors(authInterceptor)
                // Native app / no browser origin; the JWT handshake is the real gate, so origins are not restricted.
                .setAllowedOriginPatterns("*");
        registry.addHandler(liveViewHandler, "/ws/live-view")
                .addInterceptors(liveViewInterceptor)
                .setAllowedOriginPatterns("*");
    }

    /**
     * Live-view frames (base64 JPEG) exceed the 8 KB default text-message buffer, so raise the limits. Requires a
     * real JSR-356 server container, so it is excluded from MockMvc tests (the "mocktest" profile).
     */
    @Bean
    @Profile("!mocktest")
    ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(4 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(4 * 1024 * 1024);
        container.setMaxSessionIdleTimeout(0L);
        return container;
    }

    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
