package dev.frostguard.tasks.combat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class PolarSpecialRewardsBudgetTest {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final LocalDateTime CHECKED_AT = LocalDateTime.of(2026, 10, 17, 10, 0);

    @AfterEach
    void clearBudgets() {
        PolarSpecialRewardsBudget.clear();
    }

    @Test
    void missingBudgetOpensTheRewardPanel() {
        PolarSpecialRewardsBudget.Decision decision = PolarSpecialRewardsBudget.decide(
                null, CHECKED_AT, UTC);

        assertEquals(PolarSpecialRewardsBudget.Action.INSPECT, decision.action());
    }

    @Test
    void clearProfileDropsStaleQuotaSoNextDecideInspects() {
        PolarSpecialRewardsBudget.recordInspection(8L, 9, CHECKED_AT);
        PolarSpecialRewardsBudget.clearProfile(8L);

        PolarSpecialRewardsBudget.Decision decision = PolarSpecialRewardsBudget.decide(
                8L, CHECKED_AT.plusMinutes(5));

        assertEquals(PolarSpecialRewardsBudget.Action.INSPECT, decision.action());
    }

    @Test
    void freshQuotaHuntsWithoutAnotherRewardCheck() {
        PolarSpecialRewardsBudget.Snapshot snapshot = new PolarSpecialRewardsBudget.Snapshot(
                7, 10, CHECKED_AT, false);

        PolarSpecialRewardsBudget.Decision decision = PolarSpecialRewardsBudget.decide(
                snapshot, CHECKED_AT.plusMinutes(20), UTC);

        assertEquals(PolarSpecialRewardsBudget.Action.HUNT, decision.action());
        assertEquals(7, decision.remaining());
        assertEquals(3, decision.launchedSinceCheck());
        assertNull(decision.resumeAt());
    }

    @Test
    void spentQuotaWaitsUntilTheHourlyRecheck() {
        PolarSpecialRewardsBudget.Snapshot snapshot = new PolarSpecialRewardsBudget.Snapshot(
                0, 10, CHECKED_AT, false);

        PolarSpecialRewardsBudget.Decision decision = PolarSpecialRewardsBudget.decide(
                snapshot, CHECKED_AT.plusMinutes(59).plusSeconds(59), UTC);

        assertEquals(PolarSpecialRewardsBudget.Action.WAIT, decision.action());
        assertEquals(CHECKED_AT.plusHours(1), decision.resumeAt());
    }

    @Test
    void hourElapsedReplacesTheQuotaEvenWhenRalliesRemain() {
        PolarSpecialRewardsBudget.Snapshot snapshot = new PolarSpecialRewardsBudget.Snapshot(
                4, 10, CHECKED_AT, false);

        PolarSpecialRewardsBudget.Decision decision = PolarSpecialRewardsBudget.decide(
                snapshot, CHECKED_AT.plusHours(1), UTC);

        assertEquals(PolarSpecialRewardsBudget.Action.INSPECT, decision.action());
    }

    @Test
    void confirmedZeroStopsUntilTheNextRewardDay() {
        PolarSpecialRewardsBudget.Snapshot snapshot = new PolarSpecialRewardsBudget.Snapshot(
                0, 0, CHECKED_AT, true);

        PolarSpecialRewardsBudget.Decision sameDay = PolarSpecialRewardsBudget.decide(
                snapshot, CHECKED_AT.plusHours(5), UTC);
        PolarSpecialRewardsBudget.Decision nextDay = PolarSpecialRewardsBudget.decide(
                snapshot, CHECKED_AT.plusDays(1), UTC);

        assertEquals(PolarSpecialRewardsBudget.Action.STOP_FOR_DAY, sameDay.action());
        assertEquals(PolarSpecialRewardsBudget.Action.INSPECT, nextDay.action());
    }

    @Test
    void rewardResetInvalidatesAFreshQuota() {
        ZoneOffset zone = ZoneOffset.ofHours(3);
        LocalDateTime checkedAt = LocalDateTime.of(2026, 10, 17, 2, 30);
        PolarSpecialRewardsBudget.Snapshot snapshot = new PolarSpecialRewardsBudget.Snapshot(
                4, 10, checkedAt, false);

        PolarSpecialRewardsBudget.Decision decision = PolarSpecialRewardsBudget.decide(
                snapshot, LocalDateTime.of(2026, 10, 17, 3, 10), zone);

        assertEquals(PolarSpecialRewardsBudget.Action.INSPECT, decision.action());
    }

    @Test
    void inspectionReplacesTheQuotaAndEachConfirmedRallySpendsOne() {
        long profileId = 42L;
        LocalDateTime firstCheck = CHECKED_AT;
        PolarSpecialRewardsBudget.recordInspection(profileId, 10, firstCheck);
        PolarSpecialRewardsBudget.Snapshot afterThree = null;
        for (int rally = 0; rally < 3; rally++) {
            afterThree = PolarSpecialRewardsBudget.noteSuccessfulRally(profileId);
        }

        assertEquals(7, afterThree.remaining());
        assertEquals(3, afterThree.launched());
        assertEquals(PolarSpecialRewardsBudget.Action.HUNT,
                PolarSpecialRewardsBudget.decide(profileId, firstCheck.plusMinutes(30)).action());

        PolarSpecialRewardsBudget.recordInspection(profileId, 3, firstCheck.plusHours(1));
        PolarSpecialRewardsBudget.Snapshot recounted = PolarSpecialRewardsBudget.noteSuccessfulRally(profileId);

        assertEquals(2, recounted.remaining());
        assertEquals(1, recounted.launched());
        assertEquals(3, recounted.quota());
    }

    @Test
    void failedOrExtraRallyDoesNotDriveTheQuotaNegative() {
        long profileId = 7L;
        PolarSpecialRewardsBudget.recordInspection(profileId, 1, CHECKED_AT);
        PolarSpecialRewardsBudget.noteSuccessfulRally(profileId);

        PolarSpecialRewardsBudget.Snapshot extra = PolarSpecialRewardsBudget.noteSuccessfulRally(profileId);

        assertEquals(0, extra.remaining());
        assertEquals(PolarSpecialRewardsBudget.Action.WAIT,
                PolarSpecialRewardsBudget.decide(profileId, CHECKED_AT.plusMinutes(5)).action());
        assertEquals(CHECKED_AT.plusHours(1),
                PolarSpecialRewardsBudget.decide(profileId, CHECKED_AT.plusMinutes(5)).resumeAt());
    }

    @Test
    void confirmedExhaustionIsNotSpentByALaterRally() {
        long profileId = 9L;
        PolarSpecialRewardsBudget.recordConfirmedExhausted(profileId, CHECKED_AT);

        PolarSpecialRewardsBudget.Snapshot unchanged = PolarSpecialRewardsBudget.noteSuccessfulRally(profileId);

        assertEquals(0, unchanged.remaining());
        assertEquals(true, unchanged.confirmedExhausted());
        assertEquals(PolarSpecialRewardsBudget.Action.STOP_FOR_DAY,
                PolarSpecialRewardsBudget.decide(profileId, CHECKED_AT.plusMinutes(10)).action());
    }

    @Test
    void rallyBeforeAnyReadDoesNotInventAQuota() {
        assertNull(PolarSpecialRewardsBudget.noteSuccessfulRally(99L));
    }
}
