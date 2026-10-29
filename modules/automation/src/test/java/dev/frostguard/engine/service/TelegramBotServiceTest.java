package dev.frostguard.engine.service;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramBotServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void standardQueueExcludesCustomTaskPlaceholder() {
        assertFalse(TelegramBotService.isStandardQueueTask(TpDailyTaskEnum.CUSTOM_TASK));
        assertTrue(TelegramBotService.isStandardQueueTask(TpDailyTaskEnum.INITIALIZE));
    }

    @Test
    void adbRecoveryDeviceLabelsMapEnabledProfilesByEmulatorSlot() {
        AccountDescriptor stuart = new AccountDescriptor(5L, "Stuart", "5", true, 1L, 0L);
        AccountDescriptor kevin = new AccountDescriptor(2L, "Kevin", "2", true, 1L, 0L);
        AccountDescriptor disabled = new AccountDescriptor(9L, "Idle", "9", false, 1L, 0L);

        Map<String, String> labels = TelegramBotService.adbRecoveryDeviceLabels(
                List.of(stuart, kevin, disabled));

        assertEquals(Map.of("5", "Stuart", "2", "Kevin"), labels);
        assertFalse(labels.containsKey("9"));
    }

    @Test
    void allConfiguredEmulatorIndexesIncludesDisabledProfiles() {
        AccountDescriptor stuart = new AccountDescriptor(5L, "Stuart", "5", true, 1L, 0L);
        AccountDescriptor idle = new AccountDescriptor(9L, "Idle", "9", false, 1L, 0L);
        AccountDescriptor blank = new AccountDescriptor(1L, "Blank", "  ", false, 1L, 0L);

        assertEquals(Set.of("5", "9"),
                TelegramBotService.allConfiguredEmulatorIndexes(List.of(stuart, idle, blank)));
    }

    @Test
    void unusedEmulatorIndexesSkipsSlotsWithAnyEnabledPeer() {
        AccountDescriptor enabledShared = new AccountDescriptor(1L, "A", "3", true, 1L, 0L);
        AccountDescriptor disabledShared = new AccountDescriptor(2L, "B", "3", false, 1L, 0L);
        AccountDescriptor orphan = new AccountDescriptor(3L, "C", "7", false, 1L, 0L);
        AccountDescriptor enabledSolo = new AccountDescriptor(4L, "D", "4", true, 1L, 0L);

        Set<String> unused = TelegramBotService.unusedEmulatorIndexes(
                List.of(enabledShared, disabledShared, orphan, enabledSolo));

        assertEquals(Set.of("7"), unused);
    }

    @Test
    void unusedEmulatorIndexesTreatsFullyDisabledSharedSlotAsUnused() {
        AccountDescriptor first = new AccountDescriptor(1L, "A", "3", false, 1L, 0L);
        AccountDescriptor second = new AccountDescriptor(2L, "B", "3", false, 1L, 0L);

        assertEquals(Set.of("3"),
                TelegramBotService.unusedEmulatorIndexes(List.of(first, second)));
    }

    @Test
    void profilesByEmulatorGroupsOwnersInConfiguredOrder() {
        AccountDescriptor a = new AccountDescriptor(1L, "A", "3", true, 1L, 0L);
        AccountDescriptor b = new AccountDescriptor(2L, "B", "3", false, 1L, 0L);
        AccountDescriptor c = new AccountDescriptor(3L, "C", "7", false, 1L, 0L);

        Map<String, List<AccountDescriptor>> byEmu =
                TelegramBotService.profilesByEmulator(List.of(a, b, c));

        assertEquals(List.of(a, b), byEmu.get("3"));
        assertEquals(List.of(c), byEmu.get("7"));
        assertEquals(2, byEmu.size());
    }


    @Test
    void logDownloadUsesTheSelectedWorkspace() {
        String previous = System.getProperty(WorkspacePaths.WORKSPACE_PROPERTY);
        try {
            System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, tempDir.toString());
            assertEquals(tempDir.resolve("logs/frostguard.log").toAbsolutePath(),
                    TelegramBotService.currentLogPath());
        } finally {
            if (previous == null) {
                System.clearProperty(WorkspacePaths.WORKSPACE_PROPERTY);
            } else {
                System.setProperty(WorkspacePaths.WORKSPACE_PROPERTY, previous);
            }
        }
    }
}
