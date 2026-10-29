package dev.frostguard.engine.sharedlogin;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * In-process state machine for one shared-login pair during initialization.
 */
public final class SharedLoginHandshakeSession {

    public static final Duration PEER_WAIT_TIMEOUT = Duration.ofMinutes(3);
    public static final Duration BARRIER_TIMEOUT = Duration.ofSeconds(45);
    public static final Duration SESSION_TIMEOUT = Duration.ofMinutes(8);
    public static final int MAX_ATTEMPTS = 3;

    enum Phase {
        WAITING_PEER,
        OBSERVING,
        PREP,
        COMMIT,
        VERIFY,
        DONE,
        FAILED
    }

    private final SharedLoginPair pair;
    private final Instant startedAt = Instant.now();

    private Phase phase = Phase.WAITING_PEER;
    private int attempt;
    private String failureReason;

    private SharedLoginObservation firstObservation;
    private SharedLoginObservation secondObservation;
    private SharedLoginHandshakeDecision.Assignment assignment;

    private final AtomicReference<CountDownLatch> barrier = new AtomicReference<>();
    private volatile boolean switcherArmed;
    private volatile boolean waiterArmed;
    private volatile Boolean firstVerifyOk;
    private volatile Boolean secondVerifyOk;

    SharedLoginHandshakeSession(SharedLoginPair pair) {
        this.pair = Objects.requireNonNull(pair, "pair");
    }

