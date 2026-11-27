package dev.hivebets.lakesink;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.stereotype.Component;

/** Writes a batch of bronze rows to a local Parquet file using in-memory DuckDB. */
@Component
public class ParquetWriter {

    public Path write(List<BronzeRow> rows) throws Exception {
        Path file = Files.createTempFile("bronze-", ".parquet");
        Files.delete(file); // DuckDB refuses to overwrite with COPY on some platforms; let it create the file.
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
            try (Statement s = c.createStatement()) {
                s.execute("SET TimeZone = 'UTC'");
                s.execute("""
                        CREATE TABLE bronze (
                          event_id VARCHAR, event_type VARCHAR, version INTEGER,
                          occurred_at TIMESTAMPTZ, source VARCHAR, event_key VARCHAR,
                          trace_id VARCHAR, payload VARCHAR, received_at TIMESTAMPTZ)""");
            }
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO bronze VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (BronzeRow r : rows) {
                    ps.setString(1, r.eventId());
                    ps.setString(2, r.eventType());
                    ps.setInt(3, r.version());
                    ps.setTimestamp(4, r.occurredAt() == null ? null : Timestamp.from(r.occurredAt()));
                    ps.setString(5, r.source());
                    ps.setString(6, r.eventKey());
                    ps.setString(7, r.traceId());
                    ps.setString(8, r.payloadJson());
                    ps.setTimestamp(9, Timestamp.from(r.receivedAt()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                s.execute("COPY bronze TO '" + file.toString().replace("'", "''")
                        + "' (FORMAT parquet, COMPRESSION zstd)");
            }
        }
        return file;
    }
}
