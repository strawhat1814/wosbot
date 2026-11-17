package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StaminaHudOcrTest {

    @Test
    void keepsSingleAndTwoDigitValues() {
        assertEquals(6, StaminaHudOcr.preferFuller(6, 6));
        assertEquals(46, StaminaHudOcr.preferFuller(46, 46));
        assertEquals(6, StaminaHudOcr.preferFuller(6, null));
        assertEquals(46, StaminaHudOcr.preferFuller(null, 46));
    }

    @Test
    void restoresSlenderLeadingOneDroppedByRaw() {
        assertEquals(146, StaminaHudOcr.preferFuller(146, 46));
        assertEquals(142, StaminaHudOcr.preferFuller(142, 42));
        assertEquals(1455, StaminaHudOcr.preferFuller(1455, 455));
    }

    @Test
    void restoresThickDigitsSplitByIsolation() {
        assertEquals(224, StaminaHudOcr.preferFuller(2, 224));
        assertEquals(224, StaminaHudOcr.preferFuller(4, 224));
        assertEquals("224", StaminaHudOcr.preferFullerDigits("2 4", "224"));
    }

    @Test
    void bothMissingStayUnknown() {
        assertNull(StaminaHudOcr.preferFuller(null, null));
        assertNull(StaminaHudOcr.preferFullerDigits(" ", null));
    }
}