    public SharedLoginPair pair() {
        return pair;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean isTerminal() {
        return phase == Phase.DONE || phase == Phase.FAILED;
    }

    public synchronized boolean isFailed() {
        return phase == Phase.FAILED;
    }

    public synchronized String failureReason() {
        return failureReason;
    }

    public synchronized boolean contains(long profileId) {
        return pair.contains(profileId);
    }

    public synchronized SharedLoginDirective next(long profileId, SharedLoginObservation observation) {
        if (timedOut()) {
            return fail("Shared login handshake timed out");
        }
        if (phase == Phase.DONE) {
            return SharedLoginDirective.done("Handshake already completed");
        }
        if (phase == Phase.FAILED) {
            return SharedLoginDirective.failed(failureReason == null ? "Handshake failed" : failureReason);
        }

        storeObservation(profileId, observation);

        if (phase == Phase.WAITING_PEER) {
            if (firstObservation == null || secondObservation == null) {
                if (Instant.now().isAfter(startedAt.plus(PEER_WAIT_TIMEOUT))) {
                    return fail("Peer did not join handshake within " + PEER_WAIT_TIMEOUT.toMinutes() + " minutes");
                }
                return SharedLoginDirective.wait("Waiting for shared-login peer to join handshake");
            }
            phase = Phase.OBSERVING;
        }

        if (phase == Phase.OBSERVING || phase == Phase.PREP) {
            return advanceFromObservations(profileId);
        }

        if (phase == Phase.COMMIT) {
            return commitDirective(profileId);
        }

        if (phase == Phase.VERIFY) {
            return SharedLoginDirective.verify("Confirm owned character after shared login commit");
        }

        return SharedLoginDirective.wait("Shared login handshake idle");
    }

    public synchronized void markPrepReconnectDone(long profileId) {
        // Force a fresh observation cycle after the switcher claims the session.
        // Clear both sides: the previous connected observation is stale once prep kicks the peer.
        firstObservation = null;
        secondObservation = null;
        phase = Phase.OBSERVING;
        assignment = null;
        resetBarrier();
    }

    public boolean awaitCommitBarrier(long profileId, boolean switcher) throws InterruptedException {
        if (isFailed()) {
            return false;
        }
        CountDownLatch latch = ensureBarrier();
        synchronized (this) {
            if (phase == Phase.FAILED) {
                return false;
            }
            if (switcher) {
                if (!switcherArmed) {
                    switcherArmed = true;
                    latch.countDown();
                }
            } else if (!waiterArmed) {
                waiterArmed = true;
                latch.countDown();
            }
        }
        boolean released = latch.await(BARRIER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (isFailed()) {
            return false;
        }
        if (!released) {
            synchronized (this) {
                fail("Connect/Reconnect barrier timed out");
            }
        }
        return released && !isFailed();
    }

    /**
     * Marks the handshake failed for both sides and releases any barrier waiters.
     */
    public synchronized SharedLoginDirective abort(String reason) {
        return fail(reason);
    }

    public synchronized void markCommitFinished() {
        if (phase == Phase.FAILED || phase == Phase.DONE) {
            return;
        }
        phase = Phase.VERIFY;
        firstVerifyOk = null;
        secondVerifyOk = null;
    }

    public synchronized SharedLoginDirective reportVerify(long profileId, boolean ok) {
        if (Objects.equals(pair.first().getId(), profileId)) {
            firstVerifyOk = ok;
        } else {
            secondVerifyOk = ok;
        }
        if (firstVerifyOk == null || secondVerifyOk == null) {
            return SharedLoginDirective.wait("Waiting for peer character verification");
        }
        if (Boolean.TRUE.equals(firstVerifyOk) && Boolean.TRUE.equals(secondVerifyOk)) {
            phase = Phase.DONE;
            return SharedLoginDirective.done("Shared login handshake completed");
        }
        attempt++;
        if (attempt >= MAX_ATTEMPTS) {
            return fail("Owned characters still wrong after " + MAX_ATTEMPTS + " handshake attempts");
        }
        firstObservation = null;
        secondObservation = null;
        assignment = null;
        firstVerifyOk = null;
        secondVerifyOk = null;
        resetBarrier();
        phase = Phase.OBSERVING;
        return SharedLoginDirective.wait("Retrying shared login handshake (attempt " + (attempt + 1) + ")");
    }

    private SharedLoginDirective advanceFromObservations(long profileId) {
        if (firstObservation == null || secondObservation == null) {
            return SharedLoginDirective.wait("Waiting for both sides to report screen state");
        }
        if (!firstObservation.conclusive() || !secondObservation.conclusive()) {
            return SharedLoginDirective.wait(
                    "Waiting for both sides to show home or Reconnect (ignoring inconclusive polls)");
        }

        boolean firstConnected = firstObservation.connected();
        boolean secondConnected = secondObservation.connected();
        if (firstConnected && secondConnected) {
            phase = Phase.VERIFY;
            return SharedLoginDirective.verify(
                    "Both emulators already on home; verifying owned characters");
        }
        if (!firstConnected && !secondConnected) {
            // Both kicked out (Tips / Reconnect). Waiting forever never recovers — pick the
            // lower-id profile to tap Reconnect alone, then re-observe for normal roles.
            long bootstrapId = pair.first().getId();
            phase = Phase.PREP;
            if (Objects.equals(profileId, bootstrapId)) {
                return SharedLoginDirective.prepReconnect(
                        "Both on Reconnect; bootstrap reconnect on " + pair.first().getName());
            }
            return SharedLoginDirective.wait(
                    "Waiting for peer bootstrap reconnect (" + pair.first().getName() + ")");
        }

        Long connectedId = connectedProfileId();
        if (connectedId == null) {
            return fail("Neither emulator is on the home screen for shared login handshake");
        }
        SharedLoginObservation connectedObs = observationOf(connectedId);
        if (connectedObs == null || connectedObs.liveCharacterName() == null
                || connectedObs.liveCharacterName().isBlank()) {
            if (Objects.equals(profileId, connectedId)) {
                return SharedLoginDirective.wait("Connected side must read live character name");
            }
            return SharedLoginDirective.wait("Waiting for connected peer to read live character");
        }

        assignment = SharedLoginHandshakeDecision.assign(pair, connectedId, connectedObs.liveCharacterName());
        if (assignment == null) {
            // Soft OCR often returns garbage ("Be", "Quit Game" fragments). Drop it and retry
            // instead of failing the whole handshake.
            String bad = connectedObs.liveCharacterName();
            clearObservation(connectedId);
            return SharedLoginDirective.wait(
                    "Live character '" + bad + "' unmatched; re-reading connected side");
        }

        SharedLoginObservation switcherObs = observationOf(assignment.switcherProfileId());
        if (switcherObs != null && switcherObs.reconnectVisible()) {
            phase = Phase.PREP;
            if (Objects.equals(profileId, assignment.switcherProfileId())) {
                return SharedLoginDirective.prepReconnect(
                        "Switcher prep reconnect so it can open Switch Character (" + assignment.reason() + ")");
            }
            return SharedLoginDirective.wait("Waiting for switcher prep reconnect (" + assignment.reason() + ")");
        }

        phase = Phase.COMMIT;
        resetBarrier();
        return commitDirective(profileId);
    }

    private SharedLoginDirective commitDirective(long profileId) {
        if (assignment == null) {
            return fail("Missing handshake assignment");
        }
        if (Objects.equals(profileId, assignment.switcherProfileId())) {
            return SharedLoginDirective.prepareSwitch(
                    assignment.switchTargetName(),
                    "Prepare Switch Character confirm; barrier with peer Reconnect. " + assignment.reason());
        }
        if (Objects.equals(profileId, assignment.waiterProfileId())) {
            return SharedLoginDirective.armReconnect(
                    "Arm Reconnect and wait for Connect barrier. " + assignment.reason());
        }
        return SharedLoginDirective.failed("Profile not part of handshake assignment");
    }

    private void storeObservation(long profileId, SharedLoginObservation observation) {
        if (observation == null) {
            return;
        }
        if (Objects.equals(pair.first().getId(), profileId)) {
            firstObservation = mergeObservation(firstObservation, observation);
        } else if (Objects.equals(pair.second().getId(), profileId)) {
            secondObservation = mergeObservation(secondObservation, observation);
        }
    }

    private void clearObservation(long profileId) {
        if (Objects.equals(pair.first().getId(), profileId)) {
            firstObservation = null;
        } else if (Objects.equals(pair.second().getId(), profileId)) {
            secondObservation = null;
        }
    }

    /**
     * Keep the last conclusive home/Reconnect read when a poll is inconclusive
     * (loading overlay, missed home template). An inconclusive first report still
     * marks the peer as joined for {@link Phase#WAITING_PEER}.
     */
    private static SharedLoginObservation mergeObservation(
            SharedLoginObservation previous,
            SharedLoginObservation next) {
        if (next.conclusive()) {
            return next;
        }
        if (previous == null) {
            return next;
        }
        return previous;
    }

    private SharedLoginObservation observationOf(long profileId) {
        if (Objects.equals(pair.first().getId(), profileId)) {
            return firstObservation;
        }
        return secondObservation;
    }

    private Long connectedProfileId() {
        boolean firstConnected = firstObservation != null && firstObservation.connected();
        boolean secondConnected = secondObservation != null && secondObservation.connected();
        if (firstConnected && !secondConnected) {
            return pair.first().getId();
        }
        if (secondConnected && !firstConnected) {
            return pair.second().getId();
        }
        if (firstConnected && secondConnected) {
            // Both briefly look connected after a commit; keep previous assignment path via verify.
            return assignment != null ? assignment.switcherProfileId() : pair.first().getId();
        }
        return null;
    }

    private CountDownLatch ensureBarrier() {
        CountDownLatch existing = barrier.get();
        if (existing != null) {
            return existing;
        }
        CountDownLatch created = new CountDownLatch(2);
        if (barrier.compareAndSet(null, created)) {
            switcherArmed = false;
            waiterArmed = false;
            return created;
        }
        return barrier.get();
    }

    private void resetBarrier() {
        barrier.set(null);
        switcherArmed = false;
        waiterArmed = false;
    }

    private boolean timedOut() {
        return Instant.now().isAfter(startedAt.plus(SESSION_TIMEOUT));
    }

    private SharedLoginDirective fail(String reason) {
        phase = Phase.FAILED;
        failureReason = reason;
        CountDownLatch latch = barrier.getAndSet(null);
        switcherArmed = false;
        waiterArmed = false;
        if (latch != null) {
            // Unblock both barrier waiters so the peer exits immediately.
            latch.countDown();
            latch.countDown();
        }
        return SharedLoginDirective.failed(reason);
    }
}
