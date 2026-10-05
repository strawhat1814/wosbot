package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class WarmWelcomeScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 1, 3, 13, 21);

    @Test
    void retriesSoonWhenCooldownOcrIsUnreadableInsteadOfAssumingClaimedUntilReset() {
        assertEquals(NOW.plusMinutes(5), WarmWelcomeRoutine.unreadableCooldownFallback(NOW));
    }
}
