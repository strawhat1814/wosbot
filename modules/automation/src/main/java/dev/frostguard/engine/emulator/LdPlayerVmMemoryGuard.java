package dev.frostguard.engine.emulator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects LDPlayer {@code Ld9BoxHeadless} VMs whose private working set has
 * ballooned far past the configured RAM (common overnight leak) and selects
 * one emulator index to quit/launch. Mapping uses {@code --comment leidianN}
 * on the process command line.
 */
public final class LdPlayerVmMemoryGuard {

    private static final Logger LOG = LoggerFactory.getLogger(LdPlayerVmMemoryGuard.class);

    static final long DEFAULT_LIMIT_BYTES = 20L * 1024 * 1024 * 1024;
    static final long DEFAULT_CHECK_INTERVAL_MS = 120_000L;
    static final long DEFAULT_COOLDOWN_MS = 45L * 60_000L;

    private static final Pattern LEIDIAN_INDEX = Pattern.compile(
            "(?i)--comment\\s+leidian(\\d+)\\b|\\bleidian(\\d+)\\b");
    private static final Pattern SAMPLE_LINE = Pattern.compile(
            "^(\\d+)\\|(\\d+)\\|(\\d+)\\s*$");

    private final Map<String, Long> cooldownUntilMsByIndex = new ConcurrentHashMap<>();
    private final Supplier<List<VmSample>> sampleSource;
    /** {@code -1} means never checked — first call should run immediately. */
    private volatile long lastCheckMs = -1L;
    private volatile long checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;
    private volatile long cooldownMs = DEFAULT_COOLDOWN_MS;
    private volatile long limitBytes = DEFAULT_LIMIT_BYTES;

    public LdPlayerVmMemoryGuard() {
        this(LdPlayerVmMemoryGuard::scanHostLdPlayerVms);
    }

    /** Test hook with a fixed sample supplier. */
    LdPlayerVmMemoryGuard(Supplier<List<VmSample>> sampleSource) {
        this.sampleSource = sampleSource == null
                ? List::of
                : sampleSource;
    }

    public void configure(long limitBytes, long checkIntervalMs, long cooldownMs) {
        this.limitBytes = Math.max(1L, limitBytes);
        this.checkIntervalMs = Math.max(15_000L, checkIntervalMs);
        this.cooldownMs = Math.max(60_000L, cooldownMs);
    }

    public long limitBytes() {
        return limitBytes;
    }

    public boolean shouldCheckNow(long nowMs) {
        if (lastCheckMs < 0L) {
            return true;
        }
        return nowMs - lastCheckMs >= checkIntervalMs;
    }

    /**
     * Among {@code watchedIndexes}, return the fattest VM over the private-byte
     * limit that is not in cooldown. At most one index so restarts stay staggered.
     */
    public Optional<VmSample> selectRestart(Collection<String> watchedIndexes, long nowMs) {
        lastCheckMs = nowMs;
        if (watchedIndexes == null || watchedIndexes.isEmpty()) {
            return Optional.empty();
        }
        List<VmSample> samples;
        try {
            samples = sampleSource.get();
        } catch (Exception e) {
            LOG.warn("LDPlayer VM memory scan failed: {}", e.getMessage());
            return Optional.empty();
        }
        if (samples == null || samples.isEmpty()) {
            return Optional.empty();
        }

        Optional<VmSample> chosen = samples.stream()
                .filter(s -> watchedIndexes.contains(s.index()))
                .filter(s -> s.privateBytes() >= limitBytes)
                .filter(s -> !coolingDown(s.index(), nowMs))
                .max(Comparator.comparingLong(VmSample::privateBytes));
        chosen.ifPresent(s -> LOG.warn(
                "OUTAGE marker=LDPLAYER_VM_BLOATED index={} privateGb={} limitGb={} pid={}",
                s.index(),
                String.format(Locale.ROOT, "%.1f", s.privateBytes() / (1024.0 * 1024.0 * 1024.0)),
                String.format(Locale.ROOT, "%.1f", limitBytes / (1024.0 * 1024.0 * 1024.0)),
                s.pid()));
        return chosen;
    }

    public void markRestarted(String index, long nowMs) {
        if (index == null || index.isBlank()) {
            return;
        }
        cooldownUntilMsByIndex.put(index.trim(), nowMs + cooldownMs);
    }

    boolean coolingDown(String index, long nowMs) {
        Long until = cooldownUntilMsByIndex.get(index);
        return until != null && nowMs < until;
    }

    /**
     * Parse scanner lines {@code index|privateBytes|pid}. Package-visible for tests.
     */
    static List<VmSample> parseSampleLines(String output) {
        List<VmSample> out = new ArrayList<>();
        if (output == null || output.isBlank()) {
            return out;
        }
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            Matcher m = SAMPLE_LINE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            out.add(new VmSample(m.group(1), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))));
        }
        return out;
    }

    static Optional<String> parseLeidianIndex(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return Optional.empty();
        }
        Matcher m = LEIDIAN_INDEX.matcher(commandLine);
        if (!m.find()) {
            return Optional.empty();
        }
        String a = m.group(1);
        String b = m.group(2);
        return Optional.of(a != null ? a : b);
    }

    private static List<VmSample> scanHostLdPlayerVms() {
        if (!isWindows()) {
            return List.of();
        }
        String script = String.join(" ",
                "Get-CimInstance Win32_Process -Filter \"Name='Ld9BoxHeadless.exe'\" | ForEach-Object {",
                "  $proc = Get-Process -Id $_.ProcessId -ErrorAction SilentlyContinue;",
                "  if ($null -eq $proc) { return };",
                "  $m = [regex]::Match([string]$_.CommandLine, 'leidian(\\d+)');",
                "  if (-not $m.Success) { return };",
                "  Write-Output ($m.Groups[1].Value + '|' + $proc.PrivateMemorySize64 + '|' + $_.ProcessId)",
                "}");
        try {
            BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                    new ProcessBuilder(
                            "powershell.exe",
                            "-NoProfile",
                            "-ExecutionPolicy", "Bypass",
                            "-Command", script),
                    Duration.ofSeconds(20));
            if (result.timedOut()) {
                LOG.warn("LDPlayer VM memory scan timed out");
                return List.of();
            }
            return parseSampleLines(result.output());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.warn("LDPlayer VM memory scan error: {}", e.getMessage());
            return List.of();
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase(Locale.ROOT).contains("win");
    }

    public record VmSample(String index, long privateBytes, long pid) {
        public double privateGb() {
            return privateBytes / (1024.0 * 1024.0 * 1024.0);
        }
    }
}
