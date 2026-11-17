package dev.frostguard.engine.helper;

/**
 * HUD stamina OCR: raw glyphs drop a slender leading {@code 1}, while white
 * isolation can split thicker digits such as {@code 2}. Both reads are kept and
 * the fuller digit string wins when one is a truncated suffix of the other.
 */
final class StaminaHudOcr {

    private StaminaHudOcr() {}

    /** Digits only; spaces from a split glyph become concatenation ({@code "2 4"} → {@code "24"}). */
    static String digitsOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.replaceAll("\\D+", "");
    }

    static String preferFullerDigits(String first, String second) {
        String a = digitsOnly(first);
        String b = digitsOnly(second);
        if (a.isEmpty()) {
            return b.isEmpty() ? null : b;
        }
        if (b.isEmpty() || a.equals(b)) {
            return a;
        }
        if (a.endsWith(b) && a.length() > b.length()) {
            return a;
        }
        if (b.endsWith(a) && b.length() > a.length()) {
            return b;
        }
        return a.length() >= b.length() ? a : b;
    }

    static Integer preferFuller(Integer first, Integer second) {
        String picked = preferFullerDigits(
                first == null ? null : Integer.toString(first),
                second == null ? null : Integer.toString(second));
        if (picked == null || picked.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(picked);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
