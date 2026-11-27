package dev.hivebets.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.hivebets.common.web.ApiException;
import dev.hivebets.common.web.CurrentUser;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalyticsControllerTest {
    private final MartRepository marts = mock(MartRepository.class);
    private final AnalyticsController controller = new AnalyticsController(marts);
    private final CurrentUser admin = new CurrentUser(UUID.randomUUID(), "admin", Set.of("ADMIN", "TRADER"), "HUMAN");
    private final CurrentUser trader = new CurrentUser(UUID.randomUUID(), "t", Set.of("TRADER"), "HUMAN");

    @Test
    void tradersAreRefused() {
        ApiException e = assertThrows(ApiException.class, () -> controller.funnel(trader));
        assertEquals(403, e.status());
        assertThrows(ApiException.class, () -> controller.summary(trader));
    }

    @Test
    void summaryIsZerosBeforeFirstRun() {
        when(marts.rows(anyString(), anyString())).thenReturn(java.util.List.of());
        Map<String, Object> s = controller.summary(admin);
        assertEquals(null, s.get("asOf"));
        assertEquals(0L, s.get("trades"));
        assertEquals(0L, s.get("volumeCents"));
    }

    @Test
    void efficiencyNeedsTheWorldEngine() {
        assertEquals(false, controller.efficiency(admin, "m1").get("available"));
        assertTrue(controller.markets(admin).isEmpty());
    }
}
