package dev.frostguard.engine.emulator;

import java.io.File;
import java.time.Duration;
import java.util.Locale;
/**
 * One-shot host ADB process/version probe for outage correlation.
 * Windows Event Log often shows {@code adb.exe} {@code 0xc000012d}/{@code 0xc0000142}
 * at the same second as {@code Connection reset} storms.
 */
final class HostAdbHealthSnapshot {

    private HostAdbHealthSnapshot() {
    }

    static Snapshot capture(String adbPath) {
        long adbProcesses = countAdbProcesses();
        String versionLine = "unprobed";
        boolean versionOk = false;
        if (adbPath != null && !adbPath.isBlank() && new File(adbPath).isFile()) {
            try {
                BoundedProcessRunner.ProcessResult result = BoundedProcessRunner.run(
                        new ProcessBuilder(adbPath, "version")
                                .directory(new File(adbPath).getParentFile()),
                        Duration.ofSeconds(5));
                versionOk = !result.timedOut() && result.exitCode() == 0;
                String output = result.output() == null ? "" : result.output().trim();
                versionLine = versionOk
                        ? firstLine(output)
                        : ("exit=" + result.exitCode()
                                + (result.timedOut() ? " timedOut" : "")
                                + " out=" + abbreviate(output));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                versionLine = "interrupted";
            } catch (Exception e) {
                versionLine = "error=" + e.getClass().getSimpleName() + ":"
                        + String.valueOf(e.getMessage());
            }
        } else {
            versionLine = "adbPathMissing";
        }
        return new Snapshot(adbProcesses, versionOk, versionLine);
    }

    private static long countAdbProcesses() {
        try {
            return ProcessHandle.allProcesses()
                    .filter(Handle -> {
                        String cmd = Handle.info().command().orElse("").toLowerCase(Locale.ROOT);
                        String name = new File(cmd).getName();
                        return name.equals("adb.exe") || name.equals("adb");
                    })
                    .count();
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "(empty)";
        }
        int nl = text.indexOf('\n');
        return abbreviate(nl < 0 ? text.trim() : text.substring(0, nl).trim());
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "...";
    }

    record Snapshot(long adbProcessCount, boolean versionOk, String versionDetail) {
        String logToken() {
            return "hostAdbProcesses=" + adbProcessCount
                    + " hostAdbVersionOk=" + versionOk
                    + " hostAdbVersion='" + versionDetail + "'";
        }

        /**
         * True when the host binary looks crashed or unable to start.
         * Unknown/missing path is not treated as dead (avoids false suspend in tests).
         */
        boolean looksDead() {
            if (versionDetail == null
                    || versionDetail.equals("adbPathMissing")
                    || versionDetail.equals("unprobed")
                    || versionDetail.startsWith("probeError")) {
                return false;
            }
            return !versionOk || adbProcessCount == 0;
        }
    }
}
