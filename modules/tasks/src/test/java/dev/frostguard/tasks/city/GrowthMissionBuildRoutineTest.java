package dev.frostguard.tasks.city;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrowthMissionBuildRoutineTest {

    @Test
    void growthLabelAcceptsCommonOcrTruncations() {
        assertTrue(GrowthMissionBuildRoutine.looksLikeGrowthLabel("growth"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeGrowthLabel("growth missions"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeGrowthLabel("growt"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeGrowthLabel("grwth"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeGrowthLabel("growlh"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGrowthLabel("daily"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGrowthLabel("s"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGrowthLabel("eeee"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGrowthLabel(""));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGrowthLabel(null));
    }

    @Test
    void missionTabCanonicalizerMapsNearMissesOntoKnownWords() {
        assertEquals("chapter growth", GrowthMissionBuildRoutine.canonicalizeMissionTabOcr("chapler growlh"));
        assertEquals("daily growth", GrowthMissionBuildRoutine.canonicalizeMissionTabOcr("daily growth"));
        assertEquals("s", GrowthMissionBuildRoutine.canonicalizeMissionTabOcr("s"));
        assertEquals(Optional.of("growth"), GrowthMissionBuildRoutine.closestMissionTabLabel("growlh"));
        assertTrue(GrowthMissionBuildRoutine.closestMissionTabLabel("s").isEmpty());
    }

    @Test
    void claimLabelRequiresClaimToken() {
        assertTrue(GrowthMissionBuildRoutine.looksLikeClaimLabel("claim"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeClaimLabel("claim 3 rewards"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeClaimLabel("go"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeClaimLabel(null));
    }

    @Test
    void goLabelRejectsClaimAndAcceptsShortGoReads() {
        assertTrue(GrowthMissionBuildRoutine.looksLikeGoLabel("go"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeGoLabel("g0"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeGoLabel("tap go now"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGoLabel("claim"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGoLabel("claim go"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGoLabel("growth"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeGoLabel(null));
    }

    @Test
    void allCompleteStillRequiresMissionContext() {
        assertTrue(GrowthMissionBuildRoutine.looksLikeAllGrowthComplete("all growth missions completed"));
        assertTrue(GrowthMissionBuildRoutine.looksLikeAllGrowthComplete("come back later"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeAllGrowthComplete("completed"));
        assertFalse(GrowthMissionBuildRoutine.looksLikeAllGrowthComplete(""));
    }

    @Test
    void pickGrowthTabHitPrefersCleanGrowthWindowOverMixedNeighbor() {
        // Chapter|Growth two-tab layout: left window reads Chapter, right reads Growth.
        List<GrowthMissionBuildRoutine.TabOcrHit> hits = List.of(
                new GrowthMissionBuildRoutine.TabOcrHit(3, 223, "chapter"),
                new GrowthMissionBuildRoutine.TabOcrHit(200, 420, "chapter growth"),
                new GrowthMissionBuildRoutine.TabOcrHit(498, 718, "growth"));
        Optional<GrowthMissionBuildRoutine.TabOcrHit> best =
                GrowthMissionBuildRoutine.pickGrowthTabHit(hits);
        assertTrue(best.isPresent());
        assertEquals(498, best.get().left());
        assertEquals(718, best.get().right());
    }

    @Test
    void jitterAreaInsetsGrowthWindowAndClampsY() {
        GrowthMissionBuildRoutine.TabOcrHit hit =
                new GrowthMissionBuildRoutine.TabOcrHit(498, 718, "growth");
        var area = GrowthMissionBuildRoutine.jitterAreaForTabHit(hit);
        assertEquals(526, area.topLeft().getX());
        // 718-28=690, then clamped to SAFE_X_MAX 650
        assertEquals(650, area.bottomRight().getX());
        assertEquals(1116, area.topLeft().getY());
        assertEquals(1147, area.bottomRight().getY());
    }

    @Test
    void pickGrowthTabHitReturnsEmptyWhenOnlyDailyOrChapter() {
        List<GrowthMissionBuildRoutine.TabOcrHit> hits = List.of(
                new GrowthMissionBuildRoutine.TabOcrHit(3, 223, "daily"),
                new GrowthMissionBuildRoutine.TabOcrHit(498, 718, "chapter"));
        assertTrue(GrowthMissionBuildRoutine.pickGrowthTabHit(hits).isEmpty());
    }

    @Test
    void tabStripWordOrderPlacesGrowthOnRightForChapterGrowth() {
        var bar = new dev.frostguard.api.domain.AreaData(
                new dev.frostguard.api.domain.PointData(73, 1116),
                new dev.frostguard.api.domain.PointData(650, 1147));
        var area = GrowthMissionBuildRoutine.tapAreaFromTabStripText("chapter growth", bar);
        assertTrue(area.isPresent());
        // 2 slots → Growth is slot 1 → roughly right half
        assertTrue(area.get().topLeft().getX() > 300);
    }

    @Test
    void tabStripWordOrderPlacesGrowthInMiddleForThreeTabs() {
        var bar = new dev.frostguard.api.domain.AreaData(
                new dev.frostguard.api.domain.PointData(73, 1116),
                new dev.frostguard.api.domain.PointData(650, 1147));
        var area = GrowthMissionBuildRoutine.tapAreaFromTabStripText("daily growth chapter", bar);
        assertTrue(area.isPresent());
        assertTrue(area.get().topLeft().getX() > 150);
        assertTrue(area.get().bottomRight().getX() < 600);
    }
}
