package dev.frostguard.tasks.events;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Objects;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.match.OpenCvPatternLocator;

class MercenaryAskHelpEvidenceTest {

    @BeforeAll
    static void loadOpenCv() throws IOException {
        try {
            OpenCvPatternLocator.loadNativeLibrary();
        } catch (UnsatisfiedLinkError ignored) {
            // Another frame test may already have loaded OpenCV in this JVM.
        }
    }

    @Test
    void detectsRequestHelpOnMercPopupBand() throws IOException {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                resource("/mercenary/request-help-button-band.png"),
                TemplatesEnum.MERCENARY_ASK_HELP_BUTTON,
                new PointData(0, 0),
                new PointData(439, 69),
                70);

        assertTrue(result.isFound(), "Request Help should be detected: " + result);
        assertTrue(result.getMatchScore() >= 70, "Request Help score too low: " + result);
    }

    @Test
    void detectsConfirmOnTipsDialogBand() throws IOException {
        ImageSearchResultData result = OpenCvPatternLocator.locatePattern(
                resource("/mercenary/ask-help-confirm-band.png"),
                TemplatesEnum.MERCENARY_ASK_HELP_CONFIRM,
                new PointData(0, 0),
                new PointData(259, 89),
                70);

        assertTrue(result.isFound(), "Confirm should be detected: " + result);
        assertTrue(result.getMatchScore() >= 70, "Confirm score too low: " + result);
    }

    private static byte[] resource(String path) throws IOException {
        try (var stream = MercenaryAskHelpEvidenceTest.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(stream, "Missing test resource: " + path).readAllBytes();
        }
    }
}

