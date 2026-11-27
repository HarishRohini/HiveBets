package dev.hivebets.lakesink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartitionPathTest {

    @Test
    void partitionsByUtcDayAndHour() {
        PartitionPath.Partition p = PartitionPath.of("trade.executed", Instant.parse("2026-11-27T04:59:59.999Z"));
        assertEquals("bronze/trade.executed/dt=2026-11-27/hr=04/", p.prefix());
    }

    @Test
    void midnightRollsTheDay() {
        assertEquals("bronze/x/dt=2026-12-31/hr=23/", PartitionPath.of("x", Instant.parse("2026-12-31T23:30:00Z")).prefix());
        assertEquals("bronze/x/dt=2027-01-01/hr=00/", PartitionPath.of("x", Instant.parse("2027-01-01T00:00:00Z")).prefix());
    }

    @Test
    void objectKeyIsAParquetPart() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String key = PartitionPath.of("client.page_view", Instant.parse("2026-01-02T03:04:05Z")).objectKey(id);
        assertEquals("bronze/client.page_view/dt=2026-01-02/hr=03/part-00000000-0000-0000-0000-000000000001.parquet", key);
    }

    @Test
    void unsafeTypesAreSanitised() {
        assertEquals("unknown", PartitionPath.safeType(null));
        assertEquals("a_b_c", PartitionPath.safeType("A/B C"));
        String t = PartitionPath.safeType("../../etc");
        assertTrue(!t.contains("/") && !t.startsWith("."), t);
    }

    @Test
    void missingTimestampUsesNow() {
        assertTrue(PartitionPath.of("x", null).prefix().startsWith("bronze/x/dt="));
    }
}
