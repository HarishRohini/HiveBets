package dev.hivebets.trading.domain;

import static dev.hivebets.trading.domain.Types.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExchangeTest {
    MemoryState s;
    OrderBook book;
    Exchange ex;
    UUID market = UUID.randomUUID();
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
    AtomicLong seq = new AtomicLong();

    @BeforeEach
    void setUp() {
        s = new MemoryState();
        book = new OrderBook();
        ex = new Exchange(Clock.systemUTC(), UUID::randomUUID);
        s.markets.put(market, new MarketState(market, MarketStatus.OPEN, Instant.now().plusSeconds(600)));
    }

    Exchange.PlaceResult place(UUID u, Side side, Action action, int price, int shares) {
        return ex.place(s, book, new Exchange.PlaceCommand(market, u, u.toString().substring(0, 6), "HUMAN",
                side, action, price, shares, null), seq.incrementAndGet());
    }

    @Test
    void yesBuyerMeetsNoBuyerMintsShares() {
        place(alice, Side.YES, Action.BUY, 60, 10);           // bid 60
        var r = place(bob, Side.NO, Action.BUY, 40, 10);        // ask 60 → crosses
        assertEquals(1, r.trades().size());
        TradeRec t = r.trades().getFirst();
        assertEquals(TradeKind.MINT, t.kind());
        assertEquals(60, t.price());
        assertEquals(40, t.takerPrice());
        assertEquals(100_000 - 600, s.wallet(alice).available);
        assertEquals(100_000 - 400, s.wallet(bob).available);
        assertEquals(1000, s.market(market).escrowCents);
        assertEquals(10, s.position(alice, market).yes);
        assertEquals(10, s.position(bob, market).no);
        assertTrue(book.consistent());
        assertEquals(0, book.size());
    }

    @Test
    void takerGetsPriceImprovement() {
        place(alice, Side.YES, Action.BUY, 55, 10);            // resting bid 55
        place(bob, Side.NO, Action.BUY, 60, 10);               // ask at YES 40, trades at maker's 55 → bob pays 45
        assertEquals(100_000 - 450, s.wallet(bob).available);
        assertEquals(0, s.wallet(bob).reserved);
        assertEquals(100_000 - 550, s.wallet(alice).available);
    }

    @Test
    void partialFillRestsAndCancelReleases() {
        place(alice, Side.YES, Action.BUY, 50, 10);
        var r = place(bob, Side.NO, Action.BUY, 50, 4);
        assertEquals(4, r.trades().getFirst().shares());
        OrderRec rest = book.all().getFirst();
        assertEquals(6, rest.remaining());
        assertEquals(300, s.wallet(alice).reserved);
        ex.cancel(s, book, rest.id, alice);
        assertEquals(0, s.wallet(alice).reserved);
        assertEquals(100_000 - 200, s.wallet(alice).available);
        assertEquals(OrderStatus.CANCELLED, rest.status);
    }

    @Test
    void sellingNeedsShares() {
        var e = assertThrows(Exchange.Rejected.class, () -> place(alice, Side.YES, Action.SELL, 50, 1));
        assertEquals("INSUFFICIENT_SHARES", e.code);
    }

    @Test
    void buyingNeedsCash() {
        var e = assertThrows(Exchange.Rejected.class, () -> place(alice, Side.YES, Action.BUY, 99, 10_000));
        assertEquals("INSUFFICIENT_FUNDS", e.code);
    }

    @Test
    void transferAndBurn() {
        place(alice, Side.YES, Action.BUY, 60, 10);
        place(bob, Side.NO, Action.BUY, 40, 10);               // mint: alice 10 YES, bob 10 NO
        place(alice, Side.YES, Action.SELL, 70, 5);            // ask 70
        var r = place(carol, Side.YES, Action.BUY, 75, 5);       // bid 75 crosses ask 70
        assertEquals(TradeKind.TRANSFER_YES, r.trades().getFirst().kind());
        assertEquals(70, r.trades().getFirst().price());
        assertEquals(100_000 - 600 + 350, s.wallet(alice).available);
        assertEquals(100_000 - 350, s.wallet(carol).available);
        // bob sells NO at 25 (bid YES 75); carol sells YES at 70 (ask 70) → burn
        place(bob, Side.NO, Action.SELL, 25, 5);
        var b = place(carol, Side.YES, Action.SELL, 70, 5);
        assertEquals(TradeKind.BURN, b.trades().getFirst().kind());
        assertEquals(500, s.market(market).escrowCents);     // 5 YES (alice) and 5 NO (bob) left
    }

    @Test
    void earlierOrderAtSamePriceFillsFirst() {
        place(alice, Side.YES, Action.BUY, 50, 5);
        place(carol, Side.YES, Action.BUY, 50, 5);
        var r = place(bob, Side.NO, Action.BUY, 50, 5);
        assertEquals(alice, r.trades().getFirst().bidUserId());
    }

    @Test
    void selfTradeCancelsRestingOrder() {
        place(alice, Side.YES, Action.BUY, 50, 5);
        var r = place(alice, Side.NO, Action.BUY, 50, 5);
        assertTrue(r.trades().isEmpty());
        assertEquals(1, book.size());
        assertEquals(250, s.wallet(alice).reserved);
    }

    @Test
    void resolvePaysWinnersAndEmptiesEscrow() {
        place(alice, Side.YES, Action.BUY, 60, 10);
        place(bob, Side.NO, Action.BUY, 40, 10);
        place(carol, Side.YES, Action.BUY, 30, 5);             // left resting
        var res = ex.resolve(s, book, market, Side.YES);
        assertEquals(1000L, res.payouts().get(alice));
        assertEquals(100_000 + 400, s.wallet(alice).available);
        assertEquals(100_000 - 400, s.wallet(bob).available);
        assertEquals(100_000, s.wallet(carol).available);
        assertEquals(0, s.market(market).escrowCents);
        assertEquals(0, book.size());
        assertThrows(Exchange.Rejected.class, () -> place(carol, Side.YES, Action.BUY, 30, 5));
    }

    @Test
    void closedMarketRejectsOrders() {
        s.market(market).closeAt = Instant.now().minusSeconds(1);
        var e = assertThrows(Exchange.Rejected.class, () -> place(alice, Side.YES, Action.BUY, 50, 1));
        assertEquals("MARKET_CLOSED", e.code);
    }
}
