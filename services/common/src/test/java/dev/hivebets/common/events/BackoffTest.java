package dev.hivebets.common.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void doublesUpToTheCapAndResets() {
        AtomicLong now = new AtomicLong(1_000);
        Backoff b = new Backoff(200, 1_000, now::get);
        assertTrue(b.ready());
        assertEquals(200, b.failed());
        assertFalse(b.ready());
        now.addAndGet(200);
        assertTrue(b.ready());
        assertEquals(400, b.failed());
        assertEquals(800, b.failed());
        assertEquals(1_000, b.failed());
        assertEquals(1_000, b.failed());
        b.succeeded();
        assertTrue(b.ready());
        assertEquals(0, b.failures());
    }
}
