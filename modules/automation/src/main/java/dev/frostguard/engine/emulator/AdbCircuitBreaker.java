package dev.frostguard.engine.emulator;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared ADB failure circuit: after a burst of host ADB failures, pause all
 * queues, reboot the bridge once, reconnect every device, then resume.
 * Coalesces concurrent recovery requests so TaskQueues and GlobalMonitor cannot
 * race {@code restartAdb()} into a null-bridge storm.
 *
 * <p>When the host {@code adb.exe} itself is dead, enters {@link State#SUSPENDED}
 * after a bounded number of kill-server attempts, keeps queues paused, and
 * notifies the operator (Telegram) once — no multi-hour recovery thrash.
 */
public final class AdbCircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        COOLDOWN,
        /** Host ADB dead — queues stay paused; only {@link #recoverNow} clears this. */
        SUSPENDED
    }

    @FunctionalInterface
    public interface AdbRecoveryActions {
        /**
         * Kill the host adb server, recreate the ddmlib bridge, reconnect and
         * probe every provided device index. Invoke {@code onDeviceProbed} after
         * each probe so operators can get live per-device status. Recovery is
         * healthy when {@link AdbRecoveryReport#anyHealthy()} is true.
         */
        AdbRecoveryReport recover(Collection<String> deviceIndexes,
                BiConsumer<String, Boolean> onDeviceProbed) throws InterruptedException;
    }

    private static final Logger LOG = LoggerFactory.getLogger(AdbCircuitBreaker.class);

    static final int FAILURE_THRESHOLD = 6;
    static final long FAILURE_WINDOW_MS = 60_000L;
    static final long COOLDOWN_MS = 90_000L;
    static final long DRAIN_MS = 1_500L;
    /** Kill-server attempts while host ADB looks dead, then SUSPENDED. */
    static final int MAX_HOST_DEAD_RECOVERIES = 2;

    private final ReentrantLock recoveryLock = new ReentrantLock();
    private final Object failureLock = new Object();
    private final Deque<Long> recentFailures = new ArrayDeque<>();

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private volatile long cooldownUntilMs;
    private volatile String lastOpenReason = "";
    /** True only on the thread running {@link #runRecoveryLocked} so reconnect/probe can proceed while OPEN. */
    private final ThreadLocal<Boolean> recoveryThread = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private volatile Runnable pauseEngine = () -> {};
    private volatile Runnable resumeEngine = () -> {};
    private volatile Supplier<Collection<String>> deviceIndexes = Collections::emptyList;
    private volatile AdbRecoveryActions recoveryActions =
            (indexes, onDeviceProbed) -> AdbRecoveryReport.empty();
    private volatile Supplier<String> adbPath = () -> "";
    private volatile Consumer<String> operatorAlert = message -> {};
    /** Live per-device probe callback for the in-flight recovery (Telegram /adbreboot). */
    private volatile BiConsumer<String, Boolean> deviceProbeListener = (index, ok) -> {};
    /** Overridable in tests so recovery coalescing does not sleep the real drain. */
    private volatile long drainMillis = DRAIN_MS;
    /** Overridable in tests to simulate a dead host adb.exe without spawning processes. */
    private volatile Supplier<HostAdbHealthSnapshot.Snapshot> hostProbeOverride;
    private volatile int consecutiveFailedRecoveries;
    private volatile boolean hostDeadAlertSent;
    private volatile AdbRecoveryReport lastRecoveryReport = AdbRecoveryReport.empty();

    public void configure(Runnable pauseEngine,
            Runnable resumeEngine,
            Supplier<Collection<String>> deviceIndexes,
            AdbRecoveryActions recoveryActions) {
        configure(pauseEngine, resumeEngine, deviceIndexes, recoveryActions, () -> "", message -> {});
    }

    public void configure(Runnable pauseEngine,
            Runnable resumeEngine,
            Supplier<Collection<String>> deviceIndexes,
            AdbRecoveryActions recoveryActions,
            Supplier<String> adbPath) {
        configure(pauseEngine, resumeEngine, deviceIndexes, recoveryActions, adbPath, message -> {});
    }

    public void configure(Runnable pauseEngine,
            Runnable resumeEngine,
            Supplier<Collection<String>> deviceIndexes,
            AdbRecoveryActions recoveryActions,
            Supplier<String> adbPath,
            Consumer<String> operatorAlert) {
        this.pauseEngine = Objects.requireNonNullElse(pauseEngine, () -> {});
        this.resumeEngine = Objects.requireNonNullElse(resumeEngine, () -> {});
        this.deviceIndexes = Objects.requireNonNullElse(deviceIndexes, Collections::emptyList);
        this.recoveryActions = Objects.requireNonNullElse(
                recoveryActions, (indexes, onDeviceProbed) -> AdbRecoveryReport.empty());
        this.adbPath = Objects.requireNonNullElse(adbPath, () -> "");
        this.operatorAlert = Objects.requireNonNullElse(operatorAlert, message -> {});
    }

    /** Last coordinated recovery probe map (emulator index → healthy). */
    public AdbRecoveryReport lastRecoveryReport() {
        return lastRecoveryReport;
    }

    public State state() {
        State current = state.get();
        if (current == State.COOLDOWN && System.currentTimeMillis() >= cooldownUntilMs) {
            if (state.compareAndSet(State.COOLDOWN, State.CLOSED)) {
                LOG.info("ADB circuit breaker cooldown elapsed — CLOSED");
                clearFailures();
            }
            return state.get();
        }
        return current;
    }

    public boolean isCaptureAllowed() {
        State current = state();
        return current == State.CLOSED;
    }

    /**
     * Screenshots stay gated by {@link #isCaptureAllowed()}. Connect/reconnect
     * must still work on the recovery thread while state is OPEN.
     */
    public boolean isTransportAllowed() {
        return isCaptureAllowed() || Boolean.TRUE.equals(recoveryThread.get());
    }

    public String lastOpenReason() {
        return lastOpenReason;
    }

    public boolean isHostAdbSuspended() {
        return state() == State.SUSPENDED;
    }

    /**
     * Record an ADB-related failure. Opens coordinated recovery when the
     * rolling window reaches {@link #FAILURE_THRESHOLD}, or immediately for
     * bridge-null / connection-reset style signals.
     */
    public void recordFailure(String detail) {
        if (state() == State.SUSPENDED) {
            return;
        }
        String text = detail == null ? "" : detail;
        long now = System.currentTimeMillis();
        boolean immediate = looksLikeHardBridgeFailure(text);
        int count;
        synchronized (failureLock) {
            pruneFailures(now);
            recentFailures.addLast(now);
            count = recentFailures.size();
        }
        if (immediate || count >= FAILURE_THRESHOLD) {
            String reason = immediate
                    ? "hard ADB failure: " + abbreviate(text)
                    : "ADB failure burst (" + count + " in " + (FAILURE_WINDOW_MS / 1000) + "s)";
            requestRecovery(reason);
        }
    }

    public void recordSuccess() {
        if (state() == State.CLOSED) {
            clearFailures();
        }
    }

    /**
     * Request coordinated recovery. Concurrent callers coalesce onto one
     * recovery; late callers wait for it to finish.
     *
     * @return true when ADB is healthy afterward (CLOSED)
     */
    public boolean requestRecovery(String reason) {
        if (state() == State.SUSPENDED) {
            LOG.warn("ADB circuit breaker SUSPENDED (host ADB dead) — ignoring recovery request ({}); "
                            + "use Telegram /adbreboot after fixing host adb.exe",
                    abbreviate(reason));
            return false;
        }
        if (recoveryLock.tryLock()) {
            try {
                return runRecoveryLocked(reason, false);
            } finally {
                recoveryLock.unlock();
            }
        }
        LOG.info("ADB recovery already in progress — waiting ({})", abbreviate(reason));
        recoveryLock.lock();
        try {
            return state() == State.CLOSED;
        } finally {
            recoveryLock.unlock();
        }
    }

    /** Manual trigger (Telegram /adbreboot). Bypasses cooldown and SUSPENDED. */
    public boolean recoverNow(String reason) {
        return recoverNow(reason, null);
    }

    /**
     * Manual trigger with a live per-device probe callback (emulator index, healthy).
     * Invoked once per device as soon as that device's probe finishes.
     */
    public boolean recoverNow(String reason, BiConsumer<String, Boolean> onDeviceProbed) {
        recoveryLock.lock();
        try {
            if (state.get() == State.COOLDOWN || state.get() == State.SUSPENDED) {
                cooldownUntilMs = 0L;
                state.set(State.CLOSED);
            }
            hostDeadAlertSent = false;
            consecutiveFailedRecoveries = 0;
            deviceProbeListener = onDeviceProbed == null ? (index, ok) -> {} : onDeviceProbed;
            try {
                return runRecoveryLocked(
                        reason == null || reason.isBlank() ? "manual ADB reboot" : reason,
                        true);
            } finally {
                deviceProbeListener = (index, ok) -> {};
            }
        } finally {
            recoveryLock.unlock();
        }
    }

    /** Test hook: skip real drain sleeps when asserting coalescing. */
    void overrideDrainMillisForTest(long millis) {
        this.drainMillis = Math.max(0L, millis);
    }

    /** Test hook: inject host ADB health (null restores real probe). */
    void overrideHostProbeForTest(Supplier<HostAdbHealthSnapshot.Snapshot> probe) {
        this.hostProbeOverride = probe;
    }

    private boolean runRecoveryLocked(String reason, boolean manual) {
        if (!manual && state() == State.COOLDOWN && System.currentTimeMillis() < cooldownUntilMs) {
            LOG.warn("ADB circuit breaker in COOLDOWN until {} — skipping recovery ({})",
                    cooldownUntilMs, abbreviate(reason));
            return false;
        }
        if (!manual && state() == State.SUSPENDED) {
            return false;
        }

        lastOpenReason = reason == null ? "" : reason;
        state.set(State.OPEN);
        HostAdbHealthSnapshot.Snapshot hostAtOpen = probeHostAdb();
        LOG.warn("OUTAGE marker=ADB_CIRCUIT_OPEN reason='{}' {} consecutiveFailedRecoveries={} manual={}",
                abbreviate(lastOpenReason), hostAtOpen.logToken(), consecutiveFailedRecoveries, manual);
        LOG.warn("ADB circuit breaker OPEN — coordinated recovery starting ({})", abbreviate(lastOpenReason));

        // Already know host ADB is dead and we have exhausted kill-server budget — skip another loop.
        if (!manual
                && hostAtOpen.looksDead()
                && consecutiveFailedRecoveries >= MAX_HOST_DEAD_RECOVERIES) {
            enterHostAdbSuspended(hostAtOpen, "host ADB still dead — skipping further kill-server");
            return false;
        }

        boolean paused = false;
        boolean keepPausedForSuspend = false;
        recoveryThread.set(Boolean.TRUE);
        try {
            try {
                pauseEngine.run();
                paused = true;
            } catch (Exception e) {
                LOG.error("Failed to pause queues before ADB recovery: {}", e.getMessage());
            }

            sleepQuietly(drainMillis);

            Collection<String> indexes = safeDeviceIndexes();
            AdbRecoveryReport report;
            BiConsumer<String, Boolean> probeListener = deviceProbeListener;
            try {
                report = recoveryActions.recover(indexes, probeListener);
                if (report == null) {
                    report = AdbRecoveryReport.empty();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.error("ADB recovery interrupted");
                lastRecoveryReport = AdbRecoveryReport.empty();
                String cause = markRecoveryFailed(indexes.size(), 0, hostAtOpen, "interrupted");
                if (shouldSuspendForHostDeath(cause, manual)) {
                    keepPausedForSuspend = true;
                    enterHostAdbSuspended(probeHostAdb(), cause);
                } else {
                    enterCooldown();
                }
                return false;
            } catch (Exception e) {
                LOG.error("ADB recovery failed: {}", e.getMessage());
                lastRecoveryReport = AdbRecoveryReport.empty();
                String cause = markRecoveryFailed(indexes.size(), 0, hostAtOpen, e.getMessage());
                if (shouldSuspendForHostDeath(cause, manual)) {
                    keepPausedForSuspend = true;
                    enterHostAdbSuspended(probeHostAdb(), cause);
                } else {
                    enterCooldown();
                }
                return false;
            }

            lastRecoveryReport = report;
            if (report.anyHealthy()) {
                consecutiveFailedRecoveries = 0;
                hostDeadAlertSent = false;
                clearFailures();
                state.set(State.CLOSED);
                HostAdbHealthSnapshot.Snapshot hostAtClose = probeHostAdb();
                LOG.info("OUTAGE marker=ADB_RECOVERY_OK devices={}/{} {}",
                        report.okCount(), report.totalCount(), hostAtClose.logToken());
                LOG.info("ADB circuit breaker CLOSED — {}/{} device(s) healthy",
                        report.okCount(), report.totalCount());
                return true;
            }
            String detail = report.totalCount() == 0
                    ? "no device indexes to probe"
                    : "devices healthy " + report.okCount() + "/" + report.totalCount();
            String cause = markRecoveryFailed(
                    report.totalCount(), report.okCount(), hostAtOpen, detail);
            if (shouldSuspendForHostDeath(cause, manual)) {
                keepPausedForSuspend = true;
                enterHostAdbSuspended(probeHostAdb(), cause);
            } else {
                enterCooldown();
            }
            return false;
        } finally {
            recoveryThread.set(Boolean.FALSE);
            if (paused && !keepPausedForSuspend) {
                try {
                    resumeEngine.run();
                } catch (Exception e) {
                    LOG.error("Failed to resume queues after ADB recovery: {}", e.getMessage());
                }
            }
        }
    }

    private boolean shouldSuspendForHostDeath(String causeClass, boolean manual) {
        if (!"HOST_ADB_DEAD".equals(causeClass)) {
            return false;
        }
        // Manual /adbreboot always gets its one attempt; suspend if that also fails.
        if (manual) {
            return true;
        }
        return consecutiveFailedRecoveries >= MAX_HOST_DEAD_RECOVERIES;
    }

    private void enterHostAdbSuspended(HostAdbHealthSnapshot.Snapshot host, String detail) {
        state.set(State.SUSPENDED);
        clearFailures();
        LOG.error("OUTAGE marker=ADB_HOST_SUSPENDED detail='{}' {} consecutiveFailedRecoveries={} "
                        + "queues=paused killServerLoops=stopped",
                abbreviate(detail), host.logToken(), consecutiveFailedRecoveries);
        if (!hostDeadAlertSent) {
            hostDeadAlertSent = true;
            String message = "🚨 *HOST ADB DEAD*\n"
                    + "Frostguard paused all queues and *stopped* ADB kill-server loops.\n"
                    + "\n"
                    + "1. Dismiss any `adb.exe` / `ldconsole.exe` error popups on the PC\n"
                    + "2. Send `/adbreboot` to relaunch ADB and resume\n"
                    + "3. If that fails: reboot the PC, then `/adbreboot` or `/startbot`\n"
                    + "\n"
                    + "_Detail: " + abbreviate(detail) + "_";
            try {
                operatorAlert.accept(message);
            } catch (Exception e) {
                LOG.error("Failed to send HOST ADB DEAD operator alert: {}", e.getMessage());
            }
        }
    }

    private String markRecoveryFailed(int deviceCount, int probesOk,
            HostAdbHealthSnapshot.Snapshot hostAtOpen, String detail) {
        consecutiveFailedRecoveries++;
        HostAdbHealthSnapshot.Snapshot hostNow = probeHostAdb();
        String causeClass = hostNow.looksDead() || hostAtOpen.looksDead()
                ? "HOST_ADB_DEAD"
                : "DEVICES_UNREACHABLE";
        LOG.error("OUTAGE marker=ADB_RECOVERY_FAILED causeClass={} detail='{}' devices={} probesOk={} "
                        + "consecutiveFailedRecoveries={} openHost=({}) nowHost=({}) "
                        + "correlate=check Windows Application Error for adb.exe/ldconsole 0xc000012d/0xc0000142",
                causeClass,
                abbreviate(detail),
                deviceCount,
                probesOk,
                consecutiveFailedRecoveries,
                hostAtOpen.logToken(),
                hostNow.logToken());
        return causeClass;
    }

    private HostAdbHealthSnapshot.Snapshot probeHostAdb() {
        Supplier<HostAdbHealthSnapshot.Snapshot> override = hostProbeOverride;
        if (override != null) {
            return override.get();
        }
        try {
            return HostAdbHealthSnapshot.capture(adbPath.get());
        } catch (Exception e) {
            return new HostAdbHealthSnapshot.Snapshot(-1L, false, "probeError=" + e.getMessage());
        }
    }

    private void enterCooldown() {
        cooldownUntilMs = System.currentTimeMillis() + COOLDOWN_MS;
        state.set(State.COOLDOWN);
        LOG.warn("ADB circuit breaker COOLDOWN for {}s — captures suppressed",
                COOLDOWN_MS / 1000);
    }

    private Collection<String> safeDeviceIndexes() {
        try {
            Collection<String> indexes = deviceIndexes.get();
            return indexes == null ? Collections.emptyList() : indexes;
        } catch (Exception e) {
            LOG.warn("Could not list device indexes for ADB recovery: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private void clearFailures() {
        synchronized (failureLock) {
            recentFailures.clear();
        }
    }

    private void pruneFailures(long now) {
        while (!recentFailures.isEmpty() && now - recentFailures.peekFirst() > FAILURE_WINDOW_MS) {
            recentFailures.removeFirst();
        }
    }

    /**
     * Immediate fleet recovery triggers — only true host-bridge death.
     * Deliberately excludes:
     * <ul>
     *   <li>adb-connect subprocess timeouts (common during multi-boot)</li>
     *   <li>{@code ShellCommandUnresponsiveException} / screencap flakes
     *       (common under 4-client load; kill-server thrash freezes the game
     *       into Android "isn't responding" ANRs)</li>
     * </ul>
     * Soft failures still accumulate toward {@link #FAILURE_THRESHOLD}.
     */
    static boolean looksLikeHardBridgeFailure(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        // Ordinary screencap diagnostics embed exception class names; never
        // escalate those to an immediate kill-server.
        if (lower.contains("adb screencap failed") || lower.startsWith("adb capture suppressed")) {
            return false;
        }
        return lower.contains("connection reset")
                || lower.contains("bridge is null")
                || lower.contains("this.bridge");
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "...";
    }

    private static void sleepQuietly(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
