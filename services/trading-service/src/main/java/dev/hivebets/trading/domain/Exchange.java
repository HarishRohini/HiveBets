package dev.hivebets.trading.domain;

import static dev.hivebets.trading.domain.Types.*;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The trading rules. Every method runs on the market's single writer thread inside one storage
 * transaction ({@link ExchangeState}). Money and shares are conserved:
 * the sum of all ledger accounts is always zero and each market's escrow equals 100 cents per
 * outstanding YES share (which always equals the number of outstanding NO shares).
 */
public class Exchange {
    public static final long STARTING_CENTS = 100_000;
    public static final int MAX_SHARES = 10_000;

    private final Clock clock;
    private final Supplier<UUID> ids;

    public Exchange(Clock clock, Supplier<UUID> ids) {
        this.clock = clock;
        this.ids = ids;
    }

    public static class Rejected extends RuntimeException {
        public final String code;
        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public record PlaceCommand(UUID marketId, UUID userId, String username, String accountType,
                               Side side, Action action, int price, int shares, String idempotencyKey) {}

    public record PlaceResult(OrderRec order, List<TradeRec> trades, Set<UUID> touchedUsers) {}

    public PlaceResult place(ExchangeState s, OrderBook book, PlaceCommand c, long seq) {
        if (c.price() < 1 || c.price() > 99) throw new Rejected("VALIDATION", "Price must be between 1 and 99 cents.");
        if (c.shares() < 1 || c.shares() > MAX_SHARES) throw new Rejected("VALIDATION", "Shares must be between 1 and " + MAX_SHARES + ".");
        MarketState m = s.market(c.marketId());
        Instant now = clock.instant();
        if (m.status != MarketStatus.OPEN || !now.isBefore(m.closeAt)) {
            throw new Rejected("MARKET_CLOSED", "This market is closed for trading.");
        }

        Wallet w = s.wallet(c.userId(), c.username(), c.accountType());
        Position pos = s.position(c.userId(), c.marketId());
        OrderRec o = new OrderRec(ids.get(), c.marketId(), c.userId(), c.username(), c.side(), c.action(),
                c.price(), c.shares(), c.idempotencyKey(), now);
        o.seq = seq;
        o.isNew = true;

        // Reserve what the order could cost: cash for a buy, shares for a sell.
        if (o.isBuy()) {
            long need = (long) o.price * o.shares;
            if (w.available < need) throw new Rejected("INSUFFICIENT_FUNDS", "Not enough available cash for this order.");
            w.available -= need;
            w.reserved += need;
            w.dirty = true;
            o.reservedCents = need;
            s.post("order.reserve", List.of(Posting.available(w.userId, -need), Posting.reserved(w.userId, need)));
        } else {
            long free = pos.shares(o.side) - pos.reservedShares(o.side);
            if (free < o.shares) throw new Rejected("INSUFFICIENT_SHARES", "You do not hold enough " + o.side + " shares to sell.");
            pos.addReserved(o.side, o.shares);
        }
        s.saveOrder(o);
        s.emit("order.accepted", o.marketId.toString(), orderAccepted(o));

        // Match against the book.
        List<OrderRec> selfMatches = new ArrayList<>();
        List<OrderBook.Match> matches = book.crossing(o, selfMatches);
        Set<UUID> touched = new LinkedHashSet<>();
        touched.add(o.userId);
        for (OrderRec self : selfMatches) cancelResting(s, book, self, "SELF_TRADE");
        for (OrderBook.Match mt : matches) touched.add(mt.maker().userId);
        s.lockWallets(touched);

        List<TradeRec> trades = new ArrayList<>();
        for (OrderBook.Match mt : matches) {
            trades.add(settle(s, m, o, mt.maker(), mt.yesPrice(), mt.shares(), now));
            book.consume(mt.maker());
        }

        if (o.remaining() == 0) {
            o.status = OrderStatus.FILLED;
        } else {
            book.add(o);
        }
        o.updatedAt = now;
        o.dirty = true;
        s.saveOrder(o);
        if (!trades.isEmpty() || !selfMatches.isEmpty() || o.status == OrderStatus.OPEN) {
            s.emit("book.changed", m.id.toString(), bookSnapshot(m.id, book));
        }
        return new PlaceResult(o, trades, touched);
    }

