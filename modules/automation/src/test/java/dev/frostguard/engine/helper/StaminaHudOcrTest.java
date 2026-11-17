package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StaminaHudOcrTest {

    @Test
    void keepsSingleAndTwoDigitValues() {
        assertEquals("6", StaminaHudOcr.preferFullerDigits("6", "6"));
        assertEquals("46", StaminaHudOcr.preferFullerDigits("46", "46"));
        assertEquals("6", StaminaHudOcr.preferFullerDigits("6", null));
        assertEquals("46", StaminaHudOcr.preferFullerDigits(null, "46"));
    }

    @Test
    void restoresSlenderLeadingOneDroppedByRaw() {
        assertEquals("146", StaminaHudOcr.preferFullerDigits("146", "46"));
        assertEquals("142", StaminaHudOcr.preferFullerDigits("142", "42"));
        assertEquals("1455", StaminaHudOcr.preferFullerDigits("1455", "455"));
        assertEquals("141", StaminaHudOcr.preferFullerDigits("1 41", "41"));
        assertEquals("141", StaminaHudOcr.preferFullerDigits("1", "41"));
        assertEquals(141, StaminaHudOcr.parseDigits(StaminaHudOcr.preferFullerDigits("1", "41")));
    }

    @Test
    void restoresThickDigitsSplitByIsolation() {
        assertEquals("224", StaminaHudOcr.preferFullerDigits("2", "224"));
        assertEquals("224", StaminaHudOcr.preferFullerDigits("4", "224"));
        assertEquals("224", StaminaHudOcr.preferFullerDigits("2 4", "224"));
    }

    @Test
    void doesNotInventFourDigitsFromElevenAndOneHundredNineteen() {
        assertEquals("119", StaminaHudOcr.preferFullerDigits("11", "119"));
    }

    @Test
    void bothMissingStayUnknown() {
        assertNull(StaminaHudOcr.preferFullerDigits(" ", null));
        assertNull(StaminaHudOcr.parseDigits(null));
    }
}
