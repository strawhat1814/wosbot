package dev.frostguard.engine.emulator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Per-device probe outcomes from one coordinated ADB recovery.
 * Queue resume uses {@link #anyHealthy()}; callers may still report each device as it probes.
 */
public final class AdbRecoveryReport {

    private static final AdbRecoveryReport EMPTY =
            new AdbRecoveryReport(Collections.emptyMap());

    private final Map<String, Boolean> deviceOk;

    public AdbRecoveryReport(Map<String, Boolean> deviceOk) {
        Objects.requireNonNull(deviceOk, "deviceOk");
        this.deviceOk = Collections.unmodifiableMap(new LinkedHashMap<>(deviceOk));
    }

    public static AdbRecoveryReport empty() {
        return EMPTY;
    }

    public Map<String, Boolean> deviceOk() {
        return deviceOk;
    }

    public boolean allHealthy() {
        return !deviceOk.isEmpty() && deviceOk.values().stream().allMatch(Boolean.TRUE::equals);
    }

    public boolean anyHealthy() {
        return deviceOk.values().stream().anyMatch(Boolean.TRUE::equals);
    }

    public int okCount() {
        int n = 0;
        for (Boolean ok : deviceOk.values()) {
            if (Boolean.TRUE.equals(ok)) {
                n++;
            }
        }
        return n;
    }

    public int totalCount() {
        return deviceOk.size();
    }
}
