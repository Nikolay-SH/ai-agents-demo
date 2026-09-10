package meetup.sherlock.live;

import java.time.Duration;
import java.util.function.LongSupplier;

public final class ActiveBudget {
    private final LongSupplier clock;
    private final int maxCalls;
    private final long limit;
    private final long start;
    private long pausedNanos;
    private long pauseStart;
    private boolean paused;
    private boolean closed;
    private int calls;
    public ActiveBudget(int maxCalls, Duration duration) { this(maxCalls, duration, System::nanoTime); }
    ActiveBudget(int maxCalls, Duration duration, LongSupplier clock) {
        if (maxCalls < 1 || duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("Positive budgets required");
        this.maxCalls = maxCalls; this.limit = duration.toNanos(); this.clock = clock; this.start = clock.getAsLong();
    }
    public synchronized void charge() {
        if (paused) throw new IllegalStateException("Investigation paused for human approval");
        if (closed || expired() || calls >= maxCalls) {
            closed = true;
            throw new IllegalStateException("Investigation budget exhausted");
        }
        calls++;
    }
    public synchronized boolean expired() {
        return closed || (paused ? pauseStart : clock.getAsLong()) - start - pausedNanos >= limit;
    }
    public synchronized void pause() { if (!paused) { pauseStart = clock.getAsLong(); paused = true; } }
    public synchronized void resume() { if (paused) { pausedNanos += clock.getAsLong() - pauseStart; paused = false; } }
    public synchronized void close() { closed = true; }
}
