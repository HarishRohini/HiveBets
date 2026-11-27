package dev.hivebets.common.events;

import static org.junit.jupiter.api.Assertions.fail;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Contract tests for the event bus. Schemas live in common's resources (events/schemas/<type>.schema.json)
 * and are shared by producers' tests (what we emit must match) and consumers' tests (the schema's
 * example must be enough for the consumer). A producer change that breaks a consumer fails a build.
 */
public final class EventSchemas {
    private EventSchemas() {}

    public static final List<String> TYPES = List.of("envelope", "market.created", "market.closed", "market.resolved",
            "order.accepted", "order.cancelled", "trade.executed", "book.changed", "wallet.changed", "news.published", "client");

    public static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final Map<String, Schema> SCHEMAS = new ConcurrentHashMap<>();
    private static final Map<String, JsonNode> RAW = new ConcurrentHashMap<>();

    /** client.page_view, client.click, ... all share the "client" schema. */
    public static String schemaName(String type) {
        return type.startsWith(EventTypes.CLIENT_PREFIX) ? "client" : type;
    }

    public static JsonNode raw(String type) {
        return RAW.computeIfAbsent(schemaName(type), name -> {
            try (InputStream in = EventSchemas.class.getResourceAsStream("/events/schemas/" + name + ".schema.json")) {
                if (in == null) throw new IllegalArgumentException("no schema for event type " + name);
                return MAPPER.readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static Schema schema(String type) {
        return SCHEMAS.computeIfAbsent(schemaName(type), name -> REGISTRY.getSchema(raw(name).toString()));
    }

    /** The schema's first example payload, as a consumer would receive it. */
    public static JsonNode example(String type) {
        return raw(type).path("examples").get(0).deepCopy();
    }

    public static List<String> errors(String type, Object payload) {
        JsonNode node = payload instanceof JsonNode j ? j : MAPPER.valueToTree(payload);
        return schema(type).validate(node).stream().map(Error::toString).toList();
    }

    public static void assertValid(String type, Object payload) {
        List<String> errors = errors(type, payload);
        if (!errors.isEmpty()) {
            JsonNode node = payload instanceof JsonNode j ? j : MAPPER.valueToTree(payload);
            fail(type + " payload breaks its schema:\n  " + String.join("\n  ", errors) + "\npayload: " + node);
        }
    }

    /** Validates a full envelope (as it would travel on the bus) and its payload. */
    public static void assertValidEnvelope(EventEnvelope e) {
        assertValid("envelope", e);
        assertValid(e.type(), e.payload());
    }
}
