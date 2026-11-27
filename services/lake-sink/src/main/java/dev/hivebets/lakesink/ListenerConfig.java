package dev.hivebets.lakesink;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ListenerConfig {
    /** One consumer with a prefetch big enough to hold a full buffer of unacked events. */
    @Bean
    SimpleRabbitListenerContainerFactory lakeListenerFactory(ConnectionFactory cf, LakeProperties p) {
        SimpleRabbitListenerContainerFactory f = new SimpleRabbitListenerContainerFactory();
        f.setConnectionFactory(cf);
        f.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        f.setConcurrentConsumers(1);
        f.setMaxConcurrentConsumers(1);
        f.setPrefetchCount(p.maxBufferedRows() + 1000);
        f.setDefaultRequeueRejected(false);
        return f;
    }
}
