package dev.hivebets.trading.domain;

import java.time.Instant;
import java.util.UUID;

public class MarketState {
    public final UUID id;
    public Types.MarketStatus status;
    public Instant closeAt;
    public Integer lastPrice;
    public long volumeCents;
    public int tradeCount;
    public long escrowCents;
    public Types.Side outcome;
    public Instant resolvedAt;
    public boolean dirty;

    public MarketState(UUID id, Types.MarketStatus status, Instant closeAt) {
        this.id = id;
        this.status = status;
        this.closeAt = closeAt;
    }
}
