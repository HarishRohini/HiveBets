package dev.hivebets.gateway;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Runs before routing (as a WebFilter, so it also covers the gateway's own /api/events endpoint):
 * strips spoofed identity headers, verifies the session, enforces the access rules and forwards
 * the caller as X-User-* headers.
 */
@Component
public class AuthFilter implements WebFilter, Ordered {
    public static final String PRINCIPAL_ATTR = "hivebets.principal";
    private final TokenVerifier verifier;
    private final String cookieName;

    public AuthFilter(TokenVerifier verifier, @Value("${hivebets.cookie-name}") String cookieName) {
        this.verifier = verifier;
        this.cookieName = cookieName;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getPath().value();
        AccessRules.Need need = AccessRules.need(req.getMethod(), path);
        String requestId = req.getHeaders().getFirst("X-Request-Id");
        String rid = requestId != null && requestId.length() <= 64 ? requestId : UUID.randomUUID().toString();

        return verifier.verify(token(req))
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(p -> {
                    if (p.isEmpty() && need != AccessRules.Need.PUBLIC) {
                        return deny(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in first.");
                    }
                    if (p.isPresent() && !AccessRules.allows(need, p.get().roles())) {
                        return deny(exchange, HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to do that.");
                    }
                    ServerHttpRequest.Builder b = req.mutate().headers(h -> {
                        h.remove("X-User-Id");
                        h.remove("X-User-Name");
                        h.remove("X-User-Roles");
                        h.remove("X-Account-Type");
                        h.set("X-Request-Id", rid);
                        p.ifPresent(u -> {
                            h.set("X-User-Id", u.id());
                            h.set("X-User-Name", u.username());
                            h.set("X-User-Roles", String.join(",", u.roles()));
                            h.set("X-Account-Type", u.accountType() == null ? "HUMAN" : u.accountType());
                        });
                    });
                    exchange.getResponse().getHeaders().set("X-Request-Id", rid);
                    ServerWebExchange ex = exchange.mutate().request(b.build()).build();
                    p.ifPresent(u -> ex.getAttributes().put(PRINCIPAL_ATTR, u));
                    return chain.filter(ex);
                });
    }

    private String token(ServerHttpRequest req) {
        String auth = req.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (auth != null && auth.startsWith("Bearer ")) return auth.substring(7).trim();
        HttpCookie c = req.getCookies().getFirst(cookieName);
        return c == null ? null : c.getValue();
    }

    static Mono<Void> deny(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse res = exchange.getResponse();
        res.setStatusCode(status);
        res.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
        return res.writeWith(Mono.just(res.bufferFactory().wrap(body)));
    }
}
