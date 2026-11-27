package dev.hivebets.news;

import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.events.EventPublisher;
import dev.hivebets.common.web.WebSupport;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.ObjectMapper;

@SpringBootApplication
@EnableScheduling
@Import(WebSupport.class)
public class NewsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NewsApplication.class, args);
    }

    @Bean EventFactory eventFactory(ObjectMapper mapper) { return new EventFactory(mapper, "news-service"); }
    @Bean EventPublisher eventPublisher(RabbitTemplate rabbit, EventFactory f) { return new EventPublisher(rabbit, f); }
    @Bean TopicExchange eventsExchange() { return dev.hivebets.common.events.AmqpTopology.eventsExchange(); }
}
