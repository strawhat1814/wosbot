package dev.frostguard.tasks.lifecycle;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnrDialogClassifierTest {

    @Test
    void acceptsAnrDialogVariants() {
        for (String variant : new String[] {
                "Whiteout Survival isn't responding\nClose app\nWait",
                "Whiteout Survival isnt responding Close app Wait",
                "isn't responding Wait",
                "not responding\nWait",
                "App isn't responding Close Wait" }) {
            assertTrue(AnrDialogClassifier.inspect(variant).detected(), variant);
        }
    }

    @Test
    void rejectsUnrelatedStartupText() {
        for (String nonAnr : new String[] {
                "Welcome back",
                "Tap anywhere to exit",
                "Max offline income: 12hrs",
                "responding politely",
                "please wait loading",
                "" }) {
            assertFalse(AnrDialogClassifier.inspect(nonAnr).detected(), nonAnr);
        }
    }
}
