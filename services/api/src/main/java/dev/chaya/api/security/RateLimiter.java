package dev.chaya.api.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * In-process fixed-window counters. Correct for the single API instance this project deploys (ARCHITECTURE.md: one
 * monolith). With several instances each enforces its own budget, so the effective limit multiplies; a shared store
 * (Redis) would then be needed -- recorded in docs/security-hardening.md.
 */
@Component
public class RateLimiter {

    public record Decision(boolean allowed, long retryAfterSeconds) {}

    private record Window(long minute, AtomicInteger count) {}

    static final int MAX_TRACKED_KEYS = 100_000;

    private final RateLimitProperties props;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private volatile long lastSweepMinute = -1;

    public RateLimiter(RateLimitProperties props) {
        this.props = props;
    }

    public RateLimitProperties properties() {
        return props;
    }

    public Decision tryAcquire(String key, int limitPerMinute, long nowMillis) {
        long minute = nowMillis / 60_000;
        sweep(minute);
        Window w = windows.compute(key, (k, old) -> old == null || old.minute() != minute ? new Window(minute, new AtomicInteger()) : old);
        int n = w.count().incrementAndGet();
        if (n <= limitPerMinute) {
            return new Decision(true, 0);
        }
        long retryAfter = Math.max(1, ((minute + 1) * 60_000 - nowMillis + 999) / 1000);
        return new Decision(false, retryAfter);
    }

    /** Drops windows from earlier minutes once per minute, and bounds memory against key-spraying. */
    private void sweep(long minute) {
        if (minute != lastSweepMinute || windows.size() > MAX_TRACKED_KEYS) {
            lastSweepMinute = minute;
            windows.values().removeIf(w -> w.minute() != minute);
            if (windows.size() > MAX_TRACKED_KEYS) {
                windows.clear(); // all keys are from this minute: a flood; start over rather than grow without bound
            }
        }
    }

    int trackedKeys() {
        return windows.size();
    }
}
