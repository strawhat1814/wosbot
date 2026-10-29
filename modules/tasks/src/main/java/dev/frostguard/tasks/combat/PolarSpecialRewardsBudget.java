package dev.frostguard.tasks.combat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Remembers the last Special Rewards read so Polar Terror does not open the reward
 * panel before every rally. The read sets a rally quota. Each confirmed rally spends
 * one. The panel is opened again only after {@link #RECHECK_INTERVAL}, or after the
 * 00:00 UTC reward reset. A read of zero stops hunting until that reset; spending the
 * quota does not, because the earlier read may be stale.
 */
public final class PolarSpecialRewardsBudget {

    static final Duration RECHECK_INTERVAL = Duration.ofHours(1);

    private static final ConcurrentMap<Long, Snapshot> BUDGETS = new ConcurrentHashMap<>();

    private PolarSpecialRewardsBudget() {
    }

    enum Action {
        INSPECT,
        HUNT,
        WAIT,
        STOP_FOR_DAY
    }

    record Snapshot(int remaining, int quota, LocalDateTime checkedAt, boolean confirmedExhausted) {
        int launched() {
            return Math.max(0, quota - remaining);
        }
    }

    record Decision(Action action, int remaining, int quota, LocalDateTime checkedAt, LocalDateTime resumeAt) {
        int launchedSinceCheck() {
            return Math.max(0, quota - remaining);
        }
    }

    static Decision decide(long profileId, LocalDateTime now) {
        return decide(BUDGETS.get(profileId), now, ZoneId.systemDefault());
    }

    static Decision decide(Snapshot snapshot, LocalDateTime now, ZoneId zone) {
        if (snapshot == null || snapshot.checkedAt() == null || !sameRewardDay(snapshot.checkedAt(), now, zone)) {
            return new Decision(Action.INSPECT, 0, 0, snapshot == null ? null : snapshot.checkedAt(), null);
        }
        if (snapshot.confirmedExhausted()) {
            return new Decision(Action.STOP_FOR_DAY, 0, snapshot.quota(), snapshot.checkedAt(), null);
        }
        if (!now.isBefore(snapshot.checkedAt().plus(RECHECK_INTERVAL))) {
            return new Decision(Action.INSPECT, snapshot.remaining(), snapshot.quota(), snapshot.checkedAt(), null);
        }
        if (snapshot.remaining() <= 0) {
            return new Decision(Action.WAIT, 0, snapshot.quota(), snapshot.checkedAt(),
                    snapshot.checkedAt().plus(RECHECK_INTERVAL));
        }
        return new Decision(Action.HUNT, snapshot.remaining(), snapshot.quota(), snapshot.checkedAt(), null);
    }

    static void recordInspection(long profileId, int remaining, LocalDateTime checkedAt) {
        int quota = Math.max(0, remaining);
        BUDGETS.put(profileId, new Snapshot(quota, quota, checkedAt, false));
    }

    static void recordConfirmedExhausted(long profileId, LocalDateTime checkedAt) {
        BUDGETS.put(profileId, new Snapshot(0, 0, checkedAt, true));
    }

    static Snapshot noteSuccessfulRally(long profileId) {
        return BUDGETS.computeIfPresent(profileId, (id, snapshot) -> {
            if (snapshot.confirmedExhausted() || snapshot.remaining() <= 0) {
                return snapshot;
            }
            return new Snapshot(snapshot.remaining() - 1, snapshot.quota(), snapshot.checkedAt(), false);
        });
    }

    static void clear() {
        BUDGETS.clear();
    }

    /** Drop a bad/stale read (e.g. inspected while on the wrong shared-login character). */
    public static void clearProfile(long profileId) {
        BUDGETS.remove(profileId);
    }

    private static boolean sameRewardDay(LocalDateTime checkedAt, LocalDateTime now, ZoneId zone) {
        return utcDate(checkedAt, zone).equals(utcDate(now, zone));
    }

    private static LocalDate utcDate(LocalDateTime local, ZoneId zone) {
        return local.atZone(zone).withZoneSameInstant(ZoneOffset.UTC).toLocalDate();
    }
}
