package dev.hivebets.common.events;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/** Exchanges and consumer queues. Every queue gets its own dead-letter queue. */
public final class AmqpTopology {
    private AmqpTopology() {}

    public static TopicExchange eventsExchange() {
        return new TopicExchange(EventTypes.EXCHANGE, true, false);
    }

    public static DirectExchange deadLetterExchange() {
        return new DirectExchange(EventTypes.DEAD_LETTER_EXCHANGE, true, false);
    }

    /** A durable consumer queue bound to the given routing patterns, plus {@code <queue>.dlq}. */
    public static Declarables consumerQueue(String queue, String... bindingPatterns) {
        TopicExchange events = eventsExchange();
        DirectExchange dlx = deadLetterExchange();
        Queue q = QueueBuilder.durable(queue)
                .deadLetterExchange(EventTypes.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(queue + ".dlq")
                .build();
        Queue dlq = QueueBuilder.durable(queue + ".dlq").build();
        List<Declarable> all = new ArrayList<>(List.of(events, dlx, q, dlq,
                BindingBuilder.bind(dlq).to(dlx).with(queue + ".dlq")));
        for (String pattern : bindingPatterns) {
            Binding b = BindingBuilder.bind(q).to(events).with(pattern);
            all.add(b);
        }
        return new Declarables(all);
    }
}
