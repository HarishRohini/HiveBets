package dev.hivebets.analytics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** hivebets.analytics.data.age: seconds since the pipeline last published the gold tables (-1 if never). */
@Component
public class FreshnessGauge {
    private final JdbcTemplate jdbc;

    public FreshnessGauge(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("hivebets.analytics.data.age", this, FreshnessGauge::ageSeconds)
                .baseUnit("seconds")
                .description("Seconds since the lake pipeline last refreshed the analytics tables")
                .register(registry);
    }

    double ageSeconds() {
        try {
            List<Timestamp> t = jdbc.queryForList("select max(as_of) from analytics.meta", Timestamp.class);
            if (t.isEmpty() || t.getFirst() == null) return -1;
            return (System.currentTimeMillis() - t.getFirst().getTime()) / 1000.0;
        } catch (RuntimeException e) {
            return -1;
        }
    }
}
