package dev.hivebets.trading.domain;

import java.time.Instant;
import java.util.UUID;

/** An order. While OPEN with shares left it also sits in the in-memory {@link OrderBook}. */
public class OrderRec {
    public final UUID id;
    public final UUID marketId;
    public final UUID userId;
    public final String username;
    public final Types.Side side;
    public final Types.Action action;
    public final int price;      // in the order's own side terms
    public final int yesPrice;   // in YES terms
    public final Types.BookSide bookSide;
    public final int shares;
    public int filled;
    public Types.OrderStatus status = Types.OrderStatus.OPEN;
    public long reservedCents;    // cash still reserved (buy orders)
    public long filledCostCents;  // cash paid (buy) or received (sell), own terms
    public long seq;              // price-time priority
    public final String idempotencyKey;
    public final Instant createdAt;
    public Instant updatedAt;
    public boolean isNew, dirty;

    public OrderRec(UUID id, UUID marketId, UUID userId, String username, Types.Side side, Types.Action action,
                    int price, int shares, String idempotencyKey, Instant createdAt) {
        this.id = id;
        this.marketId = marketId;
        this.userId = userId;
        this.username = username;
        this.side = side;
        this.action = action;
        this.price = price;
        this.yesPrice = Types.yesPrice(side, price);
        this.bookSide = Types.bookSide(side, action);
        this.shares = shares;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public int remaining() { return shares - filled; }
    public boolean isBuy() { return action == Types.Action.BUY; }
}
