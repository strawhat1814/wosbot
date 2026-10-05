package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.SidebarNavigator;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

/**
 * Claims the Storehouse chest via the Daily sidebar Online Rewards row on World.
 */
public class StorehouseChestRoutine extends DelayedTask {

    private static final int TIMER_OCR_MAX_ATTEMPTS = 3;
    private static final int MAX_TIMER_SECONDS = 7200;
    private static final int MIN_PLAUSIBLE_TIMER_SECONDS = 60;
    private static final int FALLBACK_RESCHEDULE_MINUTES = 5;
    private static final int CLAIM_INTERVAL_HOURS = 1;

    /** Mid-row offsets for the green status line under the row title. */
    private static final int STATUS_LEFT_OFFSET = 70;
    private static final int STATUS_RIGHT_OFFSET = 300;
    private static final int STATUS_TOP_OFFSET = 4;
    private static final int STATUS_BOTTOM_OFFSET = 36;

    /**
     * Green row status under the title. "Complete" / "Completed" means the daily
     * objective is done and the sidebar checkmark is ready to tap — not that the
     * reward was already collected. Measured on live My Rewards rows (~0,190,0).
     */
    static final OcrSettingsData ROW_STATUS_OCR_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .stripBackground(true)
            .setTextColor(new Color(0, 190, 0))
            .charWhitelist("CompletedComplet0123456789: ")
            .build();

    /** White countdown that replaces Claim in the action slot while cooling down. */
    static final OcrSettingsData ROW_TIMER_OCR_SETTINGS = CommonOCRSettings.TRAVEL_TIME_SETTINGS;

    private ResilientOcrExecutor<LocalDateTime> textHelper;
    private ResilientOcrExecutor<String> statusHelper;
    private LocalDateTime nextChestTime;

    public StorehouseChestRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    private void loadConfiguration() {
        textHelper = new ResilientOcrExecutor<>(provider);
        statusHelper = new ResilientOcrExecutor<>(provider);
        logDebug("Configuration loaded");
    }

    private void resetExecutionState() {
        nextChestTime = null;
        logDebug("Execution state reset");
    }

    @Override
    protected void execute() {
        loadConfiguration();
        resetExecutionState();
        claimOnlineRewardsChest();
        navigationHelper.closeSidebar();
        scheduleNext();
    }

    private void claimOnlineRewardsChest() {
        logInfo("Claiming Storehouse chest through Daily Online Rewards on World.");
        if (navigationHelper.navigateToSidebarDestination(SidebarDestination.ONLINE_REWARDS)) {
            sleepTask(2000L);
            logInfo("Online Rewards Claim activated; chest should auto-collect on World.");
            nextChestTime = LocalDateTime.now().plusHours(1);
            StatisticsService.obtain().addToCounter(profile, "Storehouse Chests Claimed", 1);
        } else {
            logWarning("Online Rewards Claim was unavailable (missing, hidden, or on cooldown).");
            nextChestTime = readRowCooldownOrFallback(SidebarDestination.ONLINE_REWARDS);
        }
    }

    private LocalDateTime readRowCooldownOrFallback(SidebarDestination destination) {
        ImageSearchResultData row = navigationHelper.findSidebarDestinationRow(destination);
        if (!row.isFound() || row.getPoint() == null) {
            return LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
        }
        return interpretUnavailableRow(row, LocalDateTime.now().plusHours(CLAIM_INTERVAL_HOURS));
    }

