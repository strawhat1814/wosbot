package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LifeEssenceRetryPolicyTest {

    @Test
    void schedulesSecondAttemptAfterOneHour() {
        LifeEssenceRetryPolicy.Decision decision = LifeEssenceRetryPolicy.afterFailure(0);

        assertEquals(1, decision.persistedFailures());
        assertEquals(Duration.ofHours(1), decision.retryDelay());
        assertFalse(decision.deferToNextDay());
    }

    @Test
    void defersToNextDayAndClearsFailuresAfterSecondAttempt() {
        LifeEssenceRetryPolicy.Decision decision = LifeEssenceRetryPolicy.afterFailure(1);

        assertEquals(0, decision.persistedFailures());
        assertTrue(decision.deferToNextDay());
    }

    @Test
    void treatsAlreadySaturatedFailureStateAsNextDayDeferral() {
        LifeEssenceRetryPolicy.Decision decision = LifeEssenceRetryPolicy.afterFailure(6);

        assertEquals(0, decision.persistedFailures());
        assertTrue(decision.deferToNextDay());
    }

    @Test
    void treatsMalformedNegativeFailureStateAsNoPreviousFailure() {
        LifeEssenceRetryPolicy.Decision decision = LifeEssenceRetryPolicy.afterFailure(-7);

        assertEquals(1, decision.persistedFailures());
        assertEquals(Duration.ofHours(1), decision.retryDelay());
        assertFalse(decision.deferToNextDay());
    }
}
