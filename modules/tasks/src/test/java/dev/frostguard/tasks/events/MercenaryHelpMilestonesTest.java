package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MercenaryHelpMilestonesTest {

    @Test
    void helpMilestonesAreEveryFifthLevelThroughTwentyFive() {
        assertTrue(MercenaryHelpMilestones.requiresAskForHelp(5));
        assertTrue(MercenaryHelpMilestones.requiresAskForHelp(10));
        assertTrue(MercenaryHelpMilestones.requiresAskForHelp(15));
        assertTrue(MercenaryHelpMilestones.requiresAskForHelp(20));
        assertTrue(MercenaryHelpMilestones.requiresAskForHelp(25));
    }

    @Test
    void nonMilestonesAndNullDoNotRequestHelp() {
        assertFalse(MercenaryHelpMilestones.requiresAskForHelp(null));
        assertFalse(MercenaryHelpMilestones.requiresAskForHelp(1));
        assertFalse(MercenaryHelpMilestones.requiresAskForHelp(4));
        assertFalse(MercenaryHelpMilestones.requiresAskForHelp(6));
        assertFalse(MercenaryHelpMilestones.requiresAskForHelp(30));
    }

    @Test
    void finalLevelIsTwentyFiveAndAbove() {
        assertFalse(MercenaryHelpMilestones.isFinalLevel(24));
        assertTrue(MercenaryHelpMilestones.isFinalLevel(25));
        assertTrue(MercenaryHelpMilestones.isFinalLevel(26));
        assertFalse(MercenaryHelpMilestones.isFinalLevel(null));
    }

    @Test
    void sanitizeRejectsTruncatedTwoDigitReadsAfterTeens() {
        assertNull(MercenaryHelpMilestones.sanitizeLevelRead(2, 20));
        assertNull(MercenaryHelpMilestones.sanitizeLevelRead(5, 19));
        assertNull(MercenaryHelpMilestones.sanitizeLevelRead(0, null));
        assertNull(MercenaryHelpMilestones.sanitizeLevelRead(30, null));
        assertEquals(25, MercenaryHelpMilestones.sanitizeLevelRead(25, 20));
        assertEquals(2, MercenaryHelpMilestones.sanitizeLevelRead(2, null));
        assertEquals(3, MercenaryHelpMilestones.sanitizeLevelRead(3, 2));
    }
}
