package dev.hivebets.gateway;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

/**
 * CORS for every path, as one WebFilter. (The gateway's globalcors setting only covers proxied
 * routes, not the gateway's own /api/events endpoint.)
 */
@Configuration
public class CorsConfig {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    CorsWebFilter corsFilter(@Value("${WEB_ORIGIN:http://localhost:3000}") String origin) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(List.of(origin));
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        c.setAllowedHeaders(List.of("*"));
        c.setAllowCredentials(true);
        c.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // /ws is guarded separately by spring.cloud.gateway.server.webflux.websocket.allowed-origins.
        source.registerCorsConfiguration("/api/**", c);
        return new CorsWebFilter(source);
    }
}
