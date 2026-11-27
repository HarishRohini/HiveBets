package dev.hivebets.trading;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Read models (the JSON shapes in docs/API.md) straight from Postgres. */
@Component
public class Views {
    private final JdbcTemplate jdbc;

    public Views(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Market(UUID id, String question, String yesStatement, String noStatement, String kind, String status,
                         Instant closeAt, Instant createdAt, Instant resolvedAt, String outcome, Integer lastPrice,
                         long volumeCents, int tradeCount, int traderCount) {}

    public record Order(UUID id, UUID marketId, String side, String action, int price, int shares, int filledShares,
                        String status, long reservedCents, Integer avgFillPrice, Instant createdAt, Instant updatedAt) {}

    public record Trade(UUID id, UUID marketId, int price, int shares, String kind, UUID takerUserId, String takerUsername,
                        String takerSide, String takerAction, int takerPrice, String makerUsername, Instant executedAt) {}

    static Instant ts(ResultSet rs, String col) throws SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    static Integer intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    static Market market(ResultSet rs, int i) throws SQLException {
        return new Market(rs.getObject("id", UUID.class), rs.getString("question"), rs.getString("yes_statement"),
                rs.getString("no_statement"), rs.getString("kind"), rs.getString("status"), ts(rs, "close_at"),
                ts(rs, "created_at"), ts(rs, "resolved_at"), rs.getString("outcome"), intOrNull(rs, "last_price"),
                rs.getLong("volume_cents"), rs.getInt("trade_count"), rs.getInt("trader_count"));
    }

    static Order order(ResultSet rs, int i) throws SQLException {
        int filled = rs.getInt("filled_shares");
        long cost = rs.getLong("filled_cost_cents");
        return new Order(rs.getObject("id", UUID.class), rs.getObject("market_id", UUID.class), rs.getString("side"),
                rs.getString("action"), rs.getInt("price"), rs.getInt("shares"), filled, rs.getString("status"),
                rs.getLong("reserved_cents"), filled == 0 ? null : (int) Math.round((double) cost / filled),
                ts(rs, "created_at"), ts(rs, "updated_at"));
    }

    static Trade trade(ResultSet rs, int i) throws SQLException {
        return new Trade(rs.getObject("id", UUID.class), rs.getObject("market_id", UUID.class), rs.getInt("price"),
                rs.getInt("shares"), rs.getString("kind"), rs.getObject("taker_user_id", UUID.class),
                rs.getString("taker_username"), rs.getString("taker_side"), rs.getString("taker_action"),
                rs.getInt("taker_price"), rs.getString("maker_username"), ts(rs, "executed_at"));
    }

    public List<Market> markets(String status, String kind) {
        StringBuilder sql = new StringBuilder("select * from trading.markets where true");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.equalsIgnoreCase("ALL")) { sql.append(" and status = ?"); args.add(status.toUpperCase()); }
        if (kind != null && !kind.isBlank()) { sql.append(" and kind = ?"); args.add(kind.toUpperCase()); }
        sql.append(" order by case status when 'OPEN' then 0 when 'CLOSED' then 1 else 2 end,"
                + " case when status = 'RESOLVED' then null else close_at end asc nulls last, resolved_at desc nulls last limit 200");
        return jdbc.query(sql.toString(), Views::market, args.toArray());
    }

    public Market market(UUID id) {
        return jdbc.query("select * from trading.markets where id = ?", Views::market, id).stream().findFirst().orElse(null);
    }

    public Order order(UUID id) {
        return jdbc.query("select * from trading.orders where id = ?", Views::order, id).stream().findFirst().orElse(null);
    }

    public Order orderByKey(UUID userId, String key) {
        return jdbc.query("select * from trading.orders where user_id = ? and idempotency_key = ?", Views::order, userId, key)
                .stream().findFirst().orElse(null);
    }

    public List<Order> orders(UUID userId, String status, UUID marketId) {
        StringBuilder sql = new StringBuilder("select * from trading.orders where user_id = ?");
        List<Object> args = new ArrayList<>(List.of(userId));
        if (status == null || status.equalsIgnoreCase("OPEN")) sql.append(" and status = 'OPEN'");
        if (marketId != null) { sql.append(" and market_id = ?"); args.add(marketId); }
        sql.append(" order by created_at desc limit 200");
        return jdbc.query(sql.toString(), Views::order, args.toArray());
    }

    public List<Trade> trades(UUID marketId, int limit) {
        return jdbc.query("select * from trading.trades where market_id = ? order by seq desc limit ?", Views::trade,
                marketId, Math.min(Math.max(limit, 1), 200));
    }

