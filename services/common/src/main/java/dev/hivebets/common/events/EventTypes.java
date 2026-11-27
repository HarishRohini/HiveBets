package dev.hivebets.common.events;

/** Event type names; each is also the RabbitMQ routing key. */
public final class EventTypes {
    private EventTypes() {}

    public static final String EXCHANGE = "hivebets.events";
    public static final String DEAD_LETTER_EXCHANGE = "hivebets.dlx";

    public static final String MARKET_CREATED = "market.created";
    public static final String MARKET_CLOSED = "market.closed";
    public static final String MARKET_RESOLVED = "market.resolved";
    public static final String ORDER_ACCEPTED = "order.accepted";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String TRADE_EXECUTED = "trade.executed";
    public static final String BOOK_CHANGED = "book.changed";
    public static final String WALLET_CHANGED = "wallet.changed";
    public static final String NEWS_PUBLISHED = "news.published";
    public static final String CLIENT_PREFIX = "client.";
}
