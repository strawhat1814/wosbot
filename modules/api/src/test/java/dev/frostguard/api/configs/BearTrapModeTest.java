package dev.frostguard.api.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BearTrapModeTest {

    @Test
    void defaultsBlankAndUnknownToModeA() {
        assertEquals(BearTrapMode.A, BearTrapMode.fromStoredValue(null));
        assertEquals(BearTrapMode.A, BearTrapMode.fromStoredValue(""));
        assertEquals(BearTrapMode.A, BearTrapMode.fromStoredValue("A"));
        assertEquals(BearTrapMode.A, BearTrapMode.fromStoredValue("legacy"));
    }

    @Test
    void parsesModeBCaseInsensitively() {
        assertEquals(BearTrapMode.B, BearTrapMode.fromStoredValue("B"));
        assertEquals(BearTrapMode.B, BearTrapMode.fromStoredValue("b"));
        assertTrue(BearTrapMode.isModeB("B"));
        assertFalse(BearTrapMode.isModeB("A"));
    }
}
