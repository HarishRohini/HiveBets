package dev.hivebets.lakesink;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParquetWriterTest {

    @Test
    void writesReadableParquet() throws Exception {
        Instant t = Instant.parse("2026-11-27T04:00:00Z");
        List<BronzeRow> rows = List.of(
                new BronzeRow("e1", "trade.executed", 1, t, "trading-service", "m1", null, "{\"price\":55}", t, null, 1),
                new BronzeRow("e2", "trade.executed", 1, t.plusSeconds(1), "trading-service", "m1", "abc", "{\"price\":56}", t, null, 2));
        Path file = new ParquetWriter().write(rows);
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
             ResultSet rs = c.createStatement().executeQuery(
                     "select count(*), max(payload->>'price')::int, min(epoch(occurred_at))::bigint from read_parquet('" + file + "')")) {
            rs.next();
            assertEquals(2, rs.getInt(1));
            assertEquals(56, rs.getInt(2));
            assertEquals(t.getEpochSecond(), rs.getLong(3));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
