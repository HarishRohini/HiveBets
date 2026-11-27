package dev.hivebets.common.events;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** Publishes envelopes to the events exchange and waits for broker confirms. */
public class EventPublisher {
    private final RabbitTemplate rabbit;
    private final EventFactory events;

    public EventPublisher(RabbitTemplate rabbit, EventFactory events) {
        this.rabbit = rabbit;
        this.events = events;
    }

    public record Outgoing(String type, String json, String traceparent) {}

    /** Publish one event now, in the current trace. */
    public void publish(String type, String key, Object payload) {
        EventEnvelope e = events.envelope(type, key, payload);
        publishAll(List.of(new Outgoing(type, events.toJson(e), null)));
    }

    /** Publish a batch inside one confirm window; throws if the broker does not confirm. */
    public void publishAll(List<Outgoing> batch) {
        if (batch.isEmpty()) return;
        rabbit.invoke(ops -> {
            for (Outgoing o : batch) {
                MessageProperties props = new MessageProperties();
                props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
                props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
                props.setType(o.type());
                Message msg = new Message(o.json().getBytes(StandardCharsets.UTF_8), props);
                // Continue the trace of the request that produced the event, so one order can be
                // followed from the gateway through the outbox to every consumer.
                try (Scope ignored = parentContext(o.traceparent()).makeCurrent()) {
                    ops.send(EventTypes.EXCHANGE, o.type(), msg);
                }
            }
            ops.waitForConfirmsOrDie(10_000);
            return null;
        });
    }

    private static final TextMapGetter<String> GETTER = new TextMapGetter<>() {
        @Override public Iterable<String> keys(String carrier) { return Collections.singletonList("traceparent"); }
        @Override public String get(String carrier, String key) { return "traceparent".equals(key) ? carrier : null; }
    };

    private static Context parentContext(String traceparent) {
        if (traceparent == null) return Context.current();
        return GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.root(), traceparent, GETTER);
    }
}
