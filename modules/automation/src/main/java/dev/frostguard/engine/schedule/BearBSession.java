package dev.frostguard.engine.schedule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process Bear B state for one profile's current participation window.
 */
public final class BearBSession {

    private final long profileId;
    private final int trapNumber;
    private final LocalDateTime activationUtc;
    private final LocalDateTime endUtc;
    private final Instant windowEndInstant;

    private final AtomicBoolean prepDone = new AtomicBoolean(false);
    private final AtomicBoolean prepWarned = new AtomicBoolean(false);
    private final AtomicBoolean endReported = new AtomicBoolean(false);
    private final AtomicBoolean ownStoppedForCutoff = new AtomicBoolean(false);

    private volatile Long lockedMarchSeconds;
    private volatile Instant ownBusyUntil = Instant.EPOCH;
    private volatile Instant nextOwnAt = Instant.EPOCH;

    private final AtomicInteger ownCalled = new AtomicInteger(0);
    private final AtomicInteger joinsOk = new AtomicInteger(0);
    private final AtomicInteger joinsFailed = new AtomicInteger(0);
    /** Consecutive Own deploy failures; Join stops yielding once this reaches the cap. */
    private final AtomicInteger consecutiveOwnFails = new AtomicInteger(0);

    /** After this many Own fails in a row, Join runs even while Own is still due. */
    public static final int OWN_FAIL_JOIN_YIELD_CAP = 2;

    public BearBSession(
            long profileId,
            int trapNumber,
            LocalDateTime activationUtc,
            LocalDateTime endUtc,
            Instant windowEndInstant) {
        this.profileId = profileId;
        this.trapNumber = trapNumber;
        this.activationUtc = activationUtc;
        this.endUtc = endUtc;
        this.windowEndInstant = windowEndInstant;
    }

    public long profileId() {
        return profileId;
    }

    public int trapNumber() {
        return trapNumber;
    }

    public LocalDateTime activationUtc() {
        return activationUtc;
    }

    public LocalDateTime endUtc() {
        return endUtc;
    }

    public Instant windowEndInstant() {
        return windowEndInstant;
    }

    public boolean markPrepWarned() {
        return prepWarned.compareAndSet(false, true);
    }

    public boolean isPrepDone() {
        return prepDone.get();
    }

    public void markPrepDone() {
        prepDone.set(true);
    }

    public boolean markEndReported() {
        return endReported.compareAndSet(false, true);
    }

    public boolean isOwnStoppedForCutoff() {
        return ownStoppedForCutoff.get();
    }

    public void markOwnStoppedForCutoff() {
        ownStoppedForCutoff.set(true);
    }

    public OptionalLong lockedMarchSeconds() {
        Long value = lockedMarchSeconds;
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public void lockMarchSeconds(long seconds) {
        if (seconds > 0 && lockedMarchSeconds == null) {
            lockedMarchSeconds = seconds;
        }
    }

    public Instant ownBusyUntil() {
        return ownBusyUntil;
    }

    public void setOwnBusyUntil(Instant until) {
        this.ownBusyUntil = until == null ? Instant.EPOCH : until;
    }

    public Instant nextOwnAt() {
        return nextOwnAt;
    }

    public void setNextOwnAt(Instant at) {
        this.nextOwnAt = at == null ? Instant.EPOCH : at;
    }

    public void incrementOwnCalled() {
        ownCalled.incrementAndGet();
        consecutiveOwnFails.set(0);
    }

    public void recordOwnFail() {
        consecutiveOwnFails.incrementAndGet();
    }

    /**
     * Join should briefly yield only while Own is due and has not failed
     * {@link #OWN_FAIL_JOIN_YIELD_CAP} times in a row.
     */
    public boolean shouldJoinYieldToOwn() {
        return consecutiveOwnFails.get() < OWN_FAIL_JOIN_YIELD_CAP;
    }

    public int consecutiveOwnFails() {
        return consecutiveOwnFails.get();
    }

    public void incrementJoinsOk() {
        joinsOk.incrementAndGet();
    }

    public void incrementJoinsFailed() {
        joinsFailed.incrementAndGet();
    }

    public int ownCalled() {
        return ownCalled.get();
    }

    public int joinsOk() {
        return joinsOk.get();
    }

    public int joinsFailed() {
        return joinsFailed.get();
    }
}
