package dev.hivebets.trading;

import static dev.hivebets.trading.domain.Types.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hivebets.common.events.EventFactory;
import dev.hivebets.common.events.EventSchemas;
import dev.hivebets.trading.domain.Exchange;
import dev.hivebets.trading.domain.MarketState;
import dev.hivebets.trading.domain.OrderBook;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Everything trading-service publishes must match the shared event schemas (see common/events/schemas). */
class EventContractTest {

    @Test
    void exchangeEventsMatchTheirSchemas() {
        var s = new dev.hivebets.trading.domain.TestStates.Memory();
        OrderBook book = new OrderBook();
        Exchange ex = new Exchange(Clock.systemUTC(), UUID::randomUUID);
        UUID market = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        s.markets().put(market, new MarketState(market, MarketStatus.OPEN, Instant.now().plusSeconds(600)));
        long seq = 0;
        ex.place(s, book, new Exchange.PlaceCommand(market, a, "alpha", "HUMAN", Side.YES, Action.BUY, 60, 10, null), ++seq);
        ex.place(s, book, new Exchange.PlaceCommand(market, b, "bravo", "AGENT", Side.NO, Action.BUY, 40, 4, null), ++seq);
        var rest = book.all().getFirst();
        ex.cancel(s, book, rest.id, a);
        ex.place(s, book, new Exchange.PlaceCommand(market, a, "alpha", "HUMAN", Side.YES, Action.SELL, 70, 2, null), ++seq);
        ex.resolve(s, book, market, Side.NO);

        Set<String> seen = new HashSet<>();
        for (Object[] e : s.emitted()) {
            String type = (String) e[0];
            EventSchemas.assertValid(type, e[1]);
            seen.add(type);
            // and as it travels: wrapped in an envelope by the outbox
            EventSchemas.assertValidEnvelope(new EventFactory(EventSchemas.MAPPER, "trading-service").envelope(type, market.toString(), e[1]));
        }
        assertTrue(seen.containsAll(Set.of("order.accepted", "trade.executed", "book.changed", "order.cancelled", "market.resolved")), seen.toString());
    }

    @Test
    void marketCreatedMatchesSchema() {
        var m = new Views.Market(UUID.randomUUID(), "Will it rain?", "It rained.", "It stayed dry.", "SIMULATED", "OPEN",
                Instant.now().plusSeconds(600), Instant.now(), null, null, null, 0, 0, 0);
        EventSchemas.assertValid("market.created", m);
        var resolved = new Views.Market(UUID.randomUUID(), "Will it rain?", "It rained.", "It stayed dry.", "REAL", "RESOLVED",
                Instant.now(), Instant.now(), Instant.now(), "YES", 97, 12_345, 12, 4);
        EventSchemas.assertValid("market.created", resolved);
    }

    @Test
    void marketClosedMatchesSchema() {
        EventSchemas.assertValid("market.closed", Map.of("marketId", UUID.randomUUID(), "closedAt", Instant.now()));
    }

    @Test
    void walletChangedMatchesSchema() {
        EventSchemas.assertValid("wallet.changed", Views.walletPayload(UUID.randomUUID(), "alpha", "HUMAN", 99_000, 600, 400, 3, 1, 2));
    }
}
