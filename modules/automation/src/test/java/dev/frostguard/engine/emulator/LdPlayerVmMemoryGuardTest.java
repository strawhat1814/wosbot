package dev.frostguard.engine.emulator;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LdPlayerVmMemoryGuardTest {

    @Test
    void parseLeidianIndexFromHeadlessCommandLine() {
        assertEquals(Optional.of("5"), LdPlayerVmMemoryGuard.parseLeidianIndex(
                "\"C:\\Program Files\\ldplayer9box\\Ld9BoxHeadless.exe\" --comment leidian5 --startvm 20160302-aaaa-aaaa-3713-000000000005 --vrde config"));
        assertEquals(Optional.of("7"), LdPlayerVmMemoryGuard.parseLeidianIndex("leidian7"));
        assertTrue(LdPlayerVmMemoryGuard.parseLeidianIndex("notepad.exe").isEmpty());
    }

    @Test
    void parseSampleLinesIgnoresGarbage() {
        List<LdPlayerVmMemoryGuard.VmSample> samples = LdPlayerVmMemoryGuard.parseSampleLines(
                "5|53687091200|13040\n"
                        + "not-a-sample\n"
                        + "6|45097156608|16316\n");
        assertEquals(2, samples.size());
        assertEquals("5", samples.get(0).index());
        assertEquals(53687091200L, samples.get(0).privateBytes());
        assertEquals(13040L, samples.get(0).pid());
    }

    @Test
    void selectRestartPicksFattestOverLimitAmongWatched() {
        AtomicReference<List<LdPlayerVmMemoryGuard.VmSample>> source = new AtomicReference<>(List.of(
                new LdPlayerVmMemoryGuard.VmSample("5", 25L * GB, 1),
                new LdPlayerVmMemoryGuard.VmSample("6", 40L * GB, 2),
                new LdPlayerVmMemoryGuard.VmSample("7", 10L * GB, 3)));
        LdPlayerVmMemoryGuard guard = new LdPlayerVmMemoryGuard(source::get);
        guard.configure(20L * GB, 60_000L, 60_000L);

        Optional<LdPlayerVmMemoryGuard.VmSample> chosen =
                guard.selectRestart(List.of("5", "6", "7"), 1_000L);
        assertTrue(chosen.isPresent());
        assertEquals("6", chosen.get().index());
    }

    @Test
    void selectRestartSkipsUnwatchedAndCooldown() {
        LdPlayerVmMemoryGuard guard = new LdPlayerVmMemoryGuard(() -> List.of(
                new LdPlayerVmMemoryGuard.VmSample("5", 40L * GB, 1),
                new LdPlayerVmMemoryGuard.VmSample("6", 45L * GB, 2)));
        guard.configure(20L * GB, 1L, 60_000L);

        assertTrue(guard.selectRestart(List.of("5"), 1_000L).isPresent());
        guard.markRestarted("5", 1_000L);
        assertTrue(guard.coolingDown("5", 1_000L + 1_000L));
        assertTrue(guard.selectRestart(List.of("5"), 1_000L + 1_000L).isEmpty());

        // Unwatched fat VM is ignored.
        assertTrue(guard.selectRestart(List.of("0"), 1_000L + 1_000L).isEmpty());
    }

    @Test
    void shouldCheckNowRespectsInterval() {
        LdPlayerVmMemoryGuard guard = new LdPlayerVmMemoryGuard(List::of);
        guard.configure(20L * GB, 120_000L, 60_000L);
        assertTrue(guard.shouldCheckNow(0L));
        guard.selectRestart(List.of(), 10_000L);
        assertFalse(guard.shouldCheckNow(10_000L + 30_000L));
        assertTrue(guard.shouldCheckNow(10_000L + 120_000L));
    }

    private static final long GB = 1024L * 1024L * 1024L;
}
