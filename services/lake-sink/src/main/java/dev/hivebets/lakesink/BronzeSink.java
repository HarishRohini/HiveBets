package dev.hivebets.lakesink;

import com.rabbitmq.client.Channel;
import dev.hivebets.common.events.AmqpTopology;
import dev.hivebets.common.events.EventEnvelope;
import dev.hivebets.common.events.EventFactory;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes every event and lands it in the bronze layer. Messages are acked only after the
 * Parquet file holding them has been uploaded; if an upload fails they are requeued.
 */
@Component
public class BronzeSink {
    public static final String QUEUE = "lake-sink.events";
    private static final Logger log = LoggerFactory.getLogger(BronzeSink.class);

    private final ParquetWriter writer;
    private final LakeStore store;
    private final LakeProperties props;
    private final EventFactory events;
    private final Map<PartitionPath.Partition, List<BronzeRow>> buffer = new HashMap<>();
    private int buffered;

    public BronzeSink(ParquetWriter writer, LakeStore store, LakeProperties props, ObjectMapper mapper) {
        this.writer = writer;
        this.store = store;
        this.props = props;
        this.events = new EventFactory(mapper, "lake-sink");
    }

    @Configuration(proxyBeanMethods = false)
    static class Topology {
        @Bean
        Declarables lakeSinkQueue() {
            return AmqpTopology.consumerQueue(QUEUE, "#");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            store.ensureBucket();
        } catch (Exception e) {
            log.warn("lake bucket not ready yet, will retry on first flush: {}", e.toString());
        }
    }

    @RabbitListener(queues = QUEUE, ackMode = "MANUAL", containerFactory = "lakeListenerFactory")
    public void onMessage(Message message, Channel channel) throws Exception {
        long tag = message.getMessageProperties().getDeliveryTag();
        EventEnvelope e;
        try {
            e = events.fromJson(message.getBody());
        } catch (Exception bad) {
            log.warn("dropping unreadable message to the dead-letter queue: {}", bad.toString());
            channel.basicReject(tag, false);
            return;
        }
        String type = e.type() != null ? e.type() : message.getMessageProperties().getReceivedRoutingKey();
        BronzeRow row = new BronzeRow(e.eventId(), type, e.version(), e.occurredAt(), e.source(), e.key(), e.traceId(),
                e.payload() == null ? null : events.mapper().writeValueAsString(e.payload()), Instant.now(), channel, tag);
        PartitionPath.Partition p = PartitionPath.of(type, e.occurredAt());
        synchronized (this) {
            List<BronzeRow> rows = buffer.computeIfAbsent(p, k -> new ArrayList<>());
            rows.add(row);
            buffered++;
            if (rows.size() >= props.flushRows()) {
                flushPartition(p);
            } else if (buffered >= props.maxBufferedRows()) {
                flushAll();
            }
        }
    }

    @Scheduled(fixedDelayString = "${hivebets.lake.flush-interval-ms:30000}")
    public synchronized void flushAll() {
        for (PartitionPath.Partition p : new ArrayList<>(buffer.keySet())) {
            flushPartition(p);
        }
    }

    @PreDestroy
    public void onShutdown() {
        flushAll();
    }

    /** Must hold the monitor. */
    private void flushPartition(PartitionPath.Partition p) {
        List<BronzeRow> rows = buffer.remove(p);
        if (rows == null || rows.isEmpty()) return;
        buffered -= rows.size();
        Path file = null;
        try {
            file = writer.write(rows);
            String key = p.objectKey(UUID.randomUUID());
            store.put(key, file);
            ack(rows, true);
            log.info("landed {} {} events at {}", rows.size(), p.eventType(), key);
        } catch (Exception ex) {
            log.warn("flush of {} failed, requeueing {} events: {}", p.prefix(), rows.size(), ex.toString());
            ack(rows, false);
        } finally {
            if (file != null) {
                try { Files.deleteIfExists(file); } catch (Exception ignored) { }
            }
        }
    }

    private static void ack(List<BronzeRow> rows, boolean ok) {
        for (BronzeRow r : rows) {
            try {
                if (ok) r.channel().basicAck(r.deliveryTag(), false);
                else r.channel().basicNack(r.deliveryTag(), false, true);
            } catch (Exception e) {
                // Channel gone: the broker redelivers; silver models dedupe on event_id.
                log.debug("could not settle delivery {}: {}", r.deliveryTag(), e.toString());
            }
        }
    }

    synchronized int bufferedCount() {
        return buffered;
    }
}
