package dev.hivebets.realtime;

import dev.hivebets.common.events.AmqpTopology;
import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.web.WebSupport;
import org.springframework.amqp.core.Declarables;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

@SpringBootApplication
@EnableScheduling
@Import(WebSupport.class)
public class RealtimeApplication {
    public static final String QUEUE = "realtime.events";

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }

    @Bean
    Declarables realtimeQueue() {
        return AmqpTopology.consumerQueue(QUEUE, "market.#", "trade.#", "book.#", "wallet.#", "news.#");
    }

    @Bean
    EventFactory eventFactory(ObjectMapper mapper) {
        return new EventFactory(mapper, "realtime-service");
    }
}
