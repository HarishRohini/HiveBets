package dev.hivebets.trading;

import dev.hivebets.common.outbox.OutboxWriter;
import dev.hivebets.trading.domain.Exchange;
import dev.hivebets.trading.domain.ExchangeState;
import dev.hivebets.trading.domain.MarketState;
import dev.hivebets.trading.domain.OrderRec;
import dev.hivebets.trading.domain.Position;
import dev.hivebets.trading.domain.Posting;
import dev.hivebets.trading.domain.TradeRec;
import dev.hivebets.trading.domain.Types;
import dev.hivebets.trading.domain.Wallet;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ExchangeState backed by one Postgres transaction. Rows are loaded (and wallet rows locked
 * FOR UPDATE) on first use, changed in memory, and written back by {@link #flush()}.
 */
class JdbcState implements ExchangeState {
    private final JdbcTemplate jdbc;
    private final OutboxWriter outbox;
    private final Map<UUID, Wallet> wallets = new HashMap<>();
    private final Map<String, Position> positions = new HashMap<>();
    private final Map<UUID, MarketState> markets = new HashMap<>();
    private final Map<UUID, OrderRec> orders = new LinkedHashMap<>();
    private final List<TradeRec> trades = new ArrayList<>();
    private final List<Object[]> ledger = new ArrayList<>();

    JdbcState(JdbcTemplate jdbc, OutboxWriter outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    private static Wallet mapWallet(ResultSet rs, int i) throws SQLException {
        return new Wallet(rs.getObject("user_id", UUID.class), rs.getString("username"), rs.getString("account_type"),
                rs.getLong("available_cents"), rs.getLong("reserved_cents"));
    }

    @Override
    public Wallet wallet(UUID userId, String username, String accountType) {
        Wallet w = wallets.get(userId);
        if (w != null) return w;
        int created = jdbc.update("insert into trading.wallets (user_id, username, account_type, available_cents, reserved_cents)"
                + " values (?, ?, ?, ?, 0) on conflict do nothing", userId, username,
                accountType == null ? "HUMAN" : accountType, Exchange.STARTING_CENTS);
        if (created == 1) {
            post("grant", List.of(Posting.bankroll(-Exchange.STARTING_CENTS), Posting.available(userId, Exchange.STARTING_CENTS)));
        }
        lockWallets(List.of(userId));
        return wallets.get(userId);
    }

    @Override
    public void lockWallets(Collection<UUID> userIds) {
        List<UUID> missing = userIds.stream().filter(u -> !wallets.containsKey(u)).distinct().sorted().toList();
        if (missing.isEmpty()) return;
        // Always lock in user-id order to keep cross-market deadlocks rare.
        List<Wallet> rows = jdbc.query("select * from trading.wallets where user_id = any(?) order by user_id for update",
                JdbcState::mapWallet, (Object) missing.toArray(new UUID[0]));
        for (Wallet w : rows) wallets.put(w.userId, w);
    }

    @Override
    public Wallet wallet(UUID userId) {
        lockWallets(List.of(userId));
        Wallet w = wallets.get(userId);
        if (w == null) throw new IllegalStateException("no wallet for " + userId);
        return w;
    }

    private static Position mapPosition(ResultSet rs, int i) throws SQLException {
        Position p = new Position(rs.getObject("user_id", UUID.class), rs.getObject("market_id", UUID.class));
        p.yes = rs.getLong("yes_shares");
        p.no = rs.getLong("no_shares");
        p.yesReserved = rs.getLong("yes_reserved");
        p.noReserved = rs.getLong("no_reserved");
        p.yesCost = rs.getLong("yes_cost_cents");
        p.noCost = rs.getLong("no_cost_cents");
        long payout = rs.getLong("payout_cents");
        p.payout = rs.wasNull() ? null : payout;
        return p;
    }

    @Override
    public Position position(UUID userId, UUID marketId) {
        return positions.computeIfAbsent(userId + "/" + marketId, k -> jdbc.query(
                        "select * from trading.positions where user_id = ? and market_id = ?", JdbcState::mapPosition, userId, marketId)
                .stream().findFirst().orElseGet(() -> new Position(userId, marketId)));
    }

    @Override
    public List<Position> positions(UUID marketId) {
        for (Position p : jdbc.query("select * from trading.positions where market_id = ?", JdbcState::mapPosition, marketId)) {
            positions.putIfAbsent(p.userId + "/" + p.marketId, p);
        }
        return positions.values().stream().filter(p -> p.marketId.equals(marketId)).toList();
    }

    @Override
    public MarketState market(UUID marketId) {
        return markets.computeIfAbsent(marketId, id -> {
            List<MarketState> rows = jdbc.query("select * from trading.markets where id = ? for update", (rs, i) -> {
                MarketState m = new MarketState(id, Types.MarketStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("close_at").toInstant());
                int lp = rs.getInt("last_price");
                m.lastPrice = rs.wasNull() ? null : lp;
                m.volumeCents = rs.getLong("volume_cents");
                m.tradeCount = rs.getInt("trade_count");
                m.escrowCents = rs.getLong("escrow_cents");
                return m;
            }, id);
            if (rows.isEmpty()) throw new Exchange.Rejected("NOT_FOUND", "No such market.");
            return rows.getFirst();
        });
    }

    @Override public void saveOrder(OrderRec o) { orders.put(o.id, o); }
    @Override public void saveTrade(TradeRec t) { trades.add(t); }

    @Override
    public void post(String kind, List<Posting> postings) {
        long sum = postings.stream().mapToLong(Posting::amountCents).sum();
        if (sum != 0) throw new IllegalStateException("unbalanced ledger transaction " + kind + ": " + postings);
        UUID tx = UUID.randomUUID();
        for (Posting p : postings) {
            ledger.add(new Object[] {tx, kind, p.account(), p.userId(), p.marketId(), p.amountCents()});
        }
    }

    @Override
    public void emit(String type, String key, Object payload) {
        outbox.add(type, key, payload);
    }

    /** Writes every change. Call once at the end of the command, inside the transaction. */
    void flush() {
        for (Wallet w : wallets.values()) {
            if (!w.dirty) continue;
            jdbc.update("update trading.wallets set available_cents = ?, reserved_cents = ? where user_id = ?",
                    w.available, w.reserved, w.userId);
            w.dirty = false;
        }
        for (OrderRec o : orders.values()) {
            if (o.isNew) {
                jdbc.update("insert into trading.orders (id, seq, market_id, user_id, username, side, action, price, yes_price,"
                                + " book_side, shares, filled_shares, status, reserved_cents, filled_cost_cents, idempotency_key,"
                                + " created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        o.id, o.seq, o.marketId, o.userId, o.username, o.side.name(), o.action.name(), o.price, o.yesPrice,
                        o.bookSide.name(), o.shares, o.filled, o.status.name(), o.reservedCents, o.filledCostCents,
                        o.idempotencyKey, Timestamp.from(o.createdAt), Timestamp.from(o.updatedAt));
                o.isNew = false;
            } else if (o.dirty) {
                jdbc.update("update trading.orders set filled_shares = ?, status = ?, reserved_cents = ?, filled_cost_cents = ?,"
                                + " updated_at = ? where id = ?",
                        o.filled, o.status.name(), o.reservedCents, o.filledCostCents, Timestamp.from(o.updatedAt), o.id);
            }
            o.dirty = false;
        }
        Set<UUID> traders = new LinkedHashSet<>();
        for (TradeRec t : trades) {
            jdbc.update("insert into trading.trades (id, market_id, price, shares, kind, bid_order_id, ask_order_id, bid_user_id,"
                            + " ask_user_id, taker_order_id, taker_user_id, taker_username, taker_side, taker_action, taker_price,"
                            + " maker_username, executed_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    t.id(), t.marketId(), t.price(), t.shares(), t.kind().name(), t.bidOrderId(), t.askOrderId(), t.bidUserId(),
                    t.askUserId(), t.takerOrderId(), t.takerUserId(), t.takerUsername(), t.takerSide().name(),
                    t.takerAction().name(), t.takerPrice(), t.makerUsername(), Timestamp.from(t.executedAt()));
            traders.add(t.bidUserId());
            traders.add(t.askUserId());
        }
        for (Position p : positions.values()) {
            if (!p.dirty) continue;
            jdbc.update("insert into trading.positions (user_id, market_id, yes_shares, no_shares, yes_reserved, no_reserved,"
                            + " yes_cost_cents, no_cost_cents, payout_cents) values (?,?,?,?,?,?,?,?,?)"
                            + " on conflict (user_id, market_id) do update set yes_shares = excluded.yes_shares,"
                            + " no_shares = excluded.no_shares, yes_reserved = excluded.yes_reserved, no_reserved = excluded.no_reserved,"
                            + " yes_cost_cents = excluded.yes_cost_cents, no_cost_cents = excluded.no_cost_cents,"
                            + " payout_cents = excluded.payout_cents",
                    p.userId, p.marketId, p.yes, p.no, p.yesReserved, p.noReserved, p.yesCost, p.noCost, p.payout);
            p.dirty = false;
        }
        for (MarketState m : markets.values()) {
            for (UUID u : traders) {
                jdbc.update("insert into trading.market_traders (market_id, user_id) values (?, ?) on conflict do nothing", m.id, u);
            }
            if (!m.dirty) continue;
            jdbc.update("update trading.markets set status = ?, last_price = ?, volume_cents = ?, trade_count = ?, escrow_cents = ?,"
                            + " outcome = coalesce(?, outcome), resolved_at = coalesce(?, resolved_at),"
                            + " trader_count = (select count(*) from trading.market_traders where market_id = ?) where id = ?",
                    m.status.name(), m.lastPrice, m.volumeCents, m.tradeCount, m.escrowCents,
                    m.outcome == null ? null : m.outcome.name(), m.resolvedAt == null ? null : Timestamp.from(m.resolvedAt),
                    m.id, m.id);
            m.dirty = false;
        }
        if (!ledger.isEmpty()) {
            jdbc.batchUpdate("insert into trading.ledger_entries (tx_id, kind, account, user_id, market_id, amount_cents)"
                    + " values (?, ?, ?, ?, ?, ?)", ledger);
            ledger.clear();
        }
        trades.clear();
    }
}
