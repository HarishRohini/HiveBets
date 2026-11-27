package dev.hivebets.realtime;

import dev.hivebets.common.events.EventEnvelope;
import dev.hivebets.common.events.EventFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Turns bus events into Redis state and WebSocket pushes. */
@Component
public class EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private final EventFactory events;
    private final LiveState state;
    private final Fanout fanout;

    public EventConsumer(EventFactory events, LiveState state, Fanout fanout) {
        this.events = events;
        this.state = state;
        this.fanout = fanout;
    }

    @RabbitListener(queues = RealtimeApplication.QUEUE, concurrency = "1")
    public void onEvent(Message message) {
        EventEnvelope e = events.fromJson(message.getBody());
        if (!state.firstSeen(e.eventId())) return;
        JsonNode p = e.payload();
        switch (e.type()) {
            case "trade.executed" -> {
                String m = p.path("marketId").asString();
                state.trade(m, p);
                fanout.publish("market:" + m, e.type(), p);
                fanout.publish("markets", e.type(), p);
            }
            case "book.changed" -> {
                String m = p.path("marketId").asString();
                state.book(m, p);
                fanout.publish("market:" + m, e.type(), p);
            }
            case "market.created" -> {
                state.marketCreated(p.path("id").asString());
                fanout.publish("markets", e.type(), p);
            }
            case "market.closed" -> {
                String m = p.path("marketId").asString();
                fanout.publish("market:" + m, e.type(), p);
                fanout.publish("markets", e.type(), p);
            }
            case "market.resolved" -> {
                String m = p.path("marketId").asString();
                state.marketResolved(m);
                fanout.publish("market:" + m, e.type(), p);
                fanout.publish("markets", e.type(), p);
            }
            case "news.published" -> {
                String m = p.path("marketId").asString(null);
                if (m != null && !m.isBlank()) {
                    state.news(m, p);
                    fanout.publish("market:" + m, e.type(), p);
                }
                fanout.publish("news", e.type(), p);
            }
            case "wallet.changed" -> {
                state.wallet(p);
                fanout.publish("leaderboard", e.type(), p);
                fanout.publish("user:" + p.path("userId").asString(), e.type(), p);
            }
            default -> log.debug("ignoring {}", e.type());
        }
    }
}
