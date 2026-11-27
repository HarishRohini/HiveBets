package dev.hivebets.realtime;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * WS /ws. Clients send {"op":"subscribe"|"unsubscribe","topics":[...]}. Topics: markets, market:{id},
 * leaderboard, news, user (= this caller's own user topic, known from the gateway's X-User-Id header).
 */
@Component
public class LiveSocket extends TextWebSocketHandler {
    private static final String USER = "userId";
    private final Fanout fanout;
    private final LiveState state;
    private final ObjectMapper mapper;
    private final java.util.Set<WebSocketSession> open = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public LiveSocket(Fanout fanout, LiveState state, ObjectMapper mapper) {
        this.fanout = fanout;
        this.state = state;
        this.mapper = mapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession s = new ConcurrentWebSocketSessionDecorator(session, 5_000, 512 * 1024);
        session.getAttributes().put("decorated", s);
        open.add(s);
        state.seen((String) session.getAttributes().get(USER));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        WebSocketSession s = (WebSocketSession) session.getAttributes().get("decorated");
        JsonNode msg;
        try {
            msg = mapper.readTree(message.getPayload());
        } catch (RuntimeException e) {
            return;
        }
        String op = msg.path("op").asString("");
        String user = (String) session.getAttributes().get(USER);
        state.seen(user);
        for (JsonNode t : msg.path("topics")) {
            String topic = t.asString("");
            if (topic.equals("user")) {
                if (user == null) continue;
                topic = "user:" + user;
            } else if (!(topic.equals("markets") || topic.equals("leaderboard") || topic.equals("news")
                    || (topic.startsWith("market:") && LiveState.isUuid(topic.substring(7))))) {
                continue;
            }
            if (op.equals("subscribe")) fanout.subscribe(topic, s);
            else if (op.equals("unsubscribe")) fanout.unsubscribe(topic, s);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        WebSocketSession s = (WebSocketSession) session.getAttributes().get("decorated");
        if (s != null) {
            fanout.drop(s);
            open.remove(s);
        }
    }

    public int connections() {
        return open.size();
    }

    /** Keeps connected signed-in users counted as online. */
    @Scheduled(fixedDelay = 20_000)
    public void heartbeat() {
        for (WebSocketSession s : open) state.seen((String) s.getAttributes().get(USER));
    }

    @Configuration
    @EnableWebSocket
    static class Config implements WebSocketConfigurer {
        private final LiveSocket socket;
        private final String webOrigin;

        Config(LiveSocket socket, @Value("${hivebets.web-origin}") String webOrigin) {
            this.socket = socket;
            this.webOrigin = webOrigin;
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(socket, "/ws")
                    .addInterceptors(new HandshakeInterceptor() {
                        @Override
                        public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h,
                                                       Map<String, Object> attrs) {
                            List<String> id = req.getHeaders().get("X-User-Id");
                            if (id != null && !id.isEmpty() && LiveState.isUuid(id.getFirst())) attrs.put(USER, id.getFirst());
                            return true;
                        }

                        @Override
                        public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception e) {}
                    })
                    .setAllowedOriginPatterns(webOrigin, "http://localhost:*", "http://127.0.0.1:*");
        }
    }
}
