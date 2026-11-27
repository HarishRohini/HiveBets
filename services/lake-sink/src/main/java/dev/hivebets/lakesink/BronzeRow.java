package dev.hivebets.lakesink;

import com.rabbitmq.client.Channel;
import java.time.Instant;

/** One buffered event, with the delivery it must ack once it is safely in the lake. */
public record BronzeRow(
        String eventId,
        String eventType,
        int version,
        Instant occurredAt,
        String source,
        String eventKey,
        String traceId,
        String payloadJson,
        Instant receivedAt,
        Channel channel,
        long deliveryTag) {
}
