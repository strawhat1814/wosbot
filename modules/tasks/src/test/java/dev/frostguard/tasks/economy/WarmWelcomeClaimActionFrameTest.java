package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.SidebarNavigator;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;

/**
 * Live My Rewards Warm Welcome row (2026-10-17): green Claim checkmark needs the
 * taller action box (half-height 40). Half-height 30 drops below the 88 threshold.
 */
class WarmWelcomeClaimActionFrameTest {

    private static final PointData ICON_ORIGIN = CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft();
    private static final PointData ICON_LIMIT = CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight();

    @BeforeAll
    static void loadOpenCv() throws Exception {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Already loaded.
        }
    }

    @Test
    void matchesClaimCheckmarkInsideTheTallerActionBox() throws Exception {
        byte[] frame = pngBytes();
        ImageSearchResultData warm = OpenCvPatternLocator.locatePattern(
                frame,
                TemplatesEnum.SIDEBAR_DAILY_WARM_WELCOME,
                ICON_ORIGIN,
                ICON_LIMIT,
                88);
        assertTrue(warm.isFound(), "Warm Welcome icon");

        AreaData action = SidebarNavigator.rowActionAreaFor(warm);
        ImageSearchResultData claim = OpenCvPatternLocator.locatePattern(
                frame,
                TemplatesEnum.SIDEBAR_CLAIM_ACTION,
                action.topLeft(),
                action.bottomRight(),
                88);
        assertTrue(claim.isFound(),
                () -> "Claim checkmark must match in action box " + action);
        assertTrue(action.bottomRight().getY() - action.topLeft().getY() >= 80,
                "Action box must be at least 80px tall (half-height 40)");
    }

    private byte[] pngBytes() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/storehouse/daily-warm-welcome-claim-ready-20261017.png")) {
            return Objects.requireNonNull(stream, "Missing Warm Welcome claim-ready frame")
                    .readAllBytes();
        }
    }
}
