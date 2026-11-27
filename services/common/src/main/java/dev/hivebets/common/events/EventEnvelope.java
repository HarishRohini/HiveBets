package dev.hivebets.common.events;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

/** The wrapper every event on the bus carries. See docs/API.md. */
public record EventEnvelope(
        String eventId,
        String type,
        int version,
        Instant occurredAt,
        String source,
        String key,
        String traceId,
        JsonNode payload) {
}
