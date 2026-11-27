package dev.hivebets.common.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

class SchemaCatalogTest {
    static java.util.List<String> types() { return EventSchemas.TYPES; }

    @ParameterizedTest
    @MethodSource("types")
    void everyExampleMatchesItsSchema(String type) {
        EventSchemas.assertValid(type, EventSchemas.example(type));
    }

    @ParameterizedTest
    @MethodSource("types")
    void requiredFieldsAreEnforced(String type) {
        var required = EventSchemas.raw(type).path("required");
        assertTrue(required.size() > 0, type + " declares no required fields");
        ObjectNode broken = (ObjectNode) EventSchemas.example(type);
        broken.remove(required.get(0).asString());
        assertFalse(EventSchemas.errors(type, broken).isEmpty(), type + " accepted a payload missing " + required.get(0));
    }

    @Test
    void wrongTypesAreRejected() {
        ObjectNode t = (ObjectNode) EventSchemas.example("trade.executed");
        t.put("price", 150);
        assertFalse(EventSchemas.errors("trade.executed", t).isEmpty());
    }

    @Test
    void envelopesBuiltByEventFactoryMatch() {
        EventFactory f = new EventFactory(EventSchemas.MAPPER, "test");
        EventEnvelope e = f.envelope("market.closed", "k", Map.of("marketId", "5d7e6c1a-6b9f-4d3e-9a51-1f0f6c0c2b11",
                "closedAt", java.time.Instant.parse("2026-11-27T10:10:00Z")));
        EventSchemas.assertValidEnvelope(e);
        EventEnvelope back = f.fromJson(f.toJson(e).getBytes());
        assertEquals(e.eventId(), back.eventId());
        EventSchemas.assertValidEnvelope(back);
    }

    @Test
    void clientTypesShareOneSchema() {
        assertEquals("client", EventSchemas.schemaName("client.page_view"));
        assertEquals("trade.executed", EventSchemas.schemaName("trade.executed"));
    }
}
