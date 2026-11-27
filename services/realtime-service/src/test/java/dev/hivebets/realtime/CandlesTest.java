package dev.hivebets.realtime;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CandlesTest {
    @Test
    void bucketsAndOhlc() {
        assertEquals(1700000000L, Candles.bucket(1700000007L));
        var c = Candles.start(1700000000L, 50, 10).add(55, 5).add(48, 1).add(52, 2);
        assertEquals(50, c.o());
        assertEquals(55, c.h());
        assertEquals(48, c.l());
        assertEquals(52, c.c());
        assertEquals(18, c.v());
    }

    @Test
    void volumeMatchesTradingService() {
        var m = JsonMapper.builder().build();
        assertEquals(1000, LiveState.cash(m.readTree("{\"kind\":\"MINT\",\"price\":60,\"shares\":10}")));
        assertEquals(600, LiveState.cash(m.readTree("{\"kind\":\"TRANSFER_YES\",\"price\":60,\"shares\":10}")));
        assertEquals(400, LiveState.cash(m.readTree("{\"kind\":\"TRANSFER_NO\",\"price\":60,\"shares\":10}")));
    }
}
