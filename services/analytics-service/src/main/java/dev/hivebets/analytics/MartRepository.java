package dev.hivebets.analytics;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads the gold tables the pipeline publishes into the {@code analytics} schema.
 * Before the first pipeline run the tables do not exist; every read then returns empty.
 */
@Repository
public class MartRepository {
    private final JdbcTemplate jdbc;

    public MartRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(String table) {
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass(?) is not null", Boolean.class, "analytics." + table));
        } catch (DataAccessException e) {
            return false;
        }
    }

    public List<Map<String, Object>> rows(String table, String sql, Object... args) {
        if (!exists(table)) return List.of();
        return jdbc.queryForList(sql, args);
    }

    public Timestamp asOf() {
        if (!exists("meta")) return null;
        List<Timestamp> t = jdbc.queryForList("select as_of from analytics.meta order by as_of desc limit 1", Timestamp.class);
        return t.isEmpty() ? null : t.get(0);
    }
}
