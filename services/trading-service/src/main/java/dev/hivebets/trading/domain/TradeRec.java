package dev.hivebets.trading.domain;

import java.time.Instant;
import java.util.UUID;

/** One fill. {@code price} is in YES terms; {@code takerPrice} in the taker's own side terms. */
public record TradeRec(UUID id, UUID marketId, int price, int shares, Types.TradeKind kind,
                       UUID bidOrderId, UUID askOrderId, UUID bidUserId, UUID askUserId,
                       UUID takerOrderId, UUID takerUserId, String takerUsername, Types.Side takerSide,
                       Types.Action takerAction, int takerPrice, String makerUsername, Instant executedAt) {

    /** Cash that changed hands: both sides' payments for a mint or burn, the price for a transfer. */
    public long cashCents() {
        return switch (kind) {
            case MINT, BURN -> 100L * shares;
            case TRANSFER_YES -> (long) price * shares;
            case TRANSFER_NO -> (long) (100 - price) * shares;
        };
    }
}
