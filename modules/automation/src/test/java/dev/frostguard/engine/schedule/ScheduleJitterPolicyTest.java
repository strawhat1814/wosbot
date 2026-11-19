package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class ScheduleJitterPolicyTest {

    @Test
    void delaysAtOrBelowTenMinutesStayExact() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 12, 0, 0);
        LocalDateTime tenMinutes = now.plusMinutes(10);
        LocalDateTime fiveMinutes = now.plusMinutes(5);

        assertEquals(Duration.ZERO, ScheduleJitterPolicy.maxJitterFor(Duration.ofMinutes(10)));
        assertEquals(Duration.ZERO, ScheduleJitterPolicy.maxJitterFor(Duration.ofMinutes(5)));
        assertEquals(tenMinutes, ScheduleJitterPolicy.applySoft(now, tenMinutes));
        assertEquals(fiveMinutes, ScheduleJitterPolicy.applySoft(now, fiveMinutes));
    }

    @Test
    void sixtyMinutesGetsUpToSixMinutesAddOnlyJitter() {
        assertEquals(Duration.ofMinutes(6), ScheduleJitterPolicy.maxJitterFor(Duration.ofMinutes(60)));

        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 12, 0, 0);
        LocalDateTime target = now.plusMinutes(60);
        LocalDateTime withHalf = ScheduleJitterPolicy.applySoft(now, target, Duration.ofMinutes(3).toMillis());
        LocalDateTime withCap = ScheduleJitterPolicy.applySoft(now, target, Duration.ofMinutes(30).toMillis());

        assertEquals(target.plusMinutes(3), withHalf);
        assertEquals(target.plusMinutes(6), withCap);
    }

    @Test
    void twentyFourHoursCapsJitterAtTwoHours() {
        Duration day = Duration.ofHours(24);
        assertEquals(Duration.ofHours(2), ScheduleJitterPolicy.maxJitterFor(day));

        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 12, 0, 0);
        LocalDateTime target = now.plus(day);
        LocalDateTime jittered = ScheduleJitterPolicy.applySoft(now, target, Duration.ofHours(5).toMillis());

        assertEquals(target.plusHours(2), jittered);
        assertTrue(!jittered.isBefore(target));
    }

    @Test
    void randomSoftJitterStaysInsideAddOnlyWindow() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 19, 12, 0, 0);
        LocalDateTime target = now.plusHours(1);
        for (int i = 0; i < 40; i++) {
            LocalDateTime jittered = ScheduleJitterPolicy.applySoft(now, target);
            assertTrue(!jittered.isBefore(target));
            assertTrue(!jittered.isAfter(target.plusMinutes(6)));
        }
    }
}
