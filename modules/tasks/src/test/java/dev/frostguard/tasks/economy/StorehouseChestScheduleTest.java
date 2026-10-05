package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.helper.SidebarNavigator;

class StorehouseChestScheduleTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 27, 12, 49, 59);

    @Test
    void keepsAFutureChestCountdown() {
        LocalDateTime chestCountdown = NOW.plusHours(1).plusMinutes(1).plusSeconds(2);

        assertEquals(chestCountdown, StorehouseChestRoutine.nextChestVisit(NOW, chestCountdown));
    }

    @Test
    void retriesFiveMinutesWhenTheChestCountdownIsMissing() {
        assertEquals(NOW.plusMinutes(5), StorehouseChestRoutine.nextChestVisit(NOW, null));
    }

    @Test
    void retriesFiveMinutesWhenTheChestCountdownIsAlreadyPast() {
        assertEquals(NOW.plusMinutes(5), StorehouseChestRoutine.nextChestVisit(NOW, NOW.minusMinutes(1)));
    }

    @Test
    void keepsAChestCountdownThatIsExactlyNow() {
        assertEquals(NOW, StorehouseChestRoutine.nextChestVisit(NOW, NOW));
    }

    @Test
    void readsCooldownFromTheClaimActionSlot() {
        // Kevin live Warm Welcome icon @(46,822) with GO_HALF_HEIGHT=40.
        ImageSearchResultData icon = ImageSearchResultData.hit(46, 822, 99.0, 44, 44);

        AreaData timerArea = StorehouseChestRoutine.rowTimerArea(icon);

        assertEquals(SidebarNavigator.rowActionAreaFor(icon), timerArea);
        assertEquals(AreaData.of(361, 782, 440, 862), timerArea);
    }

    @Test
    void readsGreenStatusUnderTheRowTitle() {
        ImageSearchResultData icon = ImageSearchResultData.hit(46, 822, 99.0, 44, 44);

        assertEquals(AreaData.of(116, 826, 346, 858), StorehouseChestRoutine.rowStatusArea(icon));
    }

    @Test
    void treatsCompleteAndCompletedAsClaimReadyStatus() {
        assertTrue(StorehouseChestRoutine.isClaimReadyStatus("Complete"));
        assertTrue(StorehouseChestRoutine.isClaimReadyStatus("Completed"));
        assertTrue(StorehouseChestRoutine.isClaimReadyStatus("  COMPLETED "));
    }
}
