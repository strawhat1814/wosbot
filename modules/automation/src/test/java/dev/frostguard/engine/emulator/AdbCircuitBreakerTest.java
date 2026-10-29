package dev.frostguard.engine.emulator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdbCircuitBreakerTest {

    private AdbCircuitBreaker breaker;
    private final AtomicInteger recoverCalls = new AtomicInteger();
    private final AtomicInteger pauseCalls = new AtomicInteger();
    private final AtomicInteger resumeCalls = new AtomicInteger();
    private final List<String> recoveredIndexes = new ArrayList<>();

    @BeforeEach
    void setUp() {
        breaker = new AdbCircuitBreaker();
        breaker.overrideDrainMillisForTest(0L);
        recoverCalls.set(0);
        pauseCalls.set(0);
        resumeCalls.set(0);
        recoveredIndexes.clear();
        breaker.configure(
                pauseCalls::incrementAndGet,
                resumeCalls::incrementAndGet,
                () -> List.of("0", "1"),
                (indexes, onDeviceProbed) -> {
                    recoverCalls.incrementAndGet();
                    recoveredIndexes.clear();
                    recoveredIndexes.addAll(indexes);
                    return allOk(indexes, onDeviceProbed);
                });
    }

    @Test
    void hardFailureOpensBreakerAndRunsSingleCoordinatedRecovery() {
        breaker.recordFailure("Connection reset by peer");

        assertEquals(AdbCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(1, recoverCalls.get());
        assertEquals(1, pauseCalls.get());
        assertEquals(1, resumeCalls.get());
        assertEquals(List.of("0", "1"), recoveredIndexes);
        assertTrue(breaker.isCaptureAllowed());
        assertTrue(breaker.lastRecoveryReport().anyHealthy());
    }

    @Test
    void concurrentRecoveryRequestsCoalesceOntoOneRecover() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inFlight = new AtomicInteger();

        breaker.configure(
                pauseCalls::incrementAndGet,
                resumeCalls::incrementAndGet,
                () -> List.of("0"),
                (indexes, onDeviceProbed) -> {
                    recoverCalls.incrementAndGet();
                    inFlight.incrementAndGet();
                    started.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    inFlight.decrementAndGet();
                    return allOk(indexes, onDeviceProbed);
                });

        Thread first = Thread.ofVirtual().start(() -> breaker.requestRecovery("first"));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        assertEquals(1, inFlight.get());

        Thread second = Thread.ofVirtual().start(() -> breaker.requestRecovery("second"));
        Thread.sleep(50);
        assertEquals(1, recoverCalls.get());
        assertEquals(1, inFlight.get());

        release.countDown();
        first.join(5_000);
        second.join(5_000);

        assertEquals(1, recoverCalls.get());
        assertEquals(1, pauseCalls.get());
        assertEquals(1, resumeCalls.get());
        assertEquals(AdbCircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void captureDisallowedWhileOpenAndDuringCooldown() {
        AtomicReference<AdbCircuitBreaker.State> stateDuringRecover = new AtomicReference<>();
        breaker.configure(
                () -> {
                    pauseCalls.incrementAndGet();
                    stateDuringRecover.set(breaker.state());
                    assertFalse(breaker.isCaptureAllowed());
                },
                resumeCalls::incrementAndGet,
                () -> List.of("0"),
                (indexes, onDeviceProbed) -> {
                    recoverCalls.incrementAndGet();
                    assertFalse(breaker.isCaptureAllowed());
                    return allFail(indexes, onDeviceProbed);
                });

        assertFalse(breaker.recoverNow("force fail"));
        assertEquals(AdbCircuitBreaker.State.OPEN, stateDuringRecover.get());
        assertEquals(AdbCircuitBreaker.State.COOLDOWN, breaker.state());
        assertFalse(breaker.isCaptureAllowed());

        int skipped = 0;
        for (int i = 0; i < 3; i++) {
            if (!breaker.isCaptureAllowed()) {
                skipped++;
            }
        }
        assertEquals(3, skipped);
    }

    @Test
    void anyDeviceSuccessClosesBreakerAndLiveCallbackFiresPerDevice() {
        List<String> live = new ArrayList<>();
        breaker.configure(
                pauseCalls::incrementAndGet,
                resumeCalls::incrementAndGet,
                () -> List.of("0", "1"),
                (indexes, onDeviceProbed) -> {
                    recoverCalls.incrementAndGet();
                    Map<String, Boolean> ok = new LinkedHashMap<>();
                    ok.put("0", true);
                    onDeviceProbed.accept("0", true);
                    ok.put("1", false);
                    onDeviceProbed.accept("1", false);
                    return new AdbRecoveryReport(ok);
                });

        assertTrue(breaker.recoverNow("partial ok", (idx, ok) ->
                live.add(idx + "=" + ok)));
        assertEquals(AdbCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(List.of("0=true", "1=false"), live);
        assertEquals(1, breaker.lastRecoveryReport().okCount());
        assertEquals(2, breaker.lastRecoveryReport().totalCount());
    }

    @Test
    void failureBurstOpensBreakerOnceThresholdReached() {
        for (int i = 0; i < AdbCircuitBreaker.FAILURE_THRESHOLD - 1; i++) {
            breaker.recordFailure("soft flake " + i);
        }
        assertEquals(0, recoverCalls.get());
        assertTrue(breaker.isCaptureAllowed());

        breaker.recordFailure("soft flake threshold");
        assertEquals(1, recoverCalls.get());
        assertEquals(AdbCircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void looksLikeHardBridgeFailureRecognizesKnownSignals() {
        assertTrue(AdbCircuitBreaker.looksLikeHardBridgeFailure("Connection reset"));
        assertTrue(AdbCircuitBreaker.looksLikeHardBridgeFailure("this.bridge is null"));
        assertFalse(AdbCircuitBreaker.looksLikeHardBridgeFailure("template miss"));
        assertFalse(AdbCircuitBreaker.looksLikeHardBridgeFailure(
                "adb connect to 127.0.0.1:5557 did not respond within 10 seconds; killed the subprocess"));
        assertFalse(AdbCircuitBreaker.looksLikeHardBridgeFailure(
                "ADB screencap failed; emulator=3; cause=ShellCommandUnresponsiveException: no message"));
        assertFalse(AdbCircuitBreaker.looksLikeHardBridgeFailure(
                "ShellCommandUnresponsiveException alone is soft"));
    }

    @Test
    void transportAllowedOnRecoveryThreadWhileCaptureBlocked() {
        AtomicReference<Boolean> transportDuringRecover = new AtomicReference<>();
        AtomicReference<Boolean> captureDuringRecover = new AtomicReference<>();
        breaker.configure(
                () -> {},
                () -> {},
                () -> List.of("0"),
                (indexes, onDeviceProbed) -> {
                    transportDuringRecover.set(breaker.isTransportAllowed());
                    captureDuringRecover.set(breaker.isCaptureAllowed());
                    return allOk(indexes, onDeviceProbed);
                });

        assertTrue(breaker.recoverNow("probe transport gate"));
        assertEquals(Boolean.TRUE, transportDuringRecover.get());
        assertEquals(Boolean.FALSE, captureDuringRecover.get());
        assertTrue(breaker.isCaptureAllowed());
        assertTrue(breaker.isTransportAllowed());
    }

    @Test
    void hostAdbDeadSuspendsAfterBoundedKillServerAttemptsAndAlertsOnce() {
        List<String> alerts = new ArrayList<>();
        HostAdbHealthSnapshot.Snapshot dead =
                new HostAdbHealthSnapshot.Snapshot(0L, false, "exit=1 out=cannot start");
        breaker.overrideHostProbeForTest(() -> dead);
        breaker.configure(
                pauseCalls::incrementAndGet,
                resumeCalls::incrementAndGet,
                () -> List.of("0"),
                (indexes, onDeviceProbed) -> {
                    recoverCalls.incrementAndGet();
                    return allFail(indexes, onDeviceProbed);
                },
                () -> "C:\\fake\\adb.exe",
                alerts::add);

        assertFalse(breaker.requestRecovery("host dead wave 1"));
        assertEquals(AdbCircuitBreaker.State.COOLDOWN, breaker.state());
        assertEquals(1, recoverCalls.get());
        assertEquals(1, resumeCalls.get());
        assertTrue(alerts.isEmpty());

        assertFalse(breaker.recoverNow("host dead wave 2"));
        assertEquals(AdbCircuitBreaker.State.SUSPENDED, breaker.state());
        assertEquals(2, recoverCalls.get());
        assertEquals(1, resumeCalls.get());
        assertEquals(1, alerts.size());
        assertTrue(alerts.get(0).contains("/adbreboot"));

        assertFalse(breaker.requestRecovery("ignored while suspended"));
        assertEquals(2, recoverCalls.get());
        assertEquals(1, alerts.size());
        assertFalse(breaker.isCaptureAllowed());
        assertTrue(breaker.isHostAdbSuspended());
    }

    @Test
    void recoverNowClearsSuspendedAndCanResumeWhenHostHealthy() {
        List<String> alerts = new ArrayList<>();
        AtomicReference<HostAdbHealthSnapshot.Snapshot> host =
                new AtomicReference<>(new HostAdbHealthSnapshot.Snapshot(0L, false, "exit=1"));
        breaker.overrideHostProbeForTest(host::get);
        breaker.configure(
                pauseCalls::incrementAndGet,
                resumeCalls::incrementAndGet,
                () -> List.of("0"),
                (indexes, onDeviceProbed) -> {
                    recoverCalls.incrementAndGet();
                    return host.get().versionOk()
                            ? allOk(indexes, onDeviceProbed)
                            : allFail(indexes, onDeviceProbed);
                },
                () -> "C:\\fake\\adb.exe",
                alerts::add);

        assertFalse(breaker.recoverNow("manual while dead"));
        assertEquals(AdbCircuitBreaker.State.SUSPENDED, breaker.state());
        assertEquals(1, alerts.size());
        assertEquals(0, resumeCalls.get());

        host.set(new HostAdbHealthSnapshot.Snapshot(1L, true, "Android Debug Bridge version 1.0.41"));
        assertTrue(breaker.recoverNow("telegram /adbreboot"));
        assertEquals(AdbCircuitBreaker.State.CLOSED, breaker.state());
        assertEquals(1, resumeCalls.get());
        assertEquals(1, alerts.size());
    }

    @Test
    void hostLooksDeadIgnoresMissingPath() {
        assertFalse(new HostAdbHealthSnapshot.Snapshot(0L, false, "adbPathMissing").looksDead());
        assertFalse(new HostAdbHealthSnapshot.Snapshot(-1L, false, "unprobed").looksDead());
        assertTrue(new HostAdbHealthSnapshot.Snapshot(0L, false, "exit=1 out=fail").looksDead());
        assertTrue(new HostAdbHealthSnapshot.Snapshot(0L, true, "Android Debug Bridge version 1.0.41").looksDead());
    }

    private static AdbRecoveryReport allOk(Collection<String> indexes,
            java.util.function.BiConsumer<String, Boolean> onDeviceProbed) {
        Map<String, Boolean> ok = new LinkedHashMap<>();
        for (String idx : indexes) {
            ok.put(idx, true);
            if (onDeviceProbed != null) {
                onDeviceProbed.accept(idx, true);
            }
        }
        return new AdbRecoveryReport(ok);
    }

    private static AdbRecoveryReport allFail(Collection<String> indexes,
            java.util.function.BiConsumer<String, Boolean> onDeviceProbed) {
        Map<String, Boolean> ok = new LinkedHashMap<>();
        for (String idx : indexes) {
            ok.put(idx, false);
            if (onDeviceProbed != null) {
                onDeviceProbed.accept(idx, false);
            }
        }
        return new AdbRecoveryReport(ok);
    }
}
