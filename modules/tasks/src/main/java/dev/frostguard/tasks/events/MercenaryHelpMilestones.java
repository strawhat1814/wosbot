package dev.frostguard.tasks.events;

import java.util.Set;

/**
 * Mercenary levels that request alliance help instead of a solo Attack march.
 * Level {@link #FINAL_LEVEL} is the last hunt; after Request Help there, one
 * verify visit may run, then the routine rests until daily reset.
 */
public final class MercenaryHelpMilestones {

    public static final int FINAL_LEVEL = 25;
    private static final int MAX_PLAUSIBLE_LEVEL = FINAL_LEVEL;
    private static final Set<Integer> HELP_LEVELS = Set.of(5, 10, 15, 20, FINAL_LEVEL);

    private MercenaryHelpMilestones() {}

    public static boolean requiresAskForHelp(Integer level) {
        return level != null && HELP_LEVELS.contains(level);
    }

    public static boolean isFinalLevel(Integer level) {
        return level != null && level >= FINAL_LEVEL;
    }

    /**
     * Rejects truncated OCR such as reading {@code 25} as {@code 2} when a recent
     * level was already in the teens/twenties. Returns {@code null} so the caller
     * can retry a wider band instead of attacking the wrong milestone.
     */
    public static Integer sanitizeLevelRead(Integer raw, Integer previousLevel) {
        if (raw == null) {
            return null;
        }
        if (raw < 1 || raw > MAX_PLAUSIBLE_LEVEL) {
            return null;
        }
        if (previousLevel != null && previousLevel >= 10 && raw < 10 && previousLevel - raw >= 8) {
            return null;
        }
        return raw;
    }
}
