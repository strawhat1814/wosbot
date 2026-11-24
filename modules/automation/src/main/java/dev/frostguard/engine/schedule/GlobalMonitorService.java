package dev.frostguard.engine.schedule;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.configs.TpMessageSeverityEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.RawImageData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.emulator.LdPlayerVmMemoryGuard;
import dev.frostguard.engine.schedule.inject.InjectionRule;
import dev.frostguard.engine.schedule.preempt.PreemptionListener;
import dev.frostguard.engine.schedule.preempt.PreemptionRule;
import dev.frostguard.engine.service.ConfigService;
import dev.frostguard.engine.service.LoggingService;

// Background sentinel that periodically screenshots each monitored profile
// and evaluates preemption and injection rules against the captured frame.
public class GlobalMonitorService {

    private static final Logger log = LoggerFactory.getLogger(GlobalMonitorService.class);
    private static GlobalMonitorService singleton;

    private final EmulatorController controller = EmulatorController.getInstance();
    private final List<PreemptionRule> preemptionRules = new ArrayList<>();
    private final List<InjectionRule> injectionRules = new ArrayList<>();
    private final List<PreemptionListener> preemptionObservers = new CopyOnWriteArrayList<>();
    private final LdPlayerVmMemoryGuard vmMemoryGuard = new LdPlayerVmMemoryGuard();
    private volatile Consumer<String> operatorAlert = message -> {};

    private final Map<Long, MonitoredEntry> monitoredProfiles = new ConcurrentHashMap<>();
    private final Map<Long, Queue<InjectionRule>> pendingInjections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService ticker;

    // Encapsulates a monitored profile and its task-check function.
    private record MonitoredEntry(AccountDescriptor profile,
                                  Function<TpDailyTaskEnum, Boolean> taskActiveCheck) {}

