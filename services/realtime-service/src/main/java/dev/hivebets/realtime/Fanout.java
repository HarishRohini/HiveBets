package dev.hivebets.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

/**
 * Pushes messages to browsers. Publishing goes through Redis pub/sub (channel ws:{topic}) so that
 * every realtime-service instance delivers to the WebSocket clients it holds.
 */
@Component
public class Fanout implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(Fanout.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Map<String, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();

    public Fanout(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void publish(String topic, String type, Object data) {
        String json = mapper.writeValueAsString(Map.of("topic", topic, "type", type, "data", data));
        redis.convertAndSend("ws:" + topic, json);
    }

    public void subscribe(String topic, WebSocketSession s) {
        subscribers.computeIfAbsent(topic, t -> ConcurrentHashMap.newKeySet()).add(s);
    }

    public void unsubscribe(String topic, WebSocketSession s) {
        Set<WebSocketSession> set = subscribers.get(topic);
        if (set != null) set.remove(s);
    }

    public void drop(WebSocketSession s) {
        subscribers.values().forEach(set -> set.remove(s));
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel());
        String topic = channel.substring(3);
        Set<WebSocketSession> set = subscribers.get(topic);
        if (set == null || set.isEmpty()) return;
        TextMessage text = new TextMessage(new String(message.getBody()));
        for (WebSocketSession s : set) {
            if (!s.isOpen()) {
                set.remove(s);
                continue;
            }
            try {
                s.sendMessage(text);
            } catch (IOException | IllegalStateException e) {
                log.debug("dropping websocket {}: {}", s.getId(), e.toString());
                set.remove(s);
            }
        }
    }

    @Configuration
    static class Wiring {
        @Bean
        RedisMessageListenerContainer redisListener(RedisConnectionFactory cf, Fanout fanout) {
            RedisMessageListenerContainer c = new RedisMessageListenerContainer();
            c.setConnectionFactory(cf);
            c.addMessageListener(fanout, new PatternTopic("ws:*"));
            return c;
        }
    }
}
