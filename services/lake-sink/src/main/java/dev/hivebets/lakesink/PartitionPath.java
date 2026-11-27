package dev.hivebets.lakesink;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

/** Object keys of the bronze layer: {@code bronze/<event_type>/dt=YYYY-MM-DD/hr=HH/part-<uuid>.parquet}. */
public final class PartitionPath {
    private PartitionPath() {}

    /** The (type, day, hour) bucket an event belongs to; occurredAt is read in UTC. */
    public record Partition(String eventType, String dt, String hr) {
        public String prefix() {
            return "bronze/" + eventType + "/dt=" + dt + "/hr=" + hr + "/";
        }

        public String objectKey(UUID part) {
            return prefix() + "part-" + part + ".parquet";
        }
    }

    public static Partition of(String eventType, Instant occurredAt) {
        ZonedDateTime t = (occurredAt == null ? Instant.now() : occurredAt).atZone(ZoneOffset.UTC);
        return new Partition(safeType(eventType),
                String.format("%04d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth()),
                String.format("%02d", t.getHour()));
    }

    /** Event types become folder names, so keep them to lower-case letters, digits, dot, dash and underscore. */
    static String safeType(String type) {
        if (type == null || type.isBlank()) return "unknown";
        String s = type.toLowerCase().replaceAll("[^a-z0-9._-]", "_");
        s = s.replaceAll("^\\.+", "_");
        return s.isEmpty() ? "unknown" : s;
    }
}
