package dev.hivebets.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class AuthFilterTest {
    TokenVerifier verifier = mock(TokenVerifier.class);
    AuthFilter filter = new AuthFilter(verifier, "hb_session");
    AtomicReference<ServerWebExchange> passed = new AtomicReference<>();
    WebFilterChain chain = ex -> { passed.set(ex); return Mono.empty(); };

    @Test
    void spoofedIdentityHeadersAreStripped() {
        when(verifier.verify(any())).thenReturn(Mono.empty());
        var ex = MockServerWebExchange.from(MockServerHttpRequest.post("/api/events")
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001").header("X-User-Roles", "ADMIN"));
        filter.filter(ex, chain).block();
        var h = passed.get().getRequest().getHeaders();
        assertNull(h.getFirst("X-User-Id"));
        assertNull(h.getFirst("X-User-Roles"));
    }

    @Test
    void signedInCallerIsForwardedAsHeaders() {
        when(verifier.verify("tok")).thenReturn(Mono.just(new TokenVerifier.Principal("u-1", "alice", Set.of("TRADER"), "HUMAN")));
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/markets").cookie(new org.springframework.http.HttpCookie("hb_session", "tok")));
        filter.filter(ex, chain).block();
        var h = passed.get().getRequest().getHeaders();
        assertEquals("u-1", h.getFirst("X-User-Id"));
        assertEquals("alice", h.getFirst("X-User-Name"));
        assertEquals("TRADER", h.getFirst("X-User-Roles"));
    }

    @Test
    void missingSessionIsUnauthorized() {
        when(verifier.verify(any())).thenReturn(Mono.empty());
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/portfolio"));
        filter.filter(ex, chain).block();
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getResponse().getStatusCode());
        assertNull(passed.get());
    }

    @Test
    void traderOnAdminRouteIsForbidden() {
        when(verifier.verify("tok")).thenReturn(Mono.just(new TokenVerifier.Principal("u-1", "alice", Set.of("TRADER"), "HUMAN")));
        var ex = MockServerWebExchange.from(MockServerHttpRequest.get("/api/analytics/summary").header("Authorization", "Bearer tok"));
        filter.filter(ex, chain).block();
        assertEquals(HttpStatus.FORBIDDEN, ex.getResponse().getStatusCode());
    }

    @Test
    void loginRateLimitIsKeyedByUsername() {
        var resolver = new RateLimitKeys().loginUsernameKeyResolver();
        var ex = MockServerWebExchange.from(MockServerHttpRequest.post("/api/auth/login"));
        ex.getAttributes().put(ServerWebExchangeUtils.CACHED_REQUEST_BODY_ATTR, "{\"username\": \"Quiet-Quant-0502\", \"password\":\"x\"}");
        assertEquals("login:quiet-quant-0502", resolver.resolve(ex).block());
    }
}
