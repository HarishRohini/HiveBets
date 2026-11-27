package dev.hivebets.common.events;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Builds envelopes and serialises them; also captures the current trace context. */
public class EventFactory {
    private final ObjectMapper mapper;
    private final String source;

    public EventFactory(ObjectMapper mapper, String source) {
        this.mapper = mapper;
        this.source = source;
    }

    public EventEnvelope envelope(String type, String key, Object payload) {
        JsonNode node = payload instanceof JsonNode j ? j : mapper.valueToTree(payload);
        SpanContext ctx = Span.current().getSpanContext();
        String traceId = ctx.isValid() ? ctx.getTraceId() : null;
        return new EventEnvelope(UUID.randomUUID().toString(), type, 1, Instant.now(), source, key, traceId, node);
    }

    /** W3C traceparent of the current span, or null when nothing is being traced. */
    public static String currentTraceparent() {
        SpanContext ctx = Span.current().getSpanContext();
        if (!ctx.isValid()) return null;
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + ctx.getTraceFlags().asHex();
    }

    public String toJson(EventEnvelope e) {
        return mapper.writeValueAsString(e);
    }

    public EventEnvelope fromJson(byte[] body) {
        return mapper.readValue(body, EventEnvelope.class);
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String source() {
        return source;
    }
}
