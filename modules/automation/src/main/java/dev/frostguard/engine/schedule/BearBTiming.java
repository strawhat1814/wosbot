package dev.frostguard.engine.schedule;

import java.util.OptionalLong;

/**
 * Pure timing helpers for Bear B own-rally cadence and cutoff.
 */
public final class BearBTiming {

    public static final int RALLY_HOLD_SECONDS = 300;
    public static final int UNKNOWN_MARCH_FALLBACK_SECONDS = 15;

    private BearBTiming() {
    }

    public static int effectiveMarchSeconds(OptionalLong lockedOrRead) {
        return lockedOrRead.isPresent()
                ? Math.toIntExact(Math.max(0L, lockedOrRead.getAsLong()))
                : UNKNOWN_MARCH_FALLBACK_SECONDS;
    }

    /** Next wake delay after a successful own: 5 min + 2 * march (or 5:15 when unknown). */
    public static int ownCycleSeconds(OptionalLong lockedOrRead) {
        if (lockedOrRead.isPresent()) {
            return RALLY_HOLD_SECONDS + 2 * effectiveMarchSeconds(lockedOrRead);
        }
        return RALLY_HOLD_SECONDS + UNKNOWN_MARCH_FALLBACK_SECONDS;
    }

    /** Stop launching own when remaining time is at or below this many seconds. */
    public static int ownCutoffRemainingSeconds(OptionalLong lockedOrRead) {
        return RALLY_HOLD_SECONDS + effectiveMarchSeconds(lockedOrRead);
    }

    public static boolean shouldStopOwn(long secondsRemaining, OptionalLong lockedOrRead) {
        return secondsRemaining <= ownCutoffRemainingSeconds(lockedOrRead);
    }
}
