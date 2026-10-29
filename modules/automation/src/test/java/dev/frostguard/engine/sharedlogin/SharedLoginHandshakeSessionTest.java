package dev.frostguard.engine.sharedlogin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import dev.frostguard.api.domain.AccountDescriptor;

class SharedLoginHandshakeSessionTest {

    @AfterEach
    void clearCoordinator() {
        SharedLoginHandshakeCoordinator.shared().clear();
    }

    @Test
    void liveCharOneMakesPeerTheSwitcher() throws Exception {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        assertEquals(SharedLoginDirective.Action.WAIT,
                session.next(2L, new SharedLoginObservation(true, false, null)).action());

        // CharOne live on profile 1 → profile 2 must prep-reconnect then switch to CharTwo.
        assertEquals(SharedLoginDirective.Action.WAIT,
                session.next(1L, new SharedLoginObservation(false, true, "CharOne")).action());
        assertEquals(SharedLoginDirective.Action.PREP_RECONNECT,
                session.next(2L, new SharedLoginObservation(true, false, null)).action());

        session.markPrepReconnectDone(2L);
        // After prep, profile 2 is connected on CharOne and profile 1 shows Reconnect.
        session.next(2L, new SharedLoginObservation(false, true, "CharOne"));
        assertEquals(SharedLoginDirective.Action.ARM_RECONNECT_AND_COMMIT,
                session.next(1L, new SharedLoginObservation(true, false, null)).action());
        SharedLoginDirective prepare = session.next(
                2L, new SharedLoginObservation(false, true, "CharOne"));
        assertEquals(SharedLoginDirective.Action.PREPARE_SWITCH_AND_COMMIT, prepare.action());
        assertEquals("CharTwo", prepare.targetCharacterName());

        assertTrue(runBarrier(session));
        session.markCommitFinished();
        assertEquals(SharedLoginDirective.Action.VERIFY,
                session.next(1L, new SharedLoginObservation(false, true, "CharOne")).action());
        session.reportVerify(1L, true);
        assertEquals(SharedLoginDirective.Action.DONE, session.reportVerify(2L, true).action());
    }

    @Test
    void liveCharTwoMakesOwnerTheSwitcherWithoutPrep() {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        session.next(2L, new SharedLoginObservation(true, false, null));
        SharedLoginDirective switcher = session.next(
                1L, new SharedLoginObservation(false, true, "CharTwo"));
        assertEquals(SharedLoginDirective.Action.PREPARE_SWITCH_AND_COMMIT, switcher.action());
        assertEquals("CharOne", switcher.targetCharacterName());

        SharedLoginDirective waiter = session.next(2L, new SharedLoginObservation(true, false, null));
        assertEquals(SharedLoginDirective.Action.ARM_RECONNECT_AND_COMMIT, waiter.action());
    }

    @Test
    void unknownLiveCharacterWaitsAndRereadsInsteadOfFailing() {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        session.next(2L, new SharedLoginObservation(true, false, null));
        SharedLoginDirective wait = session.next(
                1L, new SharedLoginObservation(false, true, "SomeoneElse"));
        assertEquals(SharedLoginDirective.Action.WAIT, wait.action());
        assertTrue(wait.detail().contains("unmatched"));
        assertFalse(session.isFailed());

        // Trusted re-read continues the normal assignment path.
        SharedLoginDirective switcher = session.next(
                1L, new SharedLoginObservation(false, true, "CharTwo"));
        assertEquals(SharedLoginDirective.Action.PREPARE_SWITCH_AND_COMMIT, switcher.action());
    }

    @Test
    void bothAlreadyHomeGoesToVerify() {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        session.next(1L, new SharedLoginObservation(false, true, "CharOne"));
        SharedLoginDirective verify = session.next(
                2L, new SharedLoginObservation(false, true, "CharTwo"));
        assertEquals(SharedLoginDirective.Action.VERIFY, verify.action());
    }

    @Test
    void bothReconnectBootstrapsLowerIdThenResumesAssignment() {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        session.next(2L, new SharedLoginObservation(true, false, null));
        SharedLoginDirective bootstrap = session.next(
                1L, new SharedLoginObservation(true, false, null));
        assertEquals(SharedLoginDirective.Action.PREP_RECONNECT, bootstrap.action());
        assertTrue(bootstrap.detail().contains("bootstrap"));

        assertEquals(SharedLoginDirective.Action.WAIT,
                session.next(2L, new SharedLoginObservation(true, false, null)).action());

        session.markPrepReconnectDone(1L);
        session.next(1L, new SharedLoginObservation(false, true, "CharOne"));
        SharedLoginDirective peer = session.next(
                2L, new SharedLoginObservation(true, false, null));
        assertEquals(SharedLoginDirective.Action.PREP_RECONNECT, peer.action(),
                "after bootstrap, peer on Reconnect should prep for switch to CharTwo");
    }

