package dev.hivebets.trading.domain;

import java.util.UUID;

/** A trader's cash. Reserved cash backs open buy orders. */
public class Wallet {
    public final UUID userId;
    public String username;
    public String accountType;
    public long available;
    public long reserved;
    public boolean dirty;

    public Wallet(UUID userId, String username, String accountType, long available, long reserved) {
        this.userId = userId;
        this.username = username;
        this.accountType = accountType;
        this.available = available;
        this.reserved = reserved;
    }
}
