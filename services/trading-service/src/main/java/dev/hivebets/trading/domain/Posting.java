package dev.hivebets.trading.domain;

import java.util.UUID;

/** One leg of a double-entry ledger transaction. The legs of a transaction sum to zero. */
public record Posting(String account, UUID userId, UUID marketId, long amountCents) {
    public static final String BANKROLL = "system:bankroll";

    public static Posting available(UUID user, long amount) { return new Posting("user:available", user, null, amount); }
    public static Posting reserved(UUID user, long amount) { return new Posting("user:reserved", user, null, amount); }
    public static Posting escrow(UUID market, long amount) { return new Posting("market:escrow", null, market, amount); }
    public static Posting bankroll(long amount) { return new Posting(BANKROLL, null, null, amount); }
}
