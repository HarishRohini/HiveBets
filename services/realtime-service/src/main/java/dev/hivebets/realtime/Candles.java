package dev.hivebets.realtime;

/** 10-second OHLCV buckets. */
public final class Candles {
    private Candles() {}

    public static final int SECONDS = 10;

    public record Candle(long t, int o, int h, int l, int c, long v) {
        public Candle add(int price, long shares) {
            return new Candle(t, o, Math.max(h, price), Math.min(l, price), price, v + shares);
        }
    }

    public static long bucket(long epochSeconds) {
        return epochSeconds - Math.floorMod(epochSeconds, SECONDS);
    }

    public static Candle start(long t, int price, long shares) {
        return new Candle(t, price, price, price, price, shares);
    }
}
