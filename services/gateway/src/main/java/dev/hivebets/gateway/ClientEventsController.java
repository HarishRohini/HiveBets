package dev.hivebets.gateway;

import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.events.EventPublisher;
import dev.hivebets.common.events.EventTypes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/** Clickstream and web-vitals intake: POST /api/events → client.* events on the bus. */
@RestController
public class ClientEventsController {
    static final Set<String> TYPES = Set.of("page_view", "click", "order_submitted", "session_start", "session_end", "web_vital");
    static final int MAX_BATCH = 100;
    private final EventPublisher publisher;
    private final EventFactory events;

    public ClientEventsController(EventPublisher publisher, EventFactory events) {
        this.publisher = publisher;
        this.events = events;
    }

    public record ClientEvent(String type, String sessionId, String path, Object ts, Map<String, Object> props) {}
    public record Batch(List<ClientEvent> events) {}

    @PostMapping("/api/events")
    public Mono<ResponseEntity<Void>> ingest(@RequestBody Batch batch, ServerWebExchange exchange) {
        if (batch == null || batch.events() == null) return Mono.just(ResponseEntity.badRequest().build());
        Object p = exchange.getAttribute(AuthFilter.PRINCIPAL_ATTR);
        String userId = p instanceof TokenVerifier.Principal u ? u.id() : null;
        String ua = exchange.getRequest().getHeaders().getFirst("User-Agent");
        List<EventPublisher.Outgoing> out = new ArrayList<>();
        for (ClientEvent e : batch.events().stream().limit(MAX_BATCH).toList()) {
            if (e == null || !TYPES.contains(e.type())) continue;
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", e.sessionId());
            payload.put("userId", userId);
            payload.put("path", e.path());
            payload.put("ts", e.ts());
            payload.put("props", e.props() == null ? Map.of() : e.props());
            payload.put("userAgent", ua);
            String type = EventTypes.CLIENT_PREFIX + e.type();
            out.add(new EventPublisher.Outgoing(type, events.toJson(events.envelope(type, e.sessionId(), payload)),
                    EventFactory.currentTraceparent()));
        }
        return Mono.fromRunnable(() -> publisher.publishAll(out))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).<Void>build());
    }

    @Configuration
    static class Wiring {
        @Bean
        EventFactory eventFactory(ObjectMapper mapper) {
            return new EventFactory(mapper, "gateway");
        }

        @Bean
        EventPublisher eventPublisher(RabbitTemplate rabbit, EventFactory events) {
            return new EventPublisher(rabbit, events);
        }

        @Bean
        org.springframework.amqp.core.TopicExchange eventsExchange() {
            return dev.hivebets.common.events.AmqpTopology.eventsExchange();
        }
    }
}
