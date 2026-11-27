package dev.hivebets.trading;

import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.events.EventPublisher;
import dev.hivebets.common.outbox.OutboxRelay;
import dev.hivebets.common.outbox.OutboxWriter;
import dev.hivebets.common.web.WebSupport;
import java.time.Clock;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootApplication
@EnableScheduling
@Import(WebSupport.class)
public class TradingApplication {
    public static final String SCHEMA = "trading";

    public static void main(String[] args) {
        SpringApplication.run(TradingApplication.class, args);
    }

    @Bean Clock clock() { return Clock.systemUTC(); }
    @Bean EventFactory eventFactory(ObjectMapper mapper) { return new EventFactory(mapper, "trading-service"); }
    @Bean EventPublisher eventPublisher(RabbitTemplate rabbit, EventFactory f) { return new EventPublisher(rabbit, f); }
    @Bean OutboxWriter outboxWriter(JdbcTemplate jdbc, EventFactory f) { return new OutboxWriter(jdbc, f, SCHEMA); }
    @Bean OutboxRelay outboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, EventPublisher p) { return new OutboxRelay(jdbc, tx, p, SCHEMA); }
    @Bean TopicExchange eventsExchange() { return dev.hivebets.common.events.AmqpTopology.eventsExchange(); }
}
