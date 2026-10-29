package dev.frostguard.tasks.exploration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TrekHudProgressParserTest {

    @Test
    void parsesCompletedHundredOverHundred() {
        TrekHudProgress progress = TrekHudProgressParser.parse("100/100");
        assertNotNull(progress);
        assertEquals(100, progress.current());
        assertEquals(100, progress.max());
        assertTrue(progress.isComplete());
    }

    @Test
    void parsesAnyCompletedMaxWithoutHardCap() {
        TrekHudProgress fiftyEight = TrekHudProgressParser.parse("58/58");
        assertNotNull(fiftyEight);
        assertTrue(fiftyEight.isComplete());

        TrekHudProgress fourHundred = TrekHudProgressParser.parse("400/400");
        assertNotNull(fourHundred);
        assertEquals(400, fourHundred.current());
        assertEquals(400, fourHundred.max());
        assertTrue(fourHundred.isComplete());
    }

    @Test
    void parsesInProgressFraction() {
        TrekHudProgress progress = TrekHudProgressParser.parse("37/100");
        assertNotNull(progress);
        assertEquals(37, progress.current());
        assertFalse(progress.isComplete());
    }

    @Test
    void toleratesOcrNoiseAndLookalikeGlyphs() {
        TrekHudProgress progress = TrekHudProgressParser.parse("lOO / 1OO");
        assertNotNull(progress);
        assertTrue(progress.isComplete());
    }

    @Test
    void rejectsBlankAndNonFractionNoise() {
        assertNull(TrekHudProgressParser.parse(null));
        assertNull(TrekHudProgressParser.parse(""));
        assertNull(TrekHudProgressParser.parse("Idle Trek"));
        assertNull(TrekHudProgressParser.parse("12/3"));
    }
}