    @Test
    void inconclusivePeerPollDoesNotForceSwitchWhileOtherIsHome() {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        // Peer joins with an inconclusive poll (home template miss / overlay).
        assertEquals(SharedLoginDirective.Action.WAIT,
                session.next(2L, new SharedLoginObservation(false, false, null)).action());
        // Connected side alone must not assign Switch Character / Reconnect roles.
        SharedLoginDirective alone = session.next(
                1L, new SharedLoginObservation(false, true, "CharOne"));
        assertEquals(SharedLoginDirective.Action.WAIT, alone.action());
        assertTrue(alone.detail().contains("home or Reconnect"));

        // Once peer reports home on its own character, both-correct → VERIFY.
        SharedLoginDirective verify = session.next(
                2L, new SharedLoginObservation(false, true, "CharTwo"));
        assertEquals(SharedLoginDirective.Action.VERIFY, verify.action());
    }

    @Test
    void inconclusivePollDoesNotErasePriorConclusiveHome() {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        session.next(1L, new SharedLoginObservation(false, true, "CharOne"));
        session.next(2L, new SharedLoginObservation(false, true, "CharTwo"));
        assertEquals(SharedLoginDirective.Action.VERIFY,
                session.next(1L, new SharedLoginObservation(false, true, "CharOne")).action());

        // Transient miss on profile 2 must not drop back into switch assignment.
        SharedLoginDirective stillVerify = session.next(
                2L, new SharedLoginObservation(false, false, null));
        assertEquals(SharedLoginDirective.Action.VERIFY, stillVerify.action());
    }

    @Test
    void verifyMismatchRetriesThenFails() {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);

        session.next(1L, new SharedLoginObservation(false, true, "CharOne"));
        session.next(2L, new SharedLoginObservation(false, true, "CharTwo"));
        session.reportVerify(1L, false);
        SharedLoginDirective retry = session.reportVerify(2L, true);
        assertEquals(SharedLoginDirective.Action.WAIT, retry.action());

        for (int i = 0; i < SharedLoginHandshakeSession.MAX_ATTEMPTS; i++) {
            session.next(1L, new SharedLoginObservation(false, true, "CharOne"));
            session.next(2L, new SharedLoginObservation(false, true, "CharTwo"));
            session.reportVerify(1L, false);
            retry = session.reportVerify(2L, false);
        }
        assertEquals(SharedLoginDirective.Action.FAILED, retry.action());
    }

    @Test
    void abortFailsBothSidesAndReleasesBarrierWaiter() throws Exception {
        SharedLoginPair pair = pair(1L, "CharOne", 2L, "CharTwo");
        SharedLoginHandshakeSession session = new SharedLoginHandshakeSession(pair);
        session.next(2L, new SharedLoginObservation(true, false, null));
        session.next(1L, new SharedLoginObservation(false, true, "CharTwo"));

        AtomicBoolean waiterOk = new AtomicBoolean(true);
        Thread waiter = new Thread(() -> {
            try {
                waiterOk.set(session.awaitCommitBarrier(2L, false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(200);
        assertEquals(SharedLoginDirective.Action.FAILED,
                session.abort("Switcher could not open Settings").action());
        waiter.join(5_000);
        assertTrue(session.isFailed());
        assertFalse(waiterOk.get(), "waiter must exit barrier as failed after abort");
        assertEquals(SharedLoginDirective.Action.FAILED,
                session.next(1L, new SharedLoginObservation(false, true, "CharTwo")).action());
    }

    private static boolean runBarrier(SharedLoginHandshakeSession session) throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        AtomicBoolean switcherOk = new AtomicBoolean();
        AtomicBoolean waiterOk = new AtomicBoolean();
        Thread switcher = new Thread(() -> {
            try {
                started.countDown();
                started.await(5, TimeUnit.SECONDS);
                switcherOk.set(session.awaitCommitBarrier(2L, true));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        Thread waiter = new Thread(() -> {
            try {
                started.countDown();
                started.await(5, TimeUnit.SECONDS);
                waiterOk.set(session.awaitCommitBarrier(1L, false));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        switcher.start();
        waiter.start();
        switcher.join(5_000);
        waiter.join(5_000);
        return switcherOk.get() && waiterOk.get();
    }

    private static SharedLoginPair pair(long aId, String aName, long bId, String bName) {
        AccountDescriptor a = new AccountDescriptor(
                aId, "A", "1", true, 50L, 0L, null, aName, null, null, bId);
        AccountDescriptor b = new AccountDescriptor(
                bId, "B", "2", true, 50L, 0L, null, bName, null, null, aId);
        return SharedLoginPair.resolve(a, Map.of(aId, a, bId, b)::get).orElseThrow();
    }
}
