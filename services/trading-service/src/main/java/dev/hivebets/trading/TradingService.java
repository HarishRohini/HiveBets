package dev.hivebets.trading;

import dev.hivebets.common.events.EventTypes;
import dev.hivebets.common.outbox.OutboxWriter;
import dev.hivebets.common.web.ApiException;
import dev.hivebets.common.web.CurrentUser;
import dev.hivebets.trading.domain.Exchange;
import dev.hivebets.trading.domain.OrderBook;
import dev.hivebets.trading.domain.OrderRec;
import dev.hivebets.trading.domain.Types;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs every command for a market on that market's own single thread (so matching needs no locks),
 * each inside one Postgres transaction that writes orders, trades, ledger, positions and outbox rows.
 * If a transaction fails the market's in-memory book is rebuilt from the open orders in Postgres.
 */
@Service
public class TradingService {
    private static final Logger log = LoggerFactory.getLogger(TradingService.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OutboxWriter outbox;
    private final Views views;
    private final Exchange exchange;
    private final Map<UUID, Worker> workers = new ConcurrentHashMap<>();
    private final Timer orderTimer;
    private final MeterRegistry meters;

    private static final class Worker {
        final OrderBook book = new OrderBook();
        final ExecutorService thread;
        Worker(UUID id) {
            thread = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "market-" + id.toString().substring(0, 8));
                t.setDaemon(true);
                return t;
            });
        }
    }

    public TradingService(JdbcTemplate jdbc, TransactionTemplate tx, OutboxWriter outbox, Views views, Clock clock,
                          MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.views = views;
        this.exchange = new Exchange(clock, UUID::randomUUID);
        this.meters = meters;
        this.orderTimer = Timer.builder("hivebets.order.latency").description("Order placement: match and commit")
                .publishPercentileHistogram().register(meters);
    }

    /** Rebuild every live market's book from its open orders (time priority = seq). */
    @EventListener(ApplicationReadyEvent.class)
    public void rebuildBooks() {
        List<UUID> live = jdbc.queryForList("select id from trading.markets where status <> 'RESOLVED'", UUID.class);
        for (UUID id : live) {
            Worker w = worker(id);
            call(id, () -> { reload(id, w.book); return null; });
        }
        log.info("rebuilt {} order books", live.size());
    }

    private void reload(UUID marketId, OrderBook book) {
        book.clear();
        jdbc.query("select * from trading.orders where market_id = ? and status = 'OPEN' order by seq", rs -> {
            OrderRec o = new OrderRec(rs.getObject("id", UUID.class), marketId, rs.getObject("user_id", UUID.class),
                    rs.getString("username"), Types.Side.valueOf(rs.getString("side")), Types.Action.valueOf(rs.getString("action")),
                    rs.getInt("price"), rs.getInt("shares"), rs.getString("idempotency_key"), rs.getTimestamp("created_at").toInstant());
            o.filled = rs.getInt("filled_shares");
            o.reservedCents = rs.getLong("reserved_cents");
            o.filledCostCents = rs.getLong("filled_cost_cents");
            o.seq = rs.getLong("seq");
            book.add(o);
        }, marketId);
    }

    private Worker worker(UUID marketId) {
        return workers.computeIfAbsent(marketId, Worker::new);
    }

    /** Runs a transactional command on the market thread; on failure rolls back and rebuilds the book. */
    private <T> T inMarket(UUID marketId, java.util.function.BiFunction<JdbcState, OrderBook, T> command) {
        Worker w = worker(marketId);
        return call(marketId, () -> {
            for (int attempt = 1; ; attempt++) {
                try {
                    return tx.execute(status -> {
                        JdbcState state = new JdbcState(jdbc, outbox);
                        T result = command.apply(state, w.book);
                        state.flush();
                        return result;
                    });
                } catch (RuntimeException e) {
                    reload(marketId, w.book);
                    if (e instanceof PessimisticLockingFailureException && attempt < 3) {
                        log.warn("lock conflict on market {}, retrying ({})", marketId, attempt);
                        continue;
                    }
                    throw e;
                }
            }
        });
    }

    private <T> T call(UUID marketId, Callable<T> c) {
        try {
            return worker(marketId).thread.submit(c).get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        } catch (TimeoutException e) {
            throw new ApiException(503, "BUSY", "The market is busy, try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public record PlaceRequest(UUID marketId, Types.Side side, Types.Action action, Integer price, Integer shares) {}
    public record Placed(Views.Order order, boolean replay) {}

    public Placed place(CurrentUser user, PlaceRequest req, String idempotencyKey) {
        if (req.marketId() == null || req.side() == null || req.action() == null || req.price() == null || req.shares() == null) {
            throw ApiException.validation("marketId, side, action, price and shares are required.");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 100) throw ApiException.validation("Idempotency-Key is too long.");
        if (key != null) {
            Views.Order existing = views.orderByKey(user.id(), key);
            if (existing != null) return new Placed(existing, true);
        }
        if (views.market(req.marketId()) == null) throw ApiException.notFound("No such market.");
        Timer.Sample sample = Timer.start(meters);
        String result = "accepted";
        try {
            Exchange.PlaceResult r = inMarket(req.marketId(), (state, book) -> {
                long seq = jdbc.queryForObject("select nextval('trading.order_seq')", Long.class);
                Exchange.PlaceResult res = exchange.place(state, book, new Exchange.PlaceCommand(req.marketId(), user.id(),
                        user.username(), user.accountType(), req.side(), req.action(), req.price(), req.shares(), key), seq);
                state.flush();
                walletEvents(res.touchedUsers());
                return res;
            });
            Counter.builder("hivebets.trades").register(meters).increment(r.trades().size());
            return new Placed(views.order(r.order().id), false);
        } catch (Exchange.Rejected e) {
            result = e.code.toLowerCase();
            throw toApi(e);
        } catch (DuplicateKeyException e) {
            result = "duplicate";
            Views.Order existing = key == null ? null : views.orderByKey(user.id(), key);
            if (existing != null) return new Placed(existing, true);
            throw e;
        } finally {
            sample.stop(orderTimer);
            Counter.builder("hivebets.orders").tag("result", result).register(meters).increment();
        }
    }

    public Views.Order cancel(CurrentUser user, UUID orderId) {
        Views.Order o = views.order(orderId);
        if (o == null || !userOwns(user, orderId)) throw ApiException.notFound("No such order.");
        if (!"OPEN".equals(o.status())) throw ApiException.conflict("CONFLICT", "This order is no longer open.");
        try {
            inMarket(o.marketId(), (state, book) -> {
                OrderRec rec = exchange.cancel(state, book, orderId, user.id());
                if (rec == null) throw new Exchange.Rejected("CONFLICT", "This order is no longer open.");
                state.flush();
                walletEvents(List.of(user.id()));
                return rec;
            });
        } catch (Exchange.Rejected e) {
            throw toApi(e);
        }
        return views.order(orderId);
    }

    private boolean userOwns(CurrentUser user, UUID orderId) {
        Integer n = jdbc.queryForObject("select count(*) from trading.orders where id = ? and user_id = ?", Integer.class,
                orderId, user.id());
        return n != null && n > 0;
    }

    public record CreateMarket(String question, String yesStatement, String noStatement, String kind, Instant closeAt,
                               Integer durationMinutes) {}

    public Views.Market create(CurrentUser user, CreateMarket req, String slug) {
        if (req.question() == null || req.question().isBlank() || req.question().length() > 300) {
            throw ApiException.validation("A question of up to 300 characters is required.");
        }
        Instant now = Instant.now();
        Instant close = req.closeAt() != null ? req.closeAt()
                : now.plus(Duration.ofMinutes(req.durationMinutes() == null ? 10 : req.durationMinutes()));
        if (!close.isAfter(now.plusSeconds(30))) throw ApiException.validation("The close time must be at least 30 seconds away.");
        String kind = req.kind() == null ? "SIMULATED" : req.kind().toUpperCase();
        if (!kind.equals("SIMULATED") && !kind.equals("REAL")) throw ApiException.validation("kind is SIMULATED or REAL.");
        String q = req.question().trim();
        String yes = blankTo(req.yesStatement(), "Yes: " + q);
        String no = blankTo(req.noStatement(), "No: " + q);
        UUID id = UUID.randomUUID();
        Boolean inserted = tx.execute(status -> {
            int n = jdbc.update("insert into trading.markets (id, slug, question, yes_statement, no_statement, kind, status, close_at,"
                            + " created_at, created_by) values (?,?,?,?,?,?,'OPEN',?,?,?) on conflict (slug) do nothing",
                    id, slug, q, yes, no, kind, Timestamp.from(close), Timestamp.from(now), user == null ? null : user.id());
            if (n == 1) outbox.add(EventTypes.MARKET_CREATED, id.toString(), views.market(id));
            return n == 1;
        });
        if (!Boolean.TRUE.equals(inserted)) return null;
        worker(id);
        return views.market(id);
    }

    public Views.Market resolve(UUID marketId, Types.Side outcome) {
        if (views.market(marketId) == null) throw ApiException.notFound("No such market.");
        if (outcome == null) throw ApiException.validation("outcome is YES or NO.");
        try {
            inMarket(marketId, (state, book) -> {
                Exchange.ResolveResult r = exchange.resolve(state, book, marketId, outcome);
                state.flush();
                walletEvents(r.touchedUsers());
                return r;
            });
        } catch (Exchange.Rejected e) {
            throw toApi(e);
        }
        Worker w = workers.remove(marketId);
        if (w != null) w.thread.shutdown();
        return views.market(marketId);
    }

    public Map<String, Object> book(UUID marketId) {
        Worker w = workers.get(marketId);
        if (w == null) return Exchange.bookSnapshot(marketId, new OrderBook());
        return call(marketId, () -> Exchange.bookSnapshot(marketId, w.book));
    }

    /** Stops trading at the close time on this service's own clock, even if nobody resolves the market yet. */
    @Scheduled(fixedDelay = 1000)
    public void closeExpired() {
        List<UUID> due = jdbc.queryForList("select id from trading.markets where status = 'OPEN' and close_at <= now()", UUID.class);
        for (UUID id : due) {
            try {
                inMarket(id, (state, book) -> {
                    var m = state.market(id);
                    if (m.status != Types.MarketStatus.OPEN) return null;
                    m.status = Types.MarketStatus.CLOSED;
                    m.dirty = true;
                    outbox.add(EventTypes.MARKET_CLOSED, id.toString(), Map.of("marketId", id, "closedAt", Instant.now()));
                    return null;
                });
                log.info("market {} closed for trading", id);
            } catch (RuntimeException e) {
                log.warn("could not close market {}: {}", id, e.toString());
            }
        }
    }

    private void walletEvents(Collection<UUID> users) {
        for (UUID u : users) {
            Map<String, Object> w = views.walletSummary(u);
            if (w != null) outbox.add(EventTypes.WALLET_CHANGED, u.toString(), w);
        }
    }

    private static ApiException toApi(Exchange.Rejected e) {
        int status = switch (e.code) {
            case "VALIDATION" -> 400;
            case "FORBIDDEN" -> 403;
            case "NOT_FOUND" -> 404;
            default -> 409;
        };
        return new ApiException(status, e.code, e.getMessage());
    }

    private static String blankTo(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s.trim();
    }

    @PreDestroy
    void stop() {
        workers.values().forEach(w -> w.thread.shutdown());
    }
}
