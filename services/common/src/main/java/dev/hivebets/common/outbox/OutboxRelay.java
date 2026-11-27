package dev.hivebets.common.outbox;

import dev.hivebets.common.events.EventPublisher;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Polls the outbox and publishes rows in commit order. Rows are locked with SKIP LOCKED so
 * several instances can run side by side. Delivery is at-least-once; consumers dedupe on eventId.
 */
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EventPublisher publisher;
    private final String table;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, EventPublisher publisher, String schema) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.publisher = publisher;
        this.table = schema + ".outbox";
    }

    private record Row(UUID id, String type, String envelope, String traceparent) {}

    @Scheduled(fixedDelayString = "${hivebets.outbox.poll-ms:100}")
    public void relay() {
        try {
            int n;
            do {
                n = relayBatch();
            } while (n == 500);
        } catch (Exception e) {
            log.warn("outbox relay failed, will retry: {}", e.toString());
        }
    }

    int relayBatch() {
        Integer count = tx.execute(status -> {
            List<Row> rows = jdbc.query("select id, event_type, envelope::text, traceparent from " + table
                            + " where published_at is null order by seq limit 500 for update skip locked",
                    (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)));
            if (rows.isEmpty()) return 0;
            publisher.publishAll(rows.stream()
                    .map(r -> new EventPublisher.Outgoing(r.type(), r.envelope(), r.traceparent())).toList());
            jdbc.batchUpdate("update " + table + " set published_at = now() where id = ?",
                    rows.stream().map(r -> new Object[] {r.id()}).toList());
            return rows.size();
        });
        return count == null ? 0 : count;
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void cleanup() {
        int deleted = jdbc.update("delete from " + table + " where published_at < now() - interval '1 hour'");
        if (deleted > 0) log.info("outbox cleanup removed {} published rows", deleted);
    }
}
