package dev.frostguard.engine.helper;

/**
 * HUD stamina OCR: raw glyphs drop a slender leading {@code 1}, while white
 * isolation can split thicker digits such as {@code 2}. Both OCR strings are
 * kept (before integer parsing) and merged into the fullest digit reading.
 */
final class StaminaHudOcr {

    private StaminaHudOcr() {}

    /** Digits only; spaces from a split glyph become concatenation ({@code "1 41"} → {@code "141"}). */
    static String digitsOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("\\D+", "");
    }

    static boolean hasDigits(String raw) {
        return !digitsOnly(raw).isEmpty();
    }

    /**
     * Prefer the fuller digit string. {@code first} is the isolated read, {@code second} the raw read.
     * Handles suffix truncation ({@code 142}/{@code 42}), split glyphs already joined by
     * {@link #digitsOnly}, and a lone leading {@code 1} plus remainder ({@code "1"}+{@code "41"} → {@code "141"}).
     */
    static String preferFullerDigits(String first, String second) {
        String isolated = digitsOnly(first);
        String raw = digitsOnly(second);
        if (isolated.isEmpty()) {
            return raw.isEmpty() ? null : raw;
        }
        if (raw.isEmpty() || isolated.equals(raw)) {
            return isolated;
        }
        if (isolated.endsWith(raw) && isolated.length() > raw.length()) {
            return isolated;
        }
        // Isolated saw only the slender leading 1; raw saw the remainder (41 from 141).
        // Must run before raw.endsWith("1"), which would wrongly keep 41.
        if (isolated.equals("1") && raw.length() >= 2 && raw.length() <= 3) {
            return isolated + raw;
        }
        if (raw.endsWith(isolated) && raw.length() > isolated.length()) {
            return raw;
        }
        return isolated.length() >= raw.length() ? isolated : raw;
    }

    static Integer parseDigits(String digits) {
        if (digits == null || digits.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static Integer preferFuller(Integer isolated, Integer raw) {
        return parseDigits(preferFullerDigits(
                isolated == null ? null : Integer.toString(isolated),
                raw == null ? null : Integer.toString(raw)));
    }
}
