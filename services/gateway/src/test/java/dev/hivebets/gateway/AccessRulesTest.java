package dev.hivebets.gateway;

import static dev.hivebets.gateway.AccessRules.Need.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

class AccessRulesTest {
    @Test
    void publicPaths() {
        assertEquals(PUBLIC, AccessRules.need(HttpMethod.POST, "/api/auth/login"));
        assertEquals(PUBLIC, AccessRules.need(HttpMethod.POST, "/api/events"));
        assertEquals(PUBLIC, AccessRules.need(HttpMethod.OPTIONS, "/api/orders"));
    }

    @Test
    void adminPaths() {
        assertEquals(ADMIN, AccessRules.need(HttpMethod.GET, "/api/analytics/summary"));
        assertEquals(ADMIN_OR_ENGINE, AccessRules.need(HttpMethod.POST, "/api/markets"));
        assertEquals(ADMIN_OR_ENGINE, AccessRules.need(HttpMethod.POST, "/api/markets/abc/resolve"));
        assertEquals(SIGNED_IN, AccessRules.need(HttpMethod.GET, "/api/markets"));
        assertEquals(SIGNED_IN, AccessRules.need(HttpMethod.POST, "/api/orders"));
    }

    @Test
    void traderIsRefusedOnAdminRoutes() {
        assertFalse(AccessRules.allows(ADMIN, Set.of("TRADER")));
        assertFalse(AccessRules.allows(ADMIN_OR_ENGINE, Set.of("TRADER")));
        assertTrue(AccessRules.allows(ADMIN_OR_ENGINE, Set.of("ENGINE")));
        assertTrue(AccessRules.allows(ADMIN, Set.of("ADMIN", "TRADER")));
    }
}
