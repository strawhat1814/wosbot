package dev.frostguard.engine.schedule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Global soft-schedule jitter for relative task delays.
 *
 * <p>Delays longer than {@link #MIN_DELAY_FOR_JITTER} receive an <em>add-only</em>
 * random offset of up to 10% of the delay, capped at {@link #MAX_JITTER}. Shorter
 * delays and already-due targets are left unchanged so tight polls and event
 * loops stay exact.</p>
 *
 * <p>Wall-clock targets (Bear windows, restored schedules, UTC event starts)
 * must call {@link DelayedTask#rescheduleExact(LocalDateTime)} instead.</p>
 */
public final class ScheduleJitterPolicy {

    public static final Duration MIN_DELAY_FOR_JITTER = Duration.ofMinutes(10);
    public static final double JITTER_FRACTION = 0.10d;
    public static final Duration MAX_JITTER = Duration.ofHours(2);

    private ScheduleJitterPolicy() {
    }

    /**
     * Returns {@code target} unchanged, or {@code target} plus {@code [0, jitter]}
     * where {@code jitter = min(10% of delay, 2h)} when the delay from {@code now}
     * exceeds 10 minutes.
     */
    public static LocalDateTime applySoft(LocalDateTime now, LocalDateTime target) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(target, "target");
        Duration delay = Duration.between(now, target);
        if (delay.compareTo(MIN_DELAY_FOR_JITTER) <= 0) {
            return target;
        }
        long maxJitterMs = maxJitterMillis(delay);
        if (maxJitterMs <= 0L) {
            return target;
        }
        long addedMs = ThreadLocalRandom.current().nextLong(maxJitterMs + 1L);
        return target.plus(Duration.ofMillis(addedMs));
    }

    /** Deterministic max jitter for tests and documentation. */
    public static Duration maxJitterFor(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.compareTo(MIN_DELAY_FOR_JITTER) <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(maxJitterMillis(delay));
    }

    static LocalDateTime applySoft(LocalDateTime now, LocalDateTime target, long addedMs) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(target, "target");
        Duration delay = Duration.between(now, target);
        if (delay.compareTo(MIN_DELAY_FOR_JITTER) <= 0 || addedMs <= 0L) {
            return target;
        }
        long capped = Math.min(addedMs, maxJitterMillis(delay));
        return target.plus(Duration.ofMillis(Math.max(0L, capped)));
    }

    private static long maxJitterMillis(Duration delay) {
        long delayMs = delay.toMillis();
        if (delayMs <= 0L) {
            return 0L;
        }
        long tenPercent = Math.round(delayMs * JITTER_FRACTION);
        return Math.min(tenPercent, MAX_JITTER.toMillis());
    }
}
