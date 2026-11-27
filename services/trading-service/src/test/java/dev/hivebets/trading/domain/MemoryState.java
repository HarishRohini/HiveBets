package dev.hivebets.trading.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-memory ExchangeState that also keeps the full ledger, for tests. */
class MemoryState implements ExchangeState {
    final Map<UUID, Wallet> wallets = new HashMap<>();
    final Map<String, Position> positions = new HashMap<>();
    final Map<UUID, MarketState> markets = new HashMap<>();
    final Map<String, Long> ledger = new HashMap<>();
    final List<TradeRec> trades = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    @Override public Wallet wallet(UUID userId, String username, String accountType) {
        return wallets.computeIfAbsent(userId, id -> {
            post("grant", List.of(Posting.bankroll(-Exchange.STARTING_CENTS), Posting.available(id, Exchange.STARTING_CENTS)));
            return new Wallet(id, username, accountType, Exchange.STARTING_CENTS, 0);
        });
    }

    @Override public void lockWallets(Collection<UUID> userIds) {
        for (UUID u : userIds) if (!wallets.containsKey(u)) throw new IllegalStateException("unknown wallet " + u);
    }

    @Override public Wallet wallet(UUID userId) {
        Wallet w = wallets.get(userId);
        if (w == null) throw new IllegalStateException("wallet not loaded " + userId);
        return w;
    }

    @Override public Position position(UUID userId, UUID marketId) {
        return positions.computeIfAbsent(userId + "/" + marketId, k -> new Position(userId, marketId));
    }

    @Override public List<Position> positions(UUID marketId) {
        return positions.values().stream().filter(p -> p.marketId.equals(marketId)).toList();
    }

    @Override public MarketState market(UUID marketId) { return markets.get(marketId); }
    @Override public void saveOrder(OrderRec o) {}
    @Override public void saveTrade(TradeRec t) { trades.add(t); }

    @Override public void post(String kind, List<Posting> postings) {
        long sum = postings.stream().mapToLong(Posting::amountCents).sum();
        if (sum != 0) throw new AssertionError("unbalanced " + kind + " " + postings);
        for (Posting p : postings) {
            String key = p.account() + ":" + (p.userId() != null ? p.userId() : p.marketId() != null ? p.marketId() : "");
            ledger.merge(key, p.amountCents(), Long::sum);
        }
    }

    @Override public void emit(String type, String key, Object payload) { events.add(type); }

    long ledger(String account, UUID id) { return ledger.getOrDefault(account + ":" + id, 0L); }
}
