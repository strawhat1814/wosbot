package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class BearBSessionTest {

    @Test
    void joinYieldsToOwnUntilConsecutiveFailCap() {
        BearBSession session = newSession();
        assertTrue(session.shouldJoinYieldToOwn());
        assertEquals(0, session.consecutiveOwnFails());

        session.recordOwnFail();
        assertTrue(session.shouldJoinYieldToOwn());
        assertEquals(1, session.consecutiveOwnFails());

        session.recordOwnFail();
        assertFalse(session.shouldJoinYieldToOwn());
        assertEquals(BearBSession.OWN_FAIL_JOIN_YIELD_CAP, session.consecutiveOwnFails());
    }

    @Test
    void ownSuccessClearsConsecutiveFails() {
        BearBSession session = newSession();
        session.recordOwnFail();
        session.recordOwnFail();
        assertFalse(session.shouldJoinYieldToOwn());

        session.incrementOwnCalled();
        assertEquals(0, session.consecutiveOwnFails());
        assertTrue(session.shouldJoinYieldToOwn());
        assertEquals(1, session.ownCalled());
    }

    private static BearBSession newSession() {
        LocalDateTime activation = LocalDateTime.of(2026, 10, 28, 12, 0);
        LocalDateTime end = activation.plusMinutes(30);
        return new BearBSession(
                1L,
                1,
                activation,
                end,
                end.toInstant(ZoneOffset.UTC));
    }
}
