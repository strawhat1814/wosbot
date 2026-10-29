package dev.frostguard.tasks.pets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.tasks.pets.PetAdventureDecisions.PetAdventureNextVisit;

class PetAdventureStartOutcomeTest {

    @Test
    void unverifiedStartRetriesShortlyWithoutSpendingStaminaOrUsingReset() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 24, 8, 57);

        assertEquals(PetAdventureNextVisit.UNKNOWN_RETRY, PetAdventureDecisions.nextVisit(0, true, false));
        assertEquals(now.plusMinutes(15), PetAdventureDecisions.unknownRetryAt(now));
        assertFalse(PetAdventureDecisions.recordsStaminaSpend(null));
        assertFalse(PetAdventureDecisions.recordsStaminaSpend(ImageSearchResultData.miss()));
    }

    @Test
    void confirmedStartsWaitForCompletionEvenWhenAnotherPinWasUnverified() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 24, 8, 57);

        assertEquals(PetAdventureNextVisit.COMPLETION_CHECK, PetAdventureDecisions.nextVisit(1, true, false));
        assertEquals(now.plusHours(2), PetAdventureDecisions.completionCheckAt(now));
        assertTrue(PetAdventureDecisions.recordsStaminaSpend(ImageSearchResultData.hit(10, 20, 99)));
    }

    @Test
    void noIdlePinsWaitForCompletionInsteadOfRetryingImmediately() {
        assertEquals(PetAdventureNextVisit.COMPLETION_CHECK, PetAdventureDecisions.nextVisit(0, false, false));
    }

    @Test
    void noAttemptsUsesTheDailyResetRatherThanTheUnknownRetry() {
        assertEquals(PetAdventureNextVisit.ATTEMPTS_RESET, PetAdventureDecisions.nextVisit(0, true, true));
        assertEquals(PetAdventureNextVisit.ATTEMPTS_RESET, PetAdventureDecisions.nextVisit(2, false, true));
    }

    @Test
    void skipsThePinAlreadyAttemptedAndStillOffersTheOtherIdlePin() {
        PointData failed = new PointData(257, 1025);
        PointData rescanned = new PointData(260, 1022);
        PointData other = new PointData(480, 640);
        List<PointData> skipped = List.of(failed);

        assertTrue(PetAdventureDecisions.alreadyAttempted(rescanned, skipped));
        assertFalse(PetAdventureDecisions.alreadyAttempted(other, skipped));
        assertFalse(PetAdventureDecisions.occupiedByTimer(other, List.of()));
    }

    @Test
    void treatsATimerUnderThePinAsAnOccupiedChest() {
        PointData chest = new PointData(180, 360);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertTrue(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }

    @Test
    void leavesAChestIdleWhenTheTimerBelongsToAnotherPin() {
        PointData chest = new PointData(600, 700);
        ImageSearchResultData timer = ImageSearchResultData.hit(175, 450, 95);
        assertFalse(PetAdventureDecisions.occupiedByTimer(chest, List.of(timer)));
    }
}
