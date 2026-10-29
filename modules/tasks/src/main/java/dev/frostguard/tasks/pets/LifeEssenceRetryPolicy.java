package dev.frostguard.tasks.pets;

import java.time.Duration;

final class LifeEssenceRetryPolicy {

    private static final Duration SECOND_ATTEMPT_DELAY = Duration.ofHours(1);

    private LifeEssenceRetryPolicy() {
    }

    /**
     * Two attempts per day: first failure retries after one hour; second failure
     * defers to the next day and clears the failure counter so tomorrow starts fresh.
     */
    static Decision afterFailure(int persistedFailures) {
        int normalizedFailures = Math.max(0, persistedFailures);
        if (normalizedFailures < 1) {
            return new Decision(1, SECOND_ATTEMPT_DELAY, false);
        }
        return new Decision(0, Duration.ZERO, true);
    }

    record Decision(int persistedFailures, Duration retryDelay, boolean deferToNextDay) {
    }
}
