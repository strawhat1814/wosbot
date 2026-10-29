package dev.frostguard.engine.helper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.image.BufferedImage;
import java.util.Objects;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.vision.ocr.OcrEngine;

class StaminaHudOcrFrameTest {

    @Test
    void cityBadgeReadsTheVisibleStamina() throws Exception {
        assertEquals("224", readHud("/stamina/hud-city.png"));
    }

    @Test
    void worldBadgeReadsTheVisibleStamina() throws Exception {
        assertEquals("224", readHud("/stamina/hud-world.png"));
    }

    private String readHud(String resource) throws Exception {
        RawImageData frame = rgbaFrame(ImageIO.read(Objects.requireNonNull(getClass().getResourceAsStream(resource))));
        return OcrEngine.recognizeText(
                frame,
                CommonGameAreas.STAMINA_HUD_OCR_AREA.topLeft(),
                CommonGameAreas.STAMINA_HUD_OCR_AREA.bottomRight(),
                CommonOCRSettings.STAMINA_HUD_SETTINGS).trim();
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
