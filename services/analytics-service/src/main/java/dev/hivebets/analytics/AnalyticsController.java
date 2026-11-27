package dev.hivebets.analytics;

import dev.hivebets.common.web.CurrentUser;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only, read-only JSON over the gold marts. Column aliases are the camelCase names in docs/API.md. */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {
    private final MartRepository marts;

    public AnalyticsController(MartRepository marts) {
        this.marts = marts;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary(CurrentUser user) {
        user.require("ADMIN");
        Timestamp asOf = marts.asOf();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", asOf == null ? null : asOf.toInstant().toString());
        List<Map<String, Object>> s = marts.rows("daily_summary", """
                select coalesce(sum(markets_resolved), 0)::bigint as "marketsResolved",
                       coalesce(sum(trades), 0)::bigint as "trades",
                       coalesce(sum(volume_cents), 0)::bigint as "volumeCents",
                       coalesce(sum(sessions), 0)::bigint as "sessions",
                       coalesce(sum(orders), 0)::bigint as "orders"
                from analytics.daily_summary""");
        Map<String, Object> totals = s.isEmpty() ? Map.of() : s.get(0);
        for (String k : List.of("marketsResolved", "trades", "volumeCents", "sessions", "orders")) {
            Object v = totals.get(k);
            out.put(k, v == null ? 0L : ((Number) v).longValue());
        }
        return out;
    }

    @GetMapping("/markets")
    public List<Map<String, Object>> markets(CurrentUser user) {
        user.require("ADMIN");
        return marts.rows("market_activity", """
                select market_id as "marketId", question, kind, status,
                       volume_cents::bigint as "volumeCents", trades::bigint as trades, traders::bigint as traders,
                       avg_spread_cents::float8 as "avgSpreadCents", avg_depth_shares::float8 as "avgDepthShares"
                from analytics.market_activity order by volume_cents desc nulls last limit 500""");
    }

    @GetMapping("/markets/{id}/efficiency")
    public Map<String, Object> efficiency(CurrentUser user, @PathVariable String id) {
        user.require("ADMIN");
        return Map.of("marketId", id, "available", false, "reason", "needs the world engine (Phase 4)");
    }

    @GetMapping("/funnel")
    public List<Map<String, Object>> funnel(CurrentUser user) {
        user.require("ADMIN");
        return marts.rows("session_funnel",
                "select step, sessions::bigint as sessions from analytics.session_funnel order by step_order");
    }

    @GetMapping("/web-vitals")
    public List<Map<String, Object>> webVitals(CurrentUser user) {
        user.require("ADMIN");
        return marts.rows("web_vitals",
                "select metric, page, p75::float8 as p75, samples::bigint as samples from analytics.web_vitals order by metric, page");
    }

    @GetMapping("/traders")
    public List<Map<String, Object>> traders(CurrentUser user) {
        user.require("ADMIN");
        return marts.rows("trader_profit", """
                select user_id as "userId", username, account_type as "accountType",
                       profit_cents::bigint as "profitCents", trades::bigint as trades, volume_cents::bigint as "volumeCents"
                from analytics.trader_profit order by profit_cents desc nulls last limit 500""");
    }
}
