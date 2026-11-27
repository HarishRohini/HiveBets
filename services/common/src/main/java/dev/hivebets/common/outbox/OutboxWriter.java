package dev.hivebets.common.outbox;

import dev.hivebets.common.events.EventEnvelope;
import dev.hivebets.common.events.EventFactory;
import java.sql.Timestamp;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Transactional outbox: call inside the business transaction, the row commits with it.
 * Each service owns an {@code outbox} table in its own schema (see the Flyway migrations).
 */
public class OutboxWriter {
    private final JdbcTemplate jdbc;
    private final EventFactory events;
    private final String table;

    public OutboxWriter(JdbcTemplate jdbc, EventFactory events, String schema) {
        this.jdbc = jdbc;
        this.events = events;
        this.table = schema + ".outbox";
    }

    public EventEnvelope add(String type, String key, Object payload) {
        EventEnvelope e = events.envelope(type, key, payload);
        jdbc.update("insert into " + table
                        + " (id, event_type, event_key, envelope, traceparent, created_at) values (?, ?, ?, ?::jsonb, ?, ?)",
                UUID.fromString(e.eventId()), type, key, events.toJson(e), EventFactory.currentTraceparent(),
                Timestamp.from(e.occurredAt()));
        return e;
    }
}
