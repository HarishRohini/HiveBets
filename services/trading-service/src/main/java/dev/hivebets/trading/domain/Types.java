package dev.hivebets.trading.domain;

/** Small enums shared by the trading domain. */
public final class Types {
    private Types() {}

    public enum Side { YES, NO }
    public enum Action { BUY, SELL }
    public enum BookSide { BID, ASK }
    public enum OrderStatus { OPEN, FILLED, CANCELLED }
    public enum MarketStatus { OPEN, CLOSED, RESOLVED }
    public enum TradeKind { MINT, TRANSFER_YES, TRANSFER_NO, BURN }

    /** Everything trades on one YES book: buying YES and selling NO are bids; selling YES and buying NO are asks. */
    public static BookSide bookSide(Side side, Action action) {
        return (side == Side.YES) == (action == Action.BUY) ? BookSide.BID : BookSide.ASK;
    }

    public static int yesPrice(Side side, int ownPrice) {
        return side == Side.YES ? ownPrice : 100 - ownPrice;
    }

    public static int ownPrice(Side side, int yesPrice) {
        return side == Side.YES ? yesPrice : 100 - yesPrice;
    }
}
