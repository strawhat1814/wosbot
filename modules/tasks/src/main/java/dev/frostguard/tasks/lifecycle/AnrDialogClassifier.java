package dev.frostguard.tasks.lifecycle;

import java.util.Locale;

/**
 * Detects the Android system ANR dialog:
 * {@code Whiteout Survival isn't responding} with Close app / Wait.
 */
final class AnrDialogClassifier {

    private AnrDialogClassifier() {
    }

    static Evidence inspect(String ocrText) {
        String normalized = normalize(ocrText);
        boolean responding = normalized.contains("responding")
                || normalized.contains("isnt responding")
                || normalized.contains("isn t responding")
                || normalized.contains("not responding");
        boolean wait = containsWord(normalized, "wait");
        boolean closeApp = normalized.contains("close app") || containsWord(normalized, "close");
        boolean detected = responding && (wait || closeApp);
        return new Evidence(detected, normalized);
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace('\'', ' ')
                .replaceAll("[^a-z]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private static boolean containsWord(String text, String word) {
        int index = text.indexOf(word);
        while (index >= 0) {
            int end = index + word.length();
            boolean beforeBoundary = index == 0 || text.charAt(index - 1) == ' ';
            boolean afterBoundary = end == text.length() || text.charAt(end) == ' ';
            if (beforeBoundary && afterBoundary) {
                return true;
            }
            index = text.indexOf(word, end);
        }
        return false;
    }

    record Evidence(boolean detected, String normalizedText) {
    }
}