    /** Payload of wallet.changed and the wallet block of the portfolio. */
    public Map<String, Object> walletSummary(UUID userId) {
        return jdbc.query("""
                select w.user_id, w.username, w.account_type, w.available_cents, w.reserved_cents,
                  coalesce((select sum(case when m.status = 'RESOLVED' then 0 else
                       p.yes_shares * coalesce(m.last_price, 50) + p.no_shares * (100 - coalesce(m.last_price, 50)) end)
                     from trading.positions p join trading.markets m on m.id = p.market_id where p.user_id = w.user_id), 0) as held,
                  (select count(*) from trading.market_traders t where t.user_id = w.user_id) as traded,
                  (select count(*) from trading.positions p join trading.markets m on m.id = p.market_id
                     where p.user_id = w.user_id and m.status = 'RESOLVED'
                       and exists (select 1 from trading.market_traders t where t.user_id = p.user_id and t.market_id = p.market_id)) as resolved,
                  (select count(*) from trading.positions p join trading.markets m on m.id = p.market_id
                     where p.user_id = w.user_id and m.status = 'RESOLVED' and coalesce(p.payout_cents, 0) > 0) as won
                from trading.wallets w where w.user_id = ?""", (rs, i) -> {
            long equity = rs.getLong("available_cents") + rs.getLong("reserved_cents") + rs.getLong("held");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", rs.getObject("user_id", UUID.class));
            m.put("username", rs.getString("username"));
            m.put("accountType", rs.getString("account_type"));
            m.put("availableCents", rs.getLong("available_cents"));
            m.put("reservedCents", rs.getLong("reserved_cents"));
            m.put("equityCents", equity);
            m.put("profitCents", equity - dev.hivebets.trading.domain.Exchange.STARTING_CENTS);
            m.put("startingCents", dev.hivebets.trading.domain.Exchange.STARTING_CENTS);
            m.put("marketsTraded", rs.getLong("traded"));
            m.put("marketsWon", rs.getLong("won"));
            m.put("marketsResolved", rs.getLong("resolved"));
            return m;
        }, userId).stream().findFirst().orElse(null);
    }

    public List<Map<String, Object>> openPositions(UUID userId) {
        return jdbc.query("""
                select p.*, m.question, m.kind, m.status, m.close_at, m.last_price from trading.positions p
                join trading.markets m on m.id = p.market_id
                where p.user_id = ? and m.status <> 'RESOLVED' and (p.yes_shares > 0 or p.no_shares > 0)
                order by m.close_at""", (rs, i) -> {
            Integer last = intOrNull(rs, "last_price");
            int px = last == null ? 50 : last;
            long yes = rs.getLong("yes_shares"), no = rs.getLong("no_shares");
            long cost = rs.getLong("yes_cost_cents") + rs.getLong("no_cost_cents");
            long value = yes * px + no * (100 - px);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("marketId", rs.getObject("market_id", UUID.class));
            m.put("question", rs.getString("question"));
            m.put("kind", rs.getString("kind"));
            m.put("status", rs.getString("status"));
            m.put("closeAt", ts(rs, "close_at"));
            m.put("yesShares", yes);
            m.put("noShares", no);
            m.put("yesCostCents", rs.getLong("yes_cost_cents"));
            m.put("noCostCents", rs.getLong("no_cost_cents"));
            m.put("lastPrice", last);
            m.put("valueCents", value);
            m.put("profitCents", value - cost);
            return m;
        }, userId);
    }

    public List<Map<String, Object>> resolvedPositions(UUID userId) {
        // Cost of a resolved position = cash paid for buys minus cash received for sells in that market.
        return jdbc.query("""
                select m.id, m.question, m.outcome, m.resolved_at, p.yes_shares, p.no_shares, coalesce(p.payout_cents, 0) as payout,
                  coalesce((select sum(case when o.action = 'BUY' then o.filled_cost_cents else -o.filled_cost_cents end)
                     from trading.orders o where o.user_id = p.user_id and o.market_id = p.market_id), 0) as net_cost
                from trading.positions p join trading.markets m on m.id = p.market_id
                where p.user_id = ? and m.status = 'RESOLVED'
                  and exists (select 1 from trading.market_traders t where t.user_id = p.user_id and t.market_id = p.market_id)
                order by m.resolved_at desc limit 100""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            long payout = rs.getLong("payout"), cost = rs.getLong("net_cost");
            m.put("marketId", rs.getObject("id", UUID.class));
            m.put("question", rs.getString("question"));
            m.put("outcome", rs.getString("outcome"));
            m.put("yesShares", rs.getLong("yes_shares"));
            m.put("noShares", rs.getLong("no_shares"));
            m.put("costCents", cost);
            m.put("payoutCents", payout);
            m.put("profitCents", payout - cost);
            m.put("resolvedAt", ts(rs, "resolved_at"));
            return m;
        }, userId);
    }
}
