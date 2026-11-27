package dev.hivebets.trading.domain;

import static dev.hivebets.trading.domain.Types.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Money and shares never appear or vanish, whatever sequence of orders, cancels and resolution:
 * - the ledger balances (every account sums to zero overall) and matches the wallets,
 * - each market's escrow is 100 cents per outstanding YES share = per outstanding NO share,
 * - no balance, holding or reservation goes negative, and the book stays uncrossed,
 * - after resolution all cash is back with traders and total cash equals total grants.
 */
class ConservationPropertyTest {

    record Op(int kind, int user, int side, int action, int price, int shares, int pick) {}

    @Provide
    Arbitrary<List<Op>> ops() {
        Arbitrary<Op> op = Combinators.combine(
                Arbitraries.integers().between(0, 9),   // 0-7 place, 8-9 cancel
                Arbitraries.integers().between(0, 4),
                Arbitraries.integers().between(0, 1),
                Arbitraries.integers().between(0, 1),
                Arbitraries.integers().between(1, 99),
                Arbitraries.integers().between(1, 400),
                Arbitraries.integers().between(0, 1000)).as(Op::new);
        return op.list().ofMinSize(1).ofMaxSize(300);
    }

    @Property(tries = 300)
    void conserved(@ForAll("ops") List<Op> ops, @ForAll boolean outcomeYes) {
        MemoryState s = new MemoryState();
        OrderBook book = new OrderBook();
        Exchange ex = new Exchange(Clock.systemUTC(), UUID::randomUUID);
        UUID market = UUID.randomUUID();
        s.markets.put(market, new MarketState(market, MarketStatus.OPEN, Instant.now().plusSeconds(3600)));
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < 5; i++) users.add(UUID.randomUUID());
        long seq = 0;

        for (Op op : ops) {
            UUID u = users.get(op.user());
            if (op.kind() < 8) {
                // Bias sells towards something the trader holds, so transfers and burns happen.
                Side side = op.side() == 0 ? Side.YES : Side.NO;
                Action action = op.action() == 0 ? Action.BUY : Action.SELL;
                int shares = op.shares();
                if (action == Action.SELL) {
                    Position p = s.position(u, market);
                    long free = p.shares(side) - p.reservedShares(side);
                    if (free > 0) shares = (int) Math.max(1, Math.min(shares, free));
                }
                try {
                    ex.place(s, book, new Exchange.PlaceCommand(market, u, "u" + op.user(), "HUMAN",
                            side, action, op.price(), shares, null), ++seq);
                } catch (Exchange.Rejected expected) {
                    // insufficient funds/shares are fine; state must be untouched (checked below)
                }
            } else {
                List<OrderRec> open = book.all();
                if (!open.isEmpty()) {
                    OrderRec o = open.get(op.pick() % open.size());
                    ex.cancel(s, book, o.id, o.userId);
                }
            }
            checkInvariants(s, book, market, users);
        }

        ex.resolve(s, book, market, outcomeYes ? Side.YES : Side.NO);
        checkInvariants(s, book, market, users);
        long cash = s.wallets.values().stream().mapToLong(w -> w.available + w.reserved).sum();
        assertEquals(Exchange.STARTING_CENTS * s.wallets.size(), cash);
        assertTrue(s.wallets.values().stream().allMatch(w -> w.reserved == 0));
        assertEquals(0, s.market(market).escrowCents);
    }

    private void checkInvariants(MemoryState s, OrderBook book, UUID market, List<UUID> users) {
        long total = s.ledger.values().stream().mapToLong(Long::longValue).sum();
        assertEquals(0, total, "ledger does not balance");
        long yes = 0, no = 0;
        for (Wallet w : s.wallets.values()) {
            assertTrue(w.available >= 0 && w.reserved >= 0, "negative wallet");
            assertEquals(w.available, s.ledger("user:available", w.userId), "available differs from ledger");
            assertEquals(w.reserved, s.ledger("user:reserved", w.userId), "reserved differs from ledger");
            long openBuyReserve = book.all().stream().filter(o -> o.userId.equals(w.userId) && o.isBuy())
                    .mapToLong(o -> o.reservedCents).sum();
            assertEquals(openBuyReserve, w.reserved, "reserved cash differs from open buy orders");
        }
        for (Position p : s.positions(market)) {
            assertTrue(p.yes >= 0 && p.no >= 0 && p.yesReserved >= 0 && p.noReserved >= 0, "negative position");
            assertTrue(p.yesReserved <= p.yes && p.noReserved <= p.no, "reserved more shares than held");
            yes += p.yes;
            no += p.no;
        }
        MarketState m = s.market(market);
        if (m.status != MarketStatus.RESOLVED) {
            assertEquals(yes, no, "YES and NO supply differ");
            assertEquals(100 * yes, m.escrowCents, "escrow does not cover outstanding shares");
            assertEquals(m.escrowCents, s.ledger("market:escrow", market));
        }
        assertTrue(book.consistent(), "book inconsistent or crossed");
    }
}