    /** Settles one fill between the taker and a resting order at the maker's YES price. */
    private TradeRec settle(ExchangeState s, MarketState m, OrderRec taker, OrderRec maker, int p, int n, Instant now) {
        OrderRec bid = taker.bookSide == BookSide.BID ? taker : maker;
        OrderRec ask = taker.bookSide == BookSide.BID ? maker : taker;
        TradeKind kind;
        if (bid.side == Side.YES && ask.side == Side.YES) kind = TradeKind.TRANSFER_YES;
        else if (bid.side == Side.YES) kind = TradeKind.MINT;          // YES buyer meets NO buyer
        else if (ask.side == Side.YES) kind = TradeKind.BURN;          // NO seller meets YES seller
        else kind = TradeKind.TRANSFER_NO;

        List<Posting> legs = new ArrayList<>();
        fillSide(s, m, bid, p, n, legs);
        fillSide(s, m, ask, p, n, legs);
        long escrowDelta = switch (kind) {
            case MINT -> 100L * n;
            case BURN -> -100L * n;
            default -> 0;
        };
        if (escrowDelta != 0) {
            m.escrowCents += escrowDelta;
            legs.add(Posting.escrow(m.id, escrowDelta));
        }
        s.post("trade", legs);

        m.lastPrice = p;
        m.tradeCount++;
        TradeRec t = new TradeRec(ids.get(), m.id, p, n, kind, bid.id, ask.id, bid.userId, ask.userId,
                taker.id, taker.userId, taker.username, taker.side, taker.action, ownPrice(taker.side, p),
                maker.username, now);
        m.volumeCents += t.cashCents();
        m.dirty = true;
        maker.updatedAt = now;
        maker.dirty = true;
        s.saveOrder(maker);
        s.saveTrade(t);
        s.emit("trade.executed", m.id.toString(), t);
        return t;
    }

    /** Applies one side of a fill: cash and shares for that order's owner. */
    private void fillSide(ExchangeState s, MarketState m, OrderRec o, int yesPrice, int n, List<Posting> legs) {
        Wallet w = s.wallet(o.userId);
        Position pos = s.position(o.userId, m.id);
        int own = ownPrice(o.side, yesPrice);
        long cash = (long) own * n;
        if (o.isBuy()) {
            long reservedForFill = (long) o.price * n;  // what was set aside for these n shares
            long refund = reservedForFill - cash;        // price improvement goes back to available
            w.reserved -= reservedForFill;
            w.available += refund;
            o.reservedCents -= reservedForFill;
            legs.add(Posting.reserved(o.userId, -reservedForFill));
            if (refund != 0) legs.add(Posting.available(o.userId, refund));
            pos.buy(o.side, n, cash);
        } else {
            pos.sellReserved(o.side, n);
            w.available += cash;
            legs.add(Posting.available(o.userId, cash));
        }
        w.dirty = true;
        o.filled += n;
        o.filledCostCents += cash;
        if (o.remaining() == 0) o.status = OrderStatus.FILLED;
        // The cash a seller receives or a buyer pays that is not in this side's legs is
        // balanced by the other side's legs and the escrow leg (see settle).
    }

    public OrderRec cancel(ExchangeState s, OrderBook book, UUID orderId, UUID userId) {
        OrderRec o = book.get(orderId);
        if (o == null) return null;
        if (!o.userId.equals(userId)) throw new Rejected("FORBIDDEN", "That is not your order.");
        s.lockWallets(List.of(o.userId));
        cancelResting(s, book, o, "USER");
        s.emit("book.changed", o.marketId.toString(), bookSnapshot(o.marketId, book));
        return o;
    }

