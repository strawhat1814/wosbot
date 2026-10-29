package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import org.junit.jupiter.api.Test;

class BearBTimingTest {

    @Test
    void ownCycleUsesFiveMinutesPlusTwiceMarchWhenKnown() {
        assertEquals(324, BearBTiming.ownCycleSeconds(OptionalLong.of(12)));
    }

    @Test
    void ownCycleFallsBackToFiveFifteenWhenUnknown() {
        assertEquals(315, BearBTiming.ownCycleSeconds(OptionalLong.empty()));
    }

    @Test
    void ownCutoffUsesFiveMinutesPlusMarch() {
        assertEquals(312, BearBTiming.ownCutoffRemainingSeconds(OptionalLong.of(12)));
        assertEquals(315, BearBTiming.ownCutoffRemainingSeconds(OptionalLong.empty()));
        assertTrue(BearBTiming.shouldStopOwn(312, OptionalLong.of(12)));
        assertFalse(BearBTiming.shouldStopOwn(313, OptionalLong.of(12)));
    }
}
