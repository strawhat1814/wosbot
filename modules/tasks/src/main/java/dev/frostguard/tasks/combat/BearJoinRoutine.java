package dev.frostguard.tasks.combat;

import java.time.Instant;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.helper.BearTrapHelper;
import dev.frostguard.engine.schedule.BearBSession;
import dev.frostguard.engine.schedule.BearBSessionStore;

/**
 * Bear B join short task: stay on War, join on +, Solo→Rally on miss/soft-fail,
 * until Own is due or the trap window ends.
 */
public class BearJoinRoutine extends AbstractBearBRoutine {

    public BearJoinRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected String logPrefix() {
        return "BearJoinRoutine";
    }

    @Override
    protected void execute() {
        hydrateConfiguration();
        if (referenceTrapTime == null) {
            logWarning(logLine("No trap schedule configured"));
            reschedule(java.time.LocalDateTime.now().plusHours(1));
            return;
        }

        BearTrapHelper.WindowResult window = BearTrapHelper.calculateWindow(
                referenceTrapTime.atZone(java.time.ZoneOffset.UTC).toInstant(),
                trapPreparationTime);
        if (window.getState() != BearTrapHelper.WindowState.INSIDE) {
            BearBSessionStore.find(profile.getId()).ifPresent(existing -> this.session = existing);
            if (session != null) {
                finishWindowAndParkNext();
            } else {
                deferToNextWindow();
            }
            return;
        }

        if (!ensureWindowSession()) {
            deferToNextWindow();
            return;
        }

        try {
            // Join waits for prep (owned by first Own run). If Own is disabled, Join does prep.
            if (!session.isPrepDone()) {
                if (!callOwnRally) {
                    initializeOcr();
                    runPreparationIfNeeded();
                } else {
                    logInfo(logLine("Waiting for Own prep to finish"));
                    rescheduleInsideWindow(5);
                    return;
                }
            }

            if (!windowStillOpen()) {
                finishWindowAndParkNext();
                return;
            }

            // Prefer Own when due, but stop yielding after consecutive Own fails
            // so Join is not starved while Own keeps missing Rally/Deploy/OCR.
            if (callOwnRally && shouldLaunchOwnNow()) {
                if (session.shouldJoinYieldToOwn()) {
                    logInfo(logLine("Own rally due; yielding (ownFails="
                            + session.consecutiveOwnFails() + "/"
                            + BearBSession.OWN_FAIL_JOIN_YIELD_CAP + ")"));
                    rescheduleInsideWindow(3);
                    return;
                }
                logInfo(logLine("Own rally due but failing; joining anyway (ownFails="
                        + session.consecutiveOwnFails() + ")"));
            }

            if (!joinRally) {
                logInfo(logLine("Join disabled"));
                if (!windowStillOpen()) {
                    finishWindowAndParkNext();
                } else {
                    rescheduleInsideWindow(insideRetrySeconds());
                }
                return;
            }

            // After activation only — prep may finish before activation.
            if (Instant.now().isBefore(session.activationUtc().toInstant(java.time.ZoneOffset.UTC))) {
                long wait = java.time.Duration.between(
                        Instant.now(),
                        session.activationUtc().toInstant(java.time.ZoneOffset.UTC)).getSeconds();
                rescheduleInsideWindow((int) Math.max(1, wait));
                return;
            }

            // War list + green + / Solo→Rally is the join gate — not free-march OCR.
            // Stay on War until Own is due (or the window ends), then yield.
            openWarAndJoinUntilOwnOrWindowEnd();

            if (!windowStillOpen()) {
                finishWindowAndParkNext();
                return;
            }
            if (callOwnRally && shouldLaunchOwnNow()) {
                rescheduleInsideWindow(3);
            } else {
                rescheduleInsideWindow(5);
            }
        } catch (Exception e) {
            logError(logLine("Issue during join: " + e.getMessage()), e);
            if (windowStillOpen()) {
                rescheduleInsideWindow(insideRetrySeconds());
            } else {
                finishWindowAndParkNext();
            }
        }
    }
}
