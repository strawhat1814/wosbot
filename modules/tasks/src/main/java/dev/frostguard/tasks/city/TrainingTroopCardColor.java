package dev.frostguard.tasks.city;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.vision.color.PixelStats;

import java.awt.image.BufferedImage;

final class TrainingTroopCardColor {

    static final int SAMPLE_RADIUS = 38;
    static final int MIN_VIVID_PIXELS = 350;

    private TrainingTroopCardColor() {}

    static boolean isUnlockedCard(BufferedImage image, PointData center) {
        if (image == null || center == null) {
            return false;
        }
        AreaData area = sampleArea(center, image.getWidth(), image.getHeight());
        return PixelStats.count(image, area, TrainingTroopCardColor::isVividTroopPixel) >= MIN_VIVID_PIXELS;
    }

    static int vividPixelCount(BufferedImage image, PointData center) {
        if (image == null || center == null) {
            return 0;
        }
        AreaData area = sampleArea(center, image.getWidth(), image.getHeight());
        return PixelStats.count(image, area, TrainingTroopCardColor::isVividTroopPixel);
    }

    static boolean isVividTroopPixel(int rgb) {
        int red = (rgb >> 16) & 0xFF;
        int green = (rgb >> 8) & 0xFF;
        int blue = rgb & 0xFF;
        int brightest = Math.max(red, Math.max(green, blue));
        int darkest = Math.min(red, Math.min(green, blue));
        if (brightest - darkest < 25 || brightest < 50) {
            return false;
        }
        return blue < red + 10 || blue < green || red >= 110;
    }

    private static AreaData sampleArea(PointData center, int imageWidth, int imageHeight) {
        int x0 = Math.max(0, center.getX() - SAMPLE_RADIUS);
        int y0 = Math.max(0, center.getY() - SAMPLE_RADIUS);
        int x1 = Math.min(imageWidth - 1, center.getX() + SAMPLE_RADIUS);
        int y1 = Math.min(imageHeight - 1, center.getY() + SAMPLE_RADIUS);
        return new AreaData(new PointData(x0, y0), new PointData(x1, y1));
    }
}
