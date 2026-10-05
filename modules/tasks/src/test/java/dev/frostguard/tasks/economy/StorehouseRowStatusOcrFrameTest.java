package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.helper.SidebarNavigator;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.vision.match.OpenCvPatternLocator;
import dev.frostguard.vision.ocr.OcrEngine;

/**
 * Kevin live My Rewards rows (2026-10-01): Online Rewards / Warm Welcome show green
 * Complete status. Claim checkmark needs the taller action box (half-height 40).
 */
class StorehouseRowStatusOcrFrameTest {

    private static final PointData ICON_ORIGIN = CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.topLeft();
    private static final PointData ICON_LIMIT = CommonGameAreas.SIDEBAR_ROW_ICON_COLUMN.bottomRight();
    private static final int MATCH_THRESHOLD = 88;

    @BeforeAll
    static void loadOpenCv() throws Exception {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Already loaded.
        }
    }

    @Test
    void readsCompletedStatusOnOnlineRewardsAndWarmWelcome() throws Exception {
        BufferedImage image = loadFrame();
        RawImageData frame = rgbaFrame(image);
        byte[] encoded = pngBytes();

        ImageSearchResultData online = locate(encoded, TemplatesEnum.SIDEBAR_DAILY_ONLINE_REWARDS);
        ImageSearchResultData warm = locate(encoded, TemplatesEnum.SIDEBAR_DAILY_WARM_WELCOME);
        assertTrue(online.isFound(), "Online Rewards icon");
        assertTrue(warm.isFound(), "Warm Welcome icon");

        String onlineStatus = OcrEngine.recognizeText(
                frame,
                StorehouseChestRoutine.rowStatusArea(online).topLeft(),
                StorehouseChestRoutine.rowStatusArea(online).bottomRight(),
                StorehouseChestRoutine.ROW_STATUS_OCR_SETTINGS).trim();
        String warmStatus = OcrEngine.recognizeText(
                frame,
                StorehouseChestRoutine.rowStatusArea(warm).topLeft(),
                StorehouseChestRoutine.rowStatusArea(warm).bottomRight(),
                StorehouseChestRoutine.ROW_STATUS_OCR_SETTINGS).trim();

        assertTrue(StorehouseChestRoutine.isClaimReadyStatus(onlineStatus),
                () -> "Online status OCR: '" + onlineStatus + "'");
        assertTrue(StorehouseChestRoutine.isClaimReadyStatus(warmStatus),
                () -> "Warm Welcome status OCR: '" + warmStatus + "'");
    }

    @Test
    void matchesClaimCheckmarkInsideTheTallerActionBox() throws Exception {
        byte[] encoded = pngBytes();
        ImageSearchResultData warm = locate(encoded, TemplatesEnum.SIDEBAR_DAILY_WARM_WELCOME);
        assertTrue(warm.isFound());

        AreaData action = SidebarNavigator.rowActionAreaFor(warm);
        ImageSearchResultData claim = OpenCvPatternLocator.locatePattern(
                encoded,
                TemplatesEnum.SIDEBAR_CLAIM_ACTION,
                action.topLeft(),
                action.bottomRight(),
                MATCH_THRESHOLD);
        assertTrue(claim.isFound(), "Claim checkmark must fit the action box used by navigation");
        assertEquals(40, (action.bottomRight().getY() - action.topLeft().getY()) / 2);
    }

    private ImageSearchResultData locate(byte[] encoded, TemplatesEnum icon) {
        return OpenCvPatternLocator.locatePattern(
                encoded, icon, ICON_ORIGIN, ICON_LIMIT, MATCH_THRESHOLD);
    }

    private BufferedImage loadFrame() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/storehouse/daily-my-rewards-completed-20261001.png")) {
            return ImageIO.read(Objects.requireNonNull(stream, "Missing My Rewards frame"));
        }
    }

    private byte[] pngBytes() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(
                "/storehouse/daily-my-rewards-completed-20261001.png")) {
            return Objects.requireNonNull(stream).readAllBytes();
        }
    }

    private RawImageData rgbaFrame(BufferedImage image) {
        byte[] rgba = new byte[image.getWidth() * image.getHeight() * 4];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                rgba[offset++] = (byte) ((rgb >> 16) & 0xFF);
                rgba[offset++] = (byte) ((rgb >> 8) & 0xFF);
                rgba[offset++] = (byte) (rgb & 0xFF);
                rgba[offset++] = (byte) 0xFF;
            }
        }
        return RawImageData.capture(rgba, image.getWidth(), image.getHeight(), 32);
    }
}
