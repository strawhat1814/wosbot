package dev.frostguard.engine.schedule;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide Bear B session registry keyed by profile id.
 */
public final class BearBSessionStore {

    private static final Map<Long, BearBSession> SESSIONS = new ConcurrentHashMap<>();

    private BearBSessionStore() {
    }

    public static BearBSession openOrReplace(
            long profileId,
            int trapNumber,
            LocalDateTime activationUtc,
            LocalDateTime endUtc,
            Instant windowEndInstant) {
        BearBSession session = new BearBSession(
                profileId, trapNumber, activationUtc, endUtc, windowEndInstant);
        SESSIONS.put(profileId, session);
        return session;
    }

    public static Optional<BearBSession> find(long profileId) {
        return Optional.ofNullable(SESSIONS.get(profileId));
    }

    public static BearBSession require(
            long profileId,
            int trapNumber,
            LocalDateTime activationUtc,
            LocalDateTime endUtc,
            Instant windowEndInstant) {
        return SESSIONS.compute(profileId, (id, existing) -> {
            if (existing != null
                    && existing.trapNumber() == trapNumber
                    && existing.endUtc().equals(endUtc)) {
                return existing;
            }
            return new BearBSession(profileId, trapNumber, activationUtc, endUtc, windowEndInstant);
        });
    }

    public static void clear(long profileId) {
        SESSIONS.remove(profileId);
    }

    public static void clearAllForTests() {
        SESSIONS.clear();
    }
}
