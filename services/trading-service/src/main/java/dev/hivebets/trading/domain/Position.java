package dev.hivebets.trading.domain;

import java.util.UUID;

/** Shares one trader holds in one market. Reserved shares back open sell orders. Costs are average cost basis. */
public class Position {
    public final UUID userId;
    public final UUID marketId;
    public long yes, no, yesReserved, noReserved, yesCost, noCost;
    public Long payout;
    public boolean dirty;

    public Position(UUID userId, UUID marketId) {
        this.userId = userId;
        this.marketId = marketId;
    }

    public long shares(Types.Side s) { return s == Types.Side.YES ? yes : no; }
    public long reservedShares(Types.Side s) { return s == Types.Side.YES ? yesReserved : noReserved; }

    void addReserved(Types.Side s, long n) {
        if (s == Types.Side.YES) yesReserved += n; else noReserved += n;
        dirty = true;
    }

    void buy(Types.Side s, long n, long cost) {
        if (s == Types.Side.YES) { yes += n; yesCost += cost; } else { no += n; noCost += cost; }
        dirty = true;
    }

    /** Removes n reserved shares (sold), reducing cost basis proportionally. */
    void sellReserved(Types.Side s, long n) {
        if (s == Types.Side.YES) {
            yesCost -= yes == 0 ? 0 : yesCost * n / yes;
            yes -= n; yesReserved -= n;
        } else {
            noCost -= no == 0 ? 0 : noCost * n / no;
            no -= n; noReserved -= n;
        }
        dirty = true;
    }
}
