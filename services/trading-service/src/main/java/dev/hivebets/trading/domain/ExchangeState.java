package dev.hivebets.trading.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * What the exchange logic needs from storage within one command. The production implementation
 * is a Postgres transaction; tests use an in-memory one.
 */
public interface ExchangeState {
    /** The trader's wallet, created with the starting bankroll if it does not exist yet. */
    Wallet wallet(UUID userId, String username, String accountType);

    /** Loads (and locks) existing wallets; call with every counterparty before settling. */
    void lockWallets(Collection<UUID> userIds);

    Wallet wallet(UUID userId);

    Position position(UUID userId, UUID marketId);

    /** Every position in a market (for resolution). */
    List<Position> positions(UUID marketId);

    MarketState market(UUID marketId);

    void saveOrder(OrderRec o);

    void saveTrade(TradeRec t);

    /** Records one balanced ledger transaction. */
    void post(String kind, List<Posting> postings);

    void emit(String type, String key, Object payload);
}
