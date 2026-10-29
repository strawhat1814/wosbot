package dev.frostguard.api.configs;

/**
 * Operator-facing Bear Trap automation mode.
 *
 * <p>{@link #A} keeps the legacy single long-running task.
 * {@link #B} splits Own Rally and Join into short resumable tasks.</p>
 */
public enum BearTrapMode {
    A("A"),
    B("B");

    private final String storedValue;

    BearTrapMode(String storedValue) {
        this.storedValue = storedValue;
    }

    public String getStoredValue() {
        return storedValue;
    }

    public static BearTrapMode fromStoredValue(String value) {
        if (value == null || value.isBlank()) {
            return A;
        }
        String trimmed = value.trim();
        if (B.storedValue.equalsIgnoreCase(trimmed) || "mode b".equalsIgnoreCase(trimmed)) {
            return B;
        }
        return A;
    }

    public static boolean isModeB(String value) {
        return fromStoredValue(value) == B;
    }

    @Override
    public String toString() {
        return storedValue;
    }
}
