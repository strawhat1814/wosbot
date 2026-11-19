package dev.frostguard.tasks.events;

import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.DeploymentHelper;
import dev.frostguard.engine.helper.NavigationHelper.EventMenu;
import dev.frostguard.engine.helper.NavigationHelper.EventMenuOpenResult;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.tasks.diagnostics.TaskDiagnosticSnapshots;
import dev.frostguard.vision.convert.GameTimeUtils;

/**
 * Endless Trial: read Daily Challenges progress before attacking, Go to
 * Windhowler, Attack/Deploy remaining times while staying on target when
 * possible, dismiss a random post-hit popup only when present, then claim.
 * Windhowler attacks cost no stamina.
 */
public class EndlessTrialEventRoutine extends DelayedTask {

    private static final int MAX_DAILY_ATTACKS = 3;
    private static final long POST_DEPLOY_WAIT_MS = 30_000L;
    private static final long EARLY_POPUP_CHECK_MS = 2_500L;
    private static final Pattern DAILY_PROGRESS = Pattern.compile("([0-3])\\s*/\\s*3");

    /** Daily Challenges entry / counter under the Endless home footer. */
    static final AreaData DAILY_CHALLENGES_AREA = new AreaData(
            new PointData(52, 1143), new PointData(130, 1211));

    /** Counter text band inside the Daily Challenges control. */
    static final AreaData DAILY_PROGRESS_OCR_AREA = new AreaData(
            new PointData(55, 1175), new PointData(128, 1208));

    /**
     * Windhowler sits ~20% above geometric screen center on 720×1280.
     * Center y≈640 → aim near y≈512 (band 430–560).
     */
    static final AreaData TARGET_CENTER_AREA = new AreaData(
            new PointData(300, 430), new PointData(420, 560));

    public EndlessTrialEventRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        clearStaminaDeferral();

        if (eventHelper.isBearRunning()) {
            LocalDateTime when = LocalDateTime.now().plusMinutes(30);
            logInfo("Bear Hunt is running; rescheduling Endless Trial for " + when);
            reschedule(when);
            return;
        }

        logInfo("Endless Trial ignores stamina; starting without stamina pre-check.");

        if (!openEndlessHome()) {
            return;
        }

        int alreadyDone = readDailyChallengeProgress();
        if (alreadyDone < 0) {
            logWarning("Daily Challenges progress OCR failed; assuming 0/" + MAX_DAILY_ATTACKS
                    + " and continuing.");
            alreadyDone = 0;
        } else {
            logInfo("Daily Challenges progress before attacks: " + alreadyDone + "/"
                    + MAX_DAILY_ATTACKS + ".");
        }

        if (alreadyDone >= MAX_DAILY_ATTACKS) {
            logInfo("Endless Trial already complete for today; claiming without attacking.");
            int claimed = claimAvailableChallenges();
            if (claimed > 0) {
                logInfo("Claimed Endless Trial daily challenges (claim taps=" + claimed + ").");
            }
            rescheduleExact(GameTimeUtils.dailyResetTime());
            return;
        }

        if (!findGoButton().isFound()) {
            String snapshot = diagnosticSnapshot("home-no-go");
            logWarning("Endless Trial home missing Go after navigation. " + snapshot);
            reschedule(LocalDateTime.now().plusMinutes(5));
            return;
        }

        int attacksNeeded = MAX_DAILY_ATTACKS - alreadyDone;
        int attacksLanded = 0;
        boolean onWorldNearTarget = false;

        while (attacksLanded < attacksNeeded) {
            if (!onWorldNearTarget) {
                if (!travelToTargetFromEvent()) {
                    return;
                }
                onWorldNearTarget = true;
            }

            AttackOutcome attack = attackAndDeployFromWorld();
            if (attack == AttackOutcome.LEFT_AREA) {
                logInfo("Target card not in place; re-entering Endless Trial via Events.");
                onWorldNearTarget = false;
                if (!openEndlessHome()) {
                    return;
                }
                continue;
            }
            if (attack == AttackOutcome.UNKNOWN || attack == AttackOutcome.BLOCKED) {
                return;
            }

            attacksLanded++;
            logInfo("Endless Trial attack " + (alreadyDone + attacksLanded) + "/"
                    + MAX_DAILY_ATTACKS + " deployed this day (" + attacksLanded + "/"
                    + attacksNeeded + " this visit).");

            waitAfterDeployAndDismissPopupIfPresent();

            if (attacksLanded >= attacksNeeded) {
                break;
            }

            if (findAttackButton().isFound()) {
                continue;
            }
            dismissDamagePopupIfPresent();
            tapInside(TARGET_CENTER_AREA);
            sleepTask(1500);
            dismissDamagePopupIfPresent();
            if (findAttackButton().isFound()) {
                continue;
            }
            onWorldNearTarget = false;
        }

