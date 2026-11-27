package dev.hivebets.gateway;

import java.net.InetSocketAddress;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/** Token-bucket keys (buckets live in Redis): per signed-in user, and per username on the login route. */
@Configuration
public class RateLimitKeys {
    private static final Pattern USERNAME = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]{0,64})\"");

    @Bean
    @Primary
    KeyResolver userKeyResolver() {
        return exchange -> {
            Object p = exchange.getAttribute(AuthFilter.PRINCIPAL_ATTR);
            if (p instanceof TokenVerifier.Principal u) return Mono.just("user:" + u.id());
            InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
            return Mono.just("ip:" + (addr == null ? "unknown" : addr.getAddress().getHostAddress()));
        };
    }

    /** All agents share one IP, so login is limited per username rather than per address. */
    @Bean
    KeyResolver loginUsernameKeyResolver() {
        return exchange -> {
            Object body = exchange.getAttribute(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR);
            String name = "unknown";
            if (body instanceof String s) {
                Matcher m = USERNAME.matcher(s);
                if (m.find()) name = m.group(1).trim().toLowerCase();
            }
            return Mono.just("login:" + name);
        };
    }
}
