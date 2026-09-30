package dev.frostguard.tasks.economy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class StorehouseChestFrameTest {

    private static final PointData SCREEN_ORIGIN = new PointData(0, 0);
    private static final PointData SCREEN_LIMIT = new PointData(720, 1280);
    private static final double SEARCH_THRESHOLD = 90;

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded the native library in this JVM.
        }
    }

    @Test
    void detectsTheVisibleStaminaCan() throws IOException {
        assertTrue(matches(TemplatesEnum.STOREHOUSE_STAMINA));
    }

    @Test
    void rejectsBothChestTemplatesOnTheStaminaCan() throws IOException {
        assertFalse(matches(TemplatesEnum.STOREHOUSE_CHEST));
        assertFalse(matches(TemplatesEnum.STOREHOUSE_CHEST_2));
    }

    private boolean matches(TemplatesEnum template) throws IOException {
        try (InputStream stream = getClass().getResourceAsStream("/storehouse/city-can-visible.png")) {
            byte[] encoded = Objects.requireNonNull(stream, "Missing storehouse frame").readAllBytes();
            return OpenCvPatternLocator.locatePattern(
                    encoded, template, SCREEN_ORIGIN, SCREEN_LIMIT, SEARCH_THRESHOLD).isFound();
        }
    }
}
