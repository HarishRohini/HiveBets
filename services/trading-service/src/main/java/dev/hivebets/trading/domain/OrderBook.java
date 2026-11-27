package dev.hivebets.trading.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Central limit order book for one market in YES-price terms, price-time priority.
 * Not thread-safe: each market has a single writer thread.
 */
public class OrderBook {
    private final NavigableMap<Integer, ArrayDeque<OrderRec>> bids = new TreeMap<>(Comparator.reverseOrder());
    private final NavigableMap<Integer, ArrayDeque<OrderRec>> asks = new TreeMap<>();
    private final Map<UUID, OrderRec> byId = new HashMap<>();

    public record Match(OrderRec maker, int yesPrice, int shares) {}
    public record Level(int price, long shares) {}

    public void add(OrderRec o) {
        side(o.bookSide).computeIfAbsent(o.yesPrice, p -> new ArrayDeque<>()).addLast(o);
        byId.put(o.id, o);
    }

    public OrderRec remove(UUID orderId) {
        OrderRec o = byId.remove(orderId);
        if (o == null) return null;
        NavigableMap<Integer, ArrayDeque<OrderRec>> m = side(o.bookSide);
        ArrayDeque<OrderRec> q = m.get(o.yesPrice);
        if (q != null) {
            q.remove(o);
            if (q.isEmpty()) m.remove(o.yesPrice);
        }
        return o;
    }

    public OrderRec get(UUID id) { return byId.get(id); }
    public List<OrderRec> all() { return new ArrayList<>(byId.values()); }
    public int size() { return byId.size(); }

    /**
     * Finds resting orders that cross the taker, best price first, oldest first. Does not change
     * quantities; the caller settles each match and then calls {@link #consume}.
     * Resting orders from the taker's own account are returned in {@code selfMatches} so they can be cancelled.
     */
    public List<Match> crossing(OrderRec taker, List<OrderRec> selfMatches) {
        List<Match> out = new ArrayList<>();
        NavigableMap<Integer, ArrayDeque<OrderRec>> opposite =
                taker.bookSide == Types.BookSide.BID ? asks : bids;
        int left = taker.remaining();
        for (Map.Entry<Integer, ArrayDeque<OrderRec>> level : opposite.entrySet()) {
            int price = level.getKey();
            boolean crosses = taker.bookSide == Types.BookSide.BID ? price <= taker.yesPrice : price >= taker.yesPrice;
            if (!crosses || left == 0) break;
            for (OrderRec maker : level.getValue()) {
                if (left == 0) break;
                if (maker.userId.equals(taker.userId)) {
                    selfMatches.add(maker);
                    continue;
                }
                int n = Math.min(left, maker.remaining());
                out.add(new Match(maker, price, n));
                left -= n;
            }
        }
        return out;
    }

    /** Drops a resting order that is fully filled. */
    public void consume(OrderRec maker) {
        if (maker.remaining() == 0) remove(maker.id);
    }

    public Integer bestBid() { return bids.isEmpty() ? null : bids.firstKey(); }
    public Integer bestAsk() { return asks.isEmpty() ? null : asks.firstKey(); }

    public List<Level> levels(Types.BookSide s, int depth) {
        List<Level> out = new ArrayList<>();
        for (Map.Entry<Integer, ArrayDeque<OrderRec>> e : side(s).entrySet()) {
            if (out.size() == depth) break;
            long qty = 0;
            for (OrderRec o : e.getValue()) qty += o.remaining();
            out.add(new Level(e.getKey(), qty));
        }
        return Collections.unmodifiableList(out);
    }

    /** Rebuild support: orders must be added in seq order to keep time priority. */
    public void clear() {
        bids.clear();
        asks.clear();
        byId.clear();
    }

    /** Book-level sanity: every queued order is open, has shares left, and is indexed. */
    public boolean consistent() {
        int n = 0;
        for (var m : List.of(bids, asks)) {
            for (var q : m.values()) {
                if (q.isEmpty()) return false;
                for (Iterator<OrderRec> it = q.iterator(); it.hasNext(); ) {
                    OrderRec o = it.next();
                    if (o.remaining() <= 0 || o.status != Types.OrderStatus.OPEN || byId.get(o.id) != o) return false;
                    n++;
                }
            }
        }
        Integer b = bestBid(), a = bestAsk();
        return n == byId.size() && (b == null || a == null || b < a);
    }

    private NavigableMap<Integer, ArrayDeque<OrderRec>> side(Types.BookSide s) {
        return s == Types.BookSide.BID ? bids : asks;
    }
}
