package dev.hivebets.common.events;

import java.util.function.LongSupplier;

/**
 * Exponential backoff for pollers that keep failing (e.g. an outbox relay while RabbitMQ is down):
 * 200 ms, 400 ms, ... up to a cap, reset by the first success. Not thread-safe; one per poller.
 */
public class Backoff {
    private final long baseMs, maxMs;
    private final LongSupplier clock;
    private int failures;
    private long nextAttemptAt;

    public Backoff(long baseMs, long maxMs) {
        this(baseMs, maxMs, System::currentTimeMillis);
    }

    public Backoff(long baseMs, long maxMs, LongSupplier clock) {
        this.baseMs = baseMs;
        this.maxMs = maxMs;
        this.clock = clock;
    }

    public boolean ready() {
        return clock.getAsLong() >= nextAttemptAt;
    }

    /** Records a failure and returns the wait before the next attempt. */
    public long failed() {
        failures++;
        long delay = Math.min(maxMs, baseMs << Math.min(failures - 1, 20));
        nextAttemptAt = clock.getAsLong() + delay;
        return delay;
    }

    public void succeeded() {
        failures = 0;
        nextAttemptAt = 0;
    }

    public int failures() {
        return failures;
    }
}
