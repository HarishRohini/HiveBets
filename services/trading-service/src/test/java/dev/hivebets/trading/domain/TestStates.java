package dev.hivebets.trading.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Exposes the package-private in-memory state to tests in other packages. */
public final class TestStates {
    private TestStates() {}

    public static class Memory extends MemoryState {
        public Map<UUID, MarketState> markets() { return markets; }
        public List<Object[]> emitted() { return emitted; }
    }
}