    private GlobalMonitorService() {
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GlobalMonitor-Ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::runCycle, 5, 5, TimeUnit.SECONDS);
    }

    public static synchronized GlobalMonitorService getInstance() {
        if (singleton == null) singleton = new GlobalMonitorService();
        return singleton;
    }

    public void shutdown() {
        monitoredProfiles.clear();
        pendingInjections.clear();
        ticker.shutdownNow();
        synchronized (GlobalMonitorService.class) { singleton = null; }
        log.info("Global monitor shut down");
    }

    // ── registration ────────────────────────────────────────────────

    public void registerListener(PreemptionListener listener) {
        preemptionObservers.add(listener);
    }

    public void registerRule(PreemptionRule rule) {
        preemptionRules.add(rule);
        log.info("Preemption rule added: {}", rule.getRuleName());
    }

    public void registerInjectionRule(InjectionRule rule) {
        injectionRules.add(rule);
        log.info("Injection rule added: {}", rule.getRuleName());
    }

    /** Telegram (or other) operator paging for LDPlayer VM memory restarts. */
    public void setOperatorAlert(Consumer<String> operatorAlert) {
        this.operatorAlert = operatorAlert == null ? message -> {} : operatorAlert;
    }

    public void startMonitoring(AccountDescriptor profile,
                                Function<TpDailyTaskEnum, Boolean> taskActiveCheck) {
        monitoredProfiles.put(profile.getId(),
                new MonitoredEntry(profile, taskActiveCheck));
        pendingInjections.computeIfAbsent(profile.getId(), k -> new ConcurrentLinkedQueue<>());
        log.info("Monitoring started for {}", profile.getName());
    }

    public void stopMonitoring(Long profileId) {
        monitoredProfiles.remove(profileId);
        pendingInjections.remove(profileId);
        log.info("Monitoring stopped for profile {}", profileId);
    }

    public boolean updateProfile(AccountDescriptor profile) {
        if (profile == null || profile.getId() == null) return false;
        return monitoredProfiles.computeIfPresent(profile.getId(), (ignored, current) ->
                new MonitoredEntry(profile, current.taskActiveCheck())) != null;
    }

    // Dequeues the next pending injection for the given profile.
    public InjectionRule pollPendingInjection(Long profileId) {
        Queue<InjectionRule> q = pendingInjections.get(profileId);
        return q != null ? q.poll() : null;
    }

    // ── monitoring loop ─────────────────────────────────────────────

    private void runCycle() {
        if (!controller.isAdbCaptureAllowed()) {
            log.debug("GlobalMonitor skipping cycle — ADB circuit breaker={}",
                    controller.adbCircuitState());
            return;
        }
        maybeRestartBloatedLdPlayerVm();
        for (MonitoredEntry entry : monitoredProfiles.values()) {
            try {
                String devIdx = entry.profile.getEmulatorNumber();
                if (!controller.isRunning(devIdx)) continue;
                if (!controller.isAdbCaptureAllowed()) {
                    return;
                }
                RawImageData frame = controller.captureScreen(devIdx);
                processPreemptionRules(entry, frame);
                processInjectionRules(entry, frame);
            } catch (Exception ex) {
                log.warn("Monitor tick error [{}]: {}", entry.profile.getId(), ex.getMessage());
            }
        }
    }

    /**
     * Periodically scan host {@code Ld9BoxHeadless} private bytes. If one watched
     * index is over the limit, quit+launch that LDPlayer only (staggered by
     * checking at most one per interval and a long per-index cooldown).
     */
    private void maybeRestartBloatedLdPlayerVm() {
        if (!readVmGuardEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        refreshVmGuardConfig();
        if (!vmMemoryGuard.shouldCheckNow(now)) {
            return;
        }
        Set<String> watched = watchedEmulatorIndexes();
        if (watched.isEmpty()) {
            vmMemoryGuard.selectRestart(Set.of(), now);
            return;
        }
        Optional<LdPlayerVmMemoryGuard.VmSample> bloated =
                vmMemoryGuard.selectRestart(watched, now);
        if (bloated.isEmpty()) {
            return;
        }
        LdPlayerVmMemoryGuard.VmSample sample = bloated.get();
        String index = sample.index();
        String label = profileLabelForEmu(index);
        String detail = String.format(Locale.ROOT,
                "LDPlayer VM private %.1f GB (limit %.0f GB) pid=%d",
                sample.privateGb(),
                vmMemoryGuard.limitBytes() / (1024.0 * 1024.0 * 1024.0),
                sample.pid());
        log.warn("Restarting bloated LDPlayer index={} ({}) — {}", index, label, detail);
        LoggingService.obtain().emit(TpMessageSeverityEnum.WARNING, "GlobalMonitor", label,
                "Restarting bloated LDPlayer VM: " + detail);
        try {
            operatorAlert.accept("⚠️ *LDPlayer VM bloated — restarting*\n"
                    + label + " (emu `" + index + "`)\n"
                    + detail + "\n"
                    + "_Quit + launch this instance only. Cooldown prevents a reboot loop._");
        } catch (Exception e) {
            log.warn("LDPlayer VM bloat operator alert failed: {}", e.getMessage());
        }
        try {
            controller.closeEmulator(index);
            Thread.sleep(5_000L);
            controller.launchEmulator(index);
            vmMemoryGuard.markRestarted(index, System.currentTimeMillis());
            log.info("OUTAGE marker=LDPLAYER_VM_RESTARTED index={} {}", index, detail);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("LDPlayer VM restart interrupted for index {}", index);
        } catch (Exception e) {
            log.error("LDPlayer VM restart failed for index {}: {}", index, e.getMessage());
            vmMemoryGuard.markRestarted(index, System.currentTimeMillis());
        }
    }

    private Set<String> watchedEmulatorIndexes() {
        Set<String> indexes = new LinkedHashSet<>();
        for (MonitoredEntry entry : monitoredProfiles.values()) {
            if (entry.profile == null) {
                continue;
            }
            String emu = entry.profile.getEmulatorNumber();
            if (emu != null && !emu.isBlank()) {
                indexes.add(emu.trim());
            }
        }
        return indexes;
    }

    private String profileLabelForEmu(String index) {
        for (MonitoredEntry entry : monitoredProfiles.values()) {
            if (entry.profile != null && index.equals(entry.profile.getEmulatorNumber())) {
                return entry.profile.getName() != null ? entry.profile.getName() : ("emu " + index);
            }
        }
        return "emu " + index;
    }

    private void refreshVmGuardConfig() {
        long limitGb = readIntConfig(ConfigurationKeyEnum.EMULATOR_VM_PRIVATE_LIMIT_GB_INT, 20);
        long checkSec = readIntConfig(ConfigurationKeyEnum.EMULATOR_VM_MEMORY_CHECK_SEC_INT, 120);
        long coolMin = readIntConfig(ConfigurationKeyEnum.EMULATOR_VM_MEMORY_COOLDOWN_MIN_INT, 45);
        vmMemoryGuard.configure(
                Math.max(1L, limitGb) * 1024L * 1024L * 1024L,
                Math.max(30L, checkSec) * 1_000L,
                Math.max(5L, coolMin) * 60_000L);
    }

    private static boolean readVmGuardEnabled() {
        try {
            Map<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
            if (cfg == null) {
                return true;
            }
            String raw = cfg.get(ConfigurationKeyEnum.EMULATOR_VM_MEMORY_GUARD_BOOL.name());
            if (raw == null || raw.isBlank()) {
                return true;
            }
            return Boolean.parseBoolean(raw.trim());
        } catch (Exception e) {
            return true;
        }
    }

    private static long readIntConfig(ConfigurationKeyEnum key, long fallback) {
        try {
            Map<String, String> cfg = ConfigService.obtain().loadGlobalSettings();
            String raw = cfg == null ? null : cfg.get(key.name());
            if (raw == null || raw.isBlank()) {
                raw = key.getDefaultValue();
            }
            return Long.parseLong(raw.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private void processPreemptionRules(MonitoredEntry entry, RawImageData frame) {
        AccountDescriptor acct = entry.profile;
        try {
            for (PreemptionRule rule : preemptionRules) {
                if (entry.taskActiveCheck.apply(rule.getTaskToExecute())) continue;
                try {
                    if (!rule.shouldPreempt(controller, acct, frame)) continue;
                    log.info("Preemption triggered [{}]: {}", acct.getName(), rule.getRuleName());
                    LoggingService.obtain().emit(TpMessageSeverityEnum.WARNING,
                            "GlobalMonitor", acct.getName(),
                            "Preempted by: " + rule.getRuleName());
                    preemptionObservers.forEach(obs -> obs.onPreemption(acct, rule));
                } catch (Exception ex) {
                    log.error("Rule evaluation error [{}]: {} - {}",
                            acct.getName(), rule.getRuleName(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Preemption loop error [{}]: {}", acct.getName(), ex.getMessage());
        }
    }

    private void processInjectionRules(MonitoredEntry entry, RawImageData frame) {
        AccountDescriptor acct = entry.profile;
        Queue<InjectionRule> pending = pendingInjections.get(acct.getId());
        if (pending == null) return;

        try {
            for (InjectionRule rule : injectionRules) {
                boolean alreadyQueued = pending.stream()
                        .anyMatch(queued -> queued.getClass().equals(rule.getClass()));
                if (alreadyQueued) continue;
                try {
                    if (!rule.shouldInject(controller, acct, frame)) continue;
                    log.debug("Injection queued [{}]: {}", acct.getName(), rule.getRuleName());
                    pending.offer(rule);
                } catch (Exception ex) {
                    log.error("Injection evaluation error [{}]: {} - {}",
                            acct.getName(), rule.getRuleName(), ex.getMessage());
                }
            }
        } catch (Exception ex) {
            log.error("Injection loop error [{}]: {}", acct.getName(), ex.getMessage());
        }
    }
}
