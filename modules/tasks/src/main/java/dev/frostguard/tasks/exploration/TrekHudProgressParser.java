package dev.frostguard.tasks.exploration;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses City/World HUD trek {@code current/max} OCR (progress, not remaining).
 */
public final class TrekHudProgressParser {

    private static final Pattern FRACTION = Pattern.compile("(\\d{1,4})\\s*/\\s*(\\d{1,4})");

    private TrekHudProgressParser() {}

    public static TrekHudProgress parse(String rawOcr) {
        if (rawOcr == null || rawOcr.isBlank()) {
            return null;
        }

        TrekHudProgress fromRaw = extract(rawOcr);
        if (fromRaw != null) {
            return fromRaw;
        }

        String slashFixed = rawOcr
                .replace(':', '/')
                .replace(';', '/')
                .replace('|', '/')
                .replace('\\', '/');
        TrekHudProgress fromFixed = extract(slashFixed);
        if (fromFixed != null) {
            return fromFixed;
        }

        return extract(normalize(rawOcr));
    }

    private static TrekHudProgress extract(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = FRACTION.matcher(text);
        TrekHudProgress best = null;
        while (matcher.find()) {
            try {
                int current = Integer.parseInt(matcher.group(1));
                int max = Integer.parseInt(matcher.group(2));
                if (max <= 0) {
                    continue;
                }
                // Reject obvious OCR nonsense (e.g. 12/3) while allowing any real trek max.
                if (current < 0 || current > max + 5) {
                    continue;
                }
                // Prefer higher current when several fractions appear (HUD over noise).
                if (best == null || current > best.current()) {
                    best = new TrekHudProgress(Math.min(current, max), max);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private static String normalize(String text) {
        return text
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replaceAll("\\s+", "")
                .trim();
    }
}