        if (!openEndlessHome()) {
            return;
        }
        int claimed = claimAvailableChallenges();
        if (claimed > 0) {
            logInfo("Claimed Endless Trial daily challenges after attacks (claim taps="
                    + claimed + ").");
        }
        if (alreadyDone + attacksLanded >= MAX_DAILY_ATTACKS) {
            logInfo("Endless Trial daily attacks finished. Rescheduling for daily reset.");
            rescheduleExact(GameTimeUtils.dailyResetTime());
            return;
        }

        logInfo("Endless Trial visit ended after " + attacksLanded
                + " attack(s); retrying in 5 minutes.");
        reschedule(LocalDateTime.now().plusMinutes(5));
    }

    private boolean openEndlessHome() {
        logInfo("Navigating to Endless Trial event...");
        EventMenuOpenResult opened = navigationHelper.openEventMenu(EventMenu.ENDLESS_TRIAL);
        if (opened != EventMenuOpenResult.REACHED) {
            String reason = opened == EventMenuOpenResult.PANEL_CLOSED
                    ? "Events panel did not open"
                    : "Endless Trial tab was not in view";
            String snapshot = diagnosticSnapshot(opened == EventMenuOpenResult.PANEL_CLOSED
                    ? "event-panel" : "event-navigation");
            logWarning(reason + ". " + snapshot);
            // Tab misses are often carousel position; give Events time and retry later.
            long retryMinutes = opened == EventMenuOpenResult.TAB_ABSENT ? 15 : 5;
            reschedule(LocalDateTime.now().plusMinutes(retryMinutes));
            if (opened == EventMenuOpenResult.TAB_ABSENT) {
                sleepTask(300);
                pressBack();
            }
            return false;
        }
        sleepTask(1500);
        return true;
    }

    private boolean travelToTargetFromEvent() {
        ImageSearchResultData go = findGoButton();
        if (!go.isFound()) {
            if (!openEndlessHome()) {
                return false;
            }
            go = findGoButton();
        }
        if (!go.isFound()) {
            String snapshot = diagnosticSnapshot("go-missing");
            logWarning("Endless Trial Go button missing. " + snapshot);
            reschedule(LocalDateTime.now().plusMinutes(5));
            return false;
        }
        logInfo("Tapping Endless Trial Go.");
        tapInside(go);
        sleepTask(4000);
        dismissDamagePopupIfPresent();
        return true;
    }

    private AttackOutcome attackAndDeployFromWorld() {
        dismissDamagePopupIfPresent();
        ImageSearchResultData attack = findAttackButton();
        if (!attack.isFound()) {
            tapInside(TARGET_CENTER_AREA);
            sleepTask(1500);
            dismissDamagePopupIfPresent();
            attack = findAttackButton();
        }
        if (!attack.isFound()) {
            return AttackOutcome.LEFT_AREA;
        }

        logInfo("Tapping Endless Trial Attack.");
        tapInside(attack);
        sleepTask(1200);

        ImageSearchResultData deploy = findDeployButton();
        if (!deploy.isFound()) {
            if (deploymentHelper.isMarchQueueFull()) {
                logWarning("Endless Trial march queue full after Attack. Retrying in 2 minutes.");
                reschedule(LocalDateTime.now().plusMinutes(2));
                return AttackOutcome.BLOCKED;
            }
            String snapshot = diagnosticSnapshot("deploy-missing");
            logWarning("Deploy screen missing after Attack. " + snapshot);
            reschedule(LocalDateTime.now().plusMinutes(5));
            return AttackOutcome.UNKNOWN;
        }

        if (deploymentHelper.hasNoDeployableTroops()) {
            logWarning("Endless Trial deploy blocked by troops. No march sent.");
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(10));
            return AttackOutcome.BLOCKED;
        }

        tapInside(deploy);
        sleepTask(2000);

        if (deploymentHelper.isSameTargetDialog()) {
            logWarning("Another march already targets Windhowler. Cancelling deploy.");
            pressBack();
            pressBack();
            reschedule(LocalDateTime.now().plusMinutes(2));
            return AttackOutcome.BLOCKED;
        }

        DeploymentHelper.LaunchCheck launchCheck = deploymentHelper.verifyLaunchTransition();
        if (launchCheck != DeploymentHelper.LaunchCheck.WORLD_VERIFIED) {
            String snapshot = diagnosticSnapshot("deployment-unverified");
            logWarning("Endless Trial deploy not verified (" + launchCheck + "). " + snapshot);
            reschedule(LocalDateTime.now().plusMinutes(5));
            return AttackOutcome.UNKNOWN;
        }

        return AttackOutcome.DEPLOYED;
    }

    /**
     * Waits for the hit. The great-damage popup is random — only Back when Attack
     * is hidden (popup likely present); never Back when Attack is already visible.
     */
    private void waitAfterDeployAndDismissPopupIfPresent() {
        sleepTask(EARLY_POPUP_CHECK_MS);
        dismissDamagePopupIfPresent();
        long remaining = POST_DEPLOY_WAIT_MS - EARLY_POPUP_CHECK_MS;
        if (remaining > 0) {
            sleepTask(remaining);
        }
        dismissDamagePopupIfPresent();
    }

    /**
     * Reads Daily Challenges {@code n/3} on the Endless home footer.
     *
     * @return 0–3 inclusive, or -1 when unreadable
     */
    int readDailyChallengeProgress() {
        OcrSettingsData white = OcrSettingsData.assembler()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .stripBackground(true)
                .setTextColor(java.awt.Color.WHITE)
                .charWhitelist("0123456789/")
                .build();
        String raw = stringHelper.attemptRecognition(
                DAILY_PROGRESS_OCR_AREA.topLeft(),
                DAILY_PROGRESS_OCR_AREA.bottomRight(),
                4,
                200L,
                white,
                text -> DAILY_PROGRESS.matcher(text == null ? "" : text).find(),
                text -> text);
        if (raw == null) {
            return -1;
        }
        Matcher match = DAILY_PROGRESS.matcher(raw);
        if (!match.find()) {
            return -1;
        }
        return Integer.parseInt(match.group(1));
    }

    /**
     * Opens Daily Challenges and taps green Claim. One Claim gathers ready rows;
     * a second pass covers a leftover green button if present.
     */
    int claimAvailableChallenges() {
        ImageSearchResultData completion = findCompletionEntry();
        if (completion.isFound()) {
            tapInside(completion);
        } else {
            tapInside(DAILY_CHALLENGES_AREA);
        }
        sleepTask(1500);

        int claimed = 0;
        for (int i = 0; i < 2; i++) {
            ImageSearchResultData claim = findClaimButton();
            if (!claim.isFound()) {
                break;
            }
            tapInside(claim);
            claimed++;
            sleepTask(1500);
        }

        pressBack();
        sleepTask(800);
        return claimed;
    }

    /**
     * Dismisses the random post-hit overlay only when Attack is not visible.
     * No-op when Attack is already on screen (popup absent).
     */
    private void dismissDamagePopupIfPresent() {
        if (findAttackButton().isFound()) {
            return;
        }
        logInfo("Attack control hidden; dismissing possible Endless damage popup with Back.");
        pressBack();
        sleepTask(600);
    }

    ImageSearchResultData findGoButton() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.ENDLESS_TRIAL_GO_BUTTON,
                SearchConfig.builder().withThreshold(85).withMaxAttempts(3).build());
    }

    ImageSearchResultData findAttackButton() {
        ImageSearchResultData endless = templateSearchHelper.locatePattern(
                TemplatesEnum.ENDLESS_TRIAL_ATTACK_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (endless.isFound()) {
            return endless;
        }
        return templateSearchHelper.locatePattern(
                TemplatesEnum.MERCENARY_ATTACK_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE);
    }

    ImageSearchResultData findDeployButton() {
        ImageSearchResultData endless = templateSearchHelper.locatePattern(
                TemplatesEnum.ENDLESS_TRIAL_DEPLOY_BUTTON,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (endless.isFound()) {
            return endless;
        }
        return templateSearchHelper.locatePattern(
                TemplatesEnum.DEPLOY_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE);
    }

    ImageSearchResultData findCompletionEntry() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.ENDLESS_TRIAL_COMPLETION,
                SearchConfig.builder().withThreshold(75).withMaxAttempts(2).build());
    }

    ImageSearchResultData findClaimButton() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.ENDLESS_TRIAL_CLAIM_BUTTON,
                SearchConfig.builder().withThreshold(85).withMaxAttempts(3).build());
    }

    private String diagnosticSnapshot(String control) {
        return TaskDiagnosticSnapshots.capture(emuManager, EMULATOR_NUMBER, "endlesstrial", control);
    }

    enum AttackOutcome {
        DEPLOYED,
        LEFT_AREA,
        BLOCKED,
        UNKNOWN
    }
}