    /**
     * Reads the green mid-row status and/or white Claim-slot countdown after Claim
     * was not tappable. Prefers an explicit Complete/Completed status over guessing.
     */
    LocalDateTime interpretUnavailableRow(ImageSearchResultData row, LocalDateTime completedFallback) {
        String status = statusHelper.attemptRecognition(
                rowStatusArea(row).topLeft(),
                rowStatusArea(row).bottomRight(),
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                ROW_STATUS_OCR_SETTINGS,
                text -> text != null && !text.isBlank(),
                String::trim);
        if (status != null) {
            logInfo("Row status OCR for storehouse row: '" + status + "'");
            if (GameTimeUtils.isAcceptedFormat(status)) {
                LocalDateTime fromStatus = LocalDateTime.now().plus(GameTimeUtils.parseDuration(status));
                if (isPlausibleCountdown(fromStatus)) {
                    logInfo("Row status countdown: " + GameTimeUtils.formatCountdown(fromStatus));
                    return fromStatus;
                }
            }
            if (isClaimReadyStatus(status)) {
                logWarning("Storehouse row is claim-ready (Complete) but Claim was not activated; retrying soon.");
                return LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
            }
        }

        AreaData timerArea = rowTimerArea(row);
        LocalDateTime cooldown = textHelper.attemptRecognition(
                timerArea.topLeft(),
                timerArea.bottomRight(),
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                ROW_TIMER_OCR_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
        if (cooldown != null && isPlausibleCountdown(cooldown)) {
            logInfo("Row action-slot cooldown: " + GameTimeUtils.formatCountdown(cooldown));
            return cooldown;
        }
        if (cooldown != null) {
            long seconds = Duration.between(LocalDateTime.now(), cooldown).getSeconds();
            logWarning(String.format(
                    "Ignoring implausible action-slot countdown (%d s); using fallback.", seconds));
        } else {
            logWarning("Row timer OCR empty; using fallback claim interval.");
        }
        return completedFallback;
    }

    /** Green "Complete"/"Completed" on My Rewards rows — tap the sidebar checkmark to collect. */
    static boolean isClaimReadyStatus(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String letters = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        return letters.contains("complete");
    }

    static boolean isPlausibleCountdown(LocalDateTime cooldown) {
        long seconds = Duration.between(LocalDateTime.now(), cooldown).getSeconds();
        return seconds >= MIN_PLAUSIBLE_TIMER_SECONDS && seconds <= MAX_TIMER_SECONDS;
    }

    /**
     * Green status / optional green countdown under the row title.
     */
    static AreaData rowStatusArea(ImageSearchResultData rowIcon) {
        PointData center = rowIcon.getPoint();
        if (center == null) {
            throw new IllegalArgumentException("A located row icon is required");
        }
        return AreaData.of(
                center.getX() + STATUS_LEFT_OFFSET,
                center.getY() + STATUS_TOP_OFFSET,
                center.getX() + STATUS_RIGHT_OFFSET,
                center.getY() + STATUS_BOTTOM_OFFSET);
    }

    /**
     * White countdown that replaces Claim in the right-hand action slot.
     */
    static AreaData rowTimerArea(ImageSearchResultData rowIcon) {
        return SidebarNavigator.rowActionAreaFor(rowIcon);
    }

    static LocalDateTime nextChestVisit(LocalDateTime now, LocalDateTime nextChestTime) {
        LocalDateTime chest = nextChestTime;
        if (chest != null && chest.isBefore(now)) {
            chest = null;
        }
        return chest != null ? chest : now.plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
    }

    private void scheduleNext() {
        LocalDateTime now = LocalDateTime.now();
        if (nextChestTime != null && nextChestTime.isBefore(now)) {
            nextChestTime = null;
        }

        LocalDateTime nextReset = GameTimeUtils.dailyResetTime();
        if (nextChestTime != null && nextChestTime.isAfter(nextReset)) {
            logInfo("Chest time exceeds reset, capping at reset time.");
            nextChestTime = nextReset;
        }

        LocalDateTime scheduledTime = nextChestVisit(now, nextChestTime);
        String reason = nextChestTime != null ? "chest claim" : "No valid times (fallback)";
        logInfo(String.format("Rescheduling for %s at: %s", reason, scheduledTime.format(DATETIME_FORMATTER)));
        reschedule(scheduledTime);
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.WORLD;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return true;
    }
}
