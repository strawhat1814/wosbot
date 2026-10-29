package dev.frostguard.engine.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.runtime.WorkspacePaths;
import dev.frostguard.api.runtime.WorkspaceSession;
import dev.frostguard.engine.schedule.DelayedTaskRegistry;
import dev.frostguard.engine.service.ProfileService;
import dev.frostguard.engine.sharedlogin.SharedLoginHandshakeCoordinator;
import dev.frostguard.engine.sharedlogin.SharedLoginPairGate;
import dev.frostguard.engine.sharedlogin.SharedLoginRecovery;

class TaskQueueSharedLoginRecoveryTest {

    @BeforeAll
    static void initializeTestWorkspace() {
        WorkspaceSession.initializeLayout(WorkspacePaths.current());
        DelayedTaskRegistry.registerFactory(StubInitialize::maybeCreate);
    }

    @AfterEach
    void clearCoordinator() {
        SharedLoginHandshakeCoordinator.shared().clear();
        SharedLoginPairGate.clear();
    }

    @Test
    void reconnectRecoverySkipsPauseAndQueuesInitializeForPairedProfile() {
        AccountDescriptor first = new AccountDescriptor(
                null, "Shared A " + UUID.randomUUID(), "11", true, 50L, 30L,
                null, "CharOne", null, null, null);
        AccountDescriptor second = new AccountDescriptor(
                null, "Shared B " + UUID.randomUUID(), "12", true, 50L, 30L,
                null, "CharTwo", null, null, null);
        assertTrue(ProfileService.obtain().createAccount(first));
        assertTrue(ProfileService.obtain().createAccount(second));
        first.setSharedLoginPeerProfileId(second.getId());
        second.setSharedLoginPeerProfileId(first.getId());
        assertTrue(ProfileService.obtain().persistAccount(first));
        assertTrue(ProfileService.obtain().persistAccount(second));

        AccountDescriptor loadedFirst = ProfileService.obtain().fetchAllAccounts().stream()
                .filter(account -> first.getId().equals(account.getId()))
                .findFirst()
                .orElseThrow();

        TaskQueue queue = new TaskQueue(loadedFirst);
        assertTrue(queue.startSharedLoginHandshakeRecovery("unit-test reconnect"));
        assertTrue(SharedLoginHandshakeCoordinator.shared().isHandshakeActive(loadedFirst.getId()));
        assertEquals(TpDailyTaskEnum.INITIALIZE, queue.getNextQueuedTaskTypes(1).getFirst());
        assertFalse(queue.statusModel.isPaused(), "shared login recovery must not enter reconnect pause");
        assertFalse(SharedLoginPairGate.allowsAutomation(loadedFirst),
                "recovery must gate farming until handshake proves correct characters");
    }

    @Test
    void repeatedInitializeEnqueueCoalescesToOneImmediateTask() {
        AccountDescriptor profile = new AccountDescriptor(
                null, "Coalesce " + UUID.randomUUID(), "31", true, 50L, 30L,
                null, "Solo", null, null, null);
        assertTrue(ProfileService.obtain().createAccount(profile));
        TaskQueue queue = new TaskQueue(profile);

        queue.enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));
        queue.enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));
        queue.enqueue(DelayedTaskRegistry.create(TpDailyTaskEnum.INITIALIZE, profile));

        assertEquals(1, queue.getNextQueuedTaskTypes(5).size());
        assertEquals(TpDailyTaskEnum.INITIALIZE, queue.getNextQueuedTaskTypes(1).getFirst());
    }

    @Test
    void reconnectRecoveryIgnoredForUnpairedProfile() {
        AccountDescriptor lone = new AccountDescriptor(
                null, "Lone " + UUID.randomUUID(), "13", true, 50L, 30L,
                null, "Solo", null, null, null);
        assertTrue(ProfileService.obtain().createAccount(lone));
        TaskQueue queue = new TaskQueue(lone);
        assertFalse(queue.startSharedLoginHandshakeRecovery("unit-test reconnect"));
        assertTrue(queue.getNextQueuedTaskTypes(1).isEmpty());
    }

    @Test
    void joinIgnoredWhenPairAlreadyReady() {
        AccountDescriptor first = new AccountDescriptor(
                null, "Ready A " + UUID.randomUUID(), "21", true, 50L, 30L,
                null, "CharOne", null, null, null);
        AccountDescriptor second = new AccountDescriptor(
                null, "Ready B " + UUID.randomUUID(), "22", true, 50L, 30L,
                null, "CharTwo", null, null, null);
        assertTrue(ProfileService.obtain().createAccount(first));
        assertTrue(ProfileService.obtain().createAccount(second));
        first.setSharedLoginPeerProfileId(second.getId());
        second.setSharedLoginPeerProfileId(first.getId());
        assertTrue(ProfileService.obtain().persistAccount(first));
        assertTrue(ProfileService.obtain().persistAccount(second));

        AccountDescriptor loadedFirst = ProfileService.obtain().fetchAllAccounts().stream()
                .filter(account -> first.getId().equals(account.getId()))
                .findFirst()
                .orElseThrow();
        AccountDescriptor loadedSecond = ProfileService.obtain().fetchAllAccounts().stream()
                .filter(account -> second.getId().equals(account.getId()))
                .findFirst()
                .orElseThrow();
        SharedLoginPairGate.markReady(SharedLoginRecovery.resolvePair(loadedFirst).orElseThrow());

        TaskQueue queue = new TaskQueue(loadedSecond);
        queue.joinSharedLoginHandshake("unit-test peer kick while ready");
        assertTrue(queue.getNextQueuedTaskTypes(1).isEmpty(),
                "ready pair must ignore peer kick and not re-queue Initialize");
        assertTrue(SharedLoginPairGate.allowsAutomation(loadedFirst));
        assertTrue(SharedLoginPairGate.allowsAutomation(loadedSecond));
    }

    private static final class StubInitialize extends DelayedTask {
        private StubInitialize(AccountDescriptor profile) {
            super(profile, TpDailyTaskEnum.INITIALIZE);
        }

        static DelayedTask maybeCreate(TpDailyTaskEnum kind, AccountDescriptor profile) {
            return kind == TpDailyTaskEnum.INITIALIZE ? new StubInitialize(profile) : null;
        }

        @Override
        protected void execute() {
            // no-op stub for registry-backed enqueue in unit tests
        }
    }
}
