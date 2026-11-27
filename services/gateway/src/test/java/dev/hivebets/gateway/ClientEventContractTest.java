package dev.hivebets.gateway;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.hivebets.common.events.EventSchemas;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientEventContractTest {
    @Test
    void signedInAndAnonymousEventsMatchSchema() {
        var e = new ClientEventsController.ClientEvent("web_vital", "s-1", "/markets/abc", 1795750000000L,
                Map.of("metric", "LCP", "value", 1234.5));
        EventSchemas.assertValid("client.web_vital", ClientEventsController.payload(e, "0b1f8a35-7c84-4b0e-a2b6-5e6a3d6d9f01", "Mozilla"));
        var anon = new ClientEventsController.ClientEvent("page_view", "s-2", "/login", "2026-11-27T10:00:00Z", null);
        EventSchemas.assertValid("client.page_view", ClientEventsController.payload(anon, null, null));
    }

    @Test
    void eventWithoutSessionIsRejectedBySchema() {
        var e = new ClientEventsController.ClientEvent("click", null, "/markets", 1, Map.of());
        assertFalse(EventSchemas.errors("client.click", ClientEventsController.payload(e, null, null)).isEmpty());
    }
}
