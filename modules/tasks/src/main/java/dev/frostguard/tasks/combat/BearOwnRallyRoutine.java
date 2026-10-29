package dev.frostguard.tasks.combat;

import java.util.OptionalLong;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.engine.schedule.BearBTiming;
import dev.frostguard.engine.helper.BearTrapHelper;

/**
 * Bear B own-rally short task: prep on first run, deploy on cadence, never parks 48h mid-window.
 */
public class BearOwnRallyRoutine extends AbstractBearBRoutine {

    public BearOwnRallyRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected String logPrefix() {
        return "BearOwnRallyRoutine";
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
            deferToNextWindow();
            return;
        }

        if (!ensureWindowSession()) {
            deferToNextWindow();
            return;
        }

        try {
            initializeOcr();
            runPreparationIfNeeded();

            if (!windowStillOpen()) {
                finishWindowAndParkNext();
                return;
            }

            if (!callOwnRally) {
                logInfo(logLine("Own rally disabled; yielding to join"));
                rescheduleInsideWindow(insideRetrySeconds());
                return;
            }

            if (session.isOwnStoppedForCutoff()
                    || BearBTiming.shouldStopOwn(secondsRemaining(), session.lockedMarchSeconds())) {
                session.markOwnStoppedForCutoff();
                logInfo(logLine("Own rally stopped for cutoff; join continues"));
                // Stay scheduled lightly so reconnect still sees Own task until window end cleanup by Join.
                rescheduleInsideWindow((int) Math.max(insideRetrySeconds(), secondsRemaining()));
                return;
            }

            int delayLeft = secondsUntilOwnStartDelay();
            if (delayLeft > 0) {
                logInfo(logLine("Own start delay: waiting " + delayLeft + "s"));
                rescheduleInsideWindow(delayLeft);
                return;
            }

            if (!shouldLaunchOwnNow()) {
                long waitBusy = java.time.Duration.between(
                        java.time.Instant.now(),
                        session.ownBusyUntil().isAfter(session.nextOwnAt())
                                ? session.ownBusyUntil()
                                : session.nextOwnAt()).getSeconds();
                rescheduleInsideWindow((int) Math.max(5, Math.min(waitBusy, secondsRemaining())));
                return;
            }

            OptionalLong deployed = deployOwnRally();
            if (deployed.isPresent()) {
                markOwnSuccess(deployed.getAsLong());
                int cycle = BearBTiming.ownCycleSeconds(session.lockedMarchSeconds().isPresent()
                        ? session.lockedMarchSeconds()
                        : deployed);
                rescheduleInsideWindow(cycle);
            } else {
                session.recordOwnFail();
                logWarning(logLine("Own rally attempt failed (consecutive="
                        + session.consecutiveOwnFails() + "); retrying inside window"));
                rescheduleInsideWindow(insideRetrySeconds());
            }
        } catch (Exception e) {
            logError(logLine("Issue during own rally: " + e.getMessage()), e);
            if (windowStillOpen()) {
                rescheduleInsideWindow(insideRetrySeconds());
            } else {
                finishWindowAndParkNext();
            }
        }
    }
}