    private void cancelResting(ExchangeState s, OrderBook book, OrderRec o, String reason) {
        book.remove(o.id);
        release(s, o);
        o.status = OrderStatus.CANCELLED;
        o.updatedAt = clock.instant();
        o.dirty = true;
        s.saveOrder(o);
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("orderId", o.id);
        ev.put("marketId", o.marketId);
        ev.put("userId", o.userId);
        ev.put("remainingShares", o.remaining());
        ev.put("reason", reason);
        s.emit("order.cancelled", o.marketId.toString(), ev);
    }

    private void release(ExchangeState s, OrderRec o) {
        if (o.isBuy()) {
            Wallet w = s.wallet(o.userId);
            long r = o.reservedCents;
            if (r > 0) {
                w.reserved -= r;
                w.available += r;
                w.dirty = true;
                o.reservedCents = 0;
                s.post("order.release", List.of(Posting.reserved(o.userId, -r), Posting.available(o.userId, r)));
            }
        } else {
            s.position(o.userId, o.marketId).addReserved(o.side, -o.remaining());
        }
    }

    public record ResolveResult(Map<UUID, Long> payouts, Set<UUID> touchedUsers) {}

    /** Cancels what is left on the book, then pays 100 cents per winning share out of escrow. */
    public ResolveResult resolve(ExchangeState s, OrderBook book, UUID marketId, Side outcome) {
        MarketState m = s.market(marketId);
        if (m.status == MarketStatus.RESOLVED) throw new Rejected("CONFLICT", "This market is already resolved.");
        List<Position> positions = s.positions(marketId);
        Set<UUID> touched = new LinkedHashSet<>();
        for (OrderRec o : book.all()) touched.add(o.userId);
        for (Position p : positions) touched.add(p.userId);
        s.lockWallets(touched);

        List<OrderRec> open = book.all();
        open.sort((a, b) -> Long.compare(a.seq, b.seq));
        for (OrderRec o : open) cancelResting(s, book, o, "MARKET_RESOLVED");

        Map<UUID, Long> payouts = new LinkedHashMap<>();
        List<Posting> legs = new ArrayList<>();
        long paid = 0;
        for (Position p : positions) {
            long winning = outcome == Side.YES ? p.yes : p.no;
            long amount = 100L * winning;
            p.payout = amount;
            p.dirty = true;
            if (amount > 0) {
                Wallet w = s.wallet(p.userId);
                w.available += amount;
                w.dirty = true;
                legs.add(Posting.available(p.userId, amount));
                payouts.put(p.userId, amount);
                paid += amount;
            }
        }
        if (paid != m.escrowCents) {
            throw new IllegalStateException("escrow " + m.escrowCents + " does not match payouts " + paid);
        }
        if (paid > 0) {
            legs.add(Posting.escrow(m.id, -paid));
            s.post("market.payout", legs);
        }
        m.escrowCents = 0;
        m.status = MarketStatus.RESOLVED;
        m.outcome = outcome;
        m.resolvedAt = clock.instant();
        m.dirty = true;
        List<Map<String, Object>> list = new ArrayList<>();
        payouts.forEach((u, a) -> list.add(Map.of("userId", u, "payoutCents", a)));
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("marketId", m.id);
        ev.put("outcome", outcome);
        ev.put("resolvedAt", m.resolvedAt);
        ev.put("payouts", list);
        s.emit("market.resolved", m.id.toString(), ev);
        s.emit("book.changed", m.id.toString(), bookSnapshot(m.id, book));
        return new ResolveResult(payouts, touched);
    }

    public static Map<String, Object> bookSnapshot(UUID marketId, OrderBook book) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("marketId", marketId);
        m.put("bids", book.levels(BookSide.BID, 10));
        m.put("asks", book.levels(BookSide.ASK, 10));
        return m;
    }

    private static Map<String, Object> orderAccepted(OrderRec o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderId", o.id);
        m.put("marketId", o.marketId);
        m.put("userId", o.userId);
        m.put("side", o.side);
        m.put("action", o.action);
        m.put("price", o.price);
        m.put("shares", o.shares);
        m.put("yesPrice", o.yesPrice);
        m.put("bookSide", o.bookSide);
        return m;
    }
}
