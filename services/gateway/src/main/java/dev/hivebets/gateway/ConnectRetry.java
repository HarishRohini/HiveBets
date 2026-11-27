package dev.hivebets.gateway;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.time.Duration;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.factory.RetryGatewayFilterFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;

/**
 * Retries a request when the backend could not be reached at all (connection refused, host not
 * in DNS yet), e.g. while a service restarts. Nothing was sent, so this is safe for every method,
 * POSTs included. Responses the backend did return, 5xx too, are never retried.
 */
@Configuration
public class ConnectRetry {
    static RetryGatewayFilterFactory.RetryConfig config() {
        RetryGatewayFilterFactory.RetryConfig c = new RetryGatewayFilterFactory.RetryConfig();
        c.setRetries(6);
        c.setSeries();
        c.setStatuses();
        c.setMethods(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);
        c.setExceptions(ConnectException.class, UnknownHostException.class);
        c.setBackoff(new RetryGatewayFilterFactory.BackoffConfig(Duration.ofMillis(250), Duration.ofSeconds(3), 2));
        return c;
    }

    @Bean
    GlobalFilter connectRetryFilter() {
        GatewayFilter retry = new RetryGatewayFilterFactory().apply(config());
        return new OrderedGlobalFilter(retry, 0);
    }

    record OrderedGlobalFilter(GatewayFilter delegate, int order) implements GlobalFilter, Ordered {
        @Override
        public reactor.core.publisher.Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange,
                                                        org.springframework.cloud.gateway.filter.GatewayFilterChain chain) {
            return delegate.filter(exchange, chain);
        }

        @Override
        public int getOrder() {
            return order;
        }
    }
}
