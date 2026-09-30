package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;

import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
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
    private static final int FALLBACK_RESCHEDULE_MINUTES = 5;
    private static final int ROW_TIMER_LEFT_OFFSET = 70;
    private static final int ROW_TIMER_RIGHT_OFFSET = 280;
    private static final int ROW_TIMER_HALF_HEIGHT = 22;

    static final OcrSettingsData ROW_TIMER_OCR_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .stripBackground(true)
            .setTextColor(new Color(255, 255, 255))
            .charWhitelist("0123456789:")
            .build();

    private ResilientOcrExecutor<LocalDateTime> textHelper;
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
        if (row.isFound() && row.getPoint() != null) {
            AreaData timerArea = rowTimerArea(row);
            LocalDateTime cooldown = textHelper.attemptRecognition(
                    timerArea.topLeft(),
                    timerArea.bottomRight(),
                    TIMER_OCR_MAX_ATTEMPTS,
                    200L,
                    ROW_TIMER_OCR_SETTINGS,
                    GameTimeUtils::isAcceptedFormat,
                    text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
            if (cooldown == null) {
                logWarning("Row timer OCR empty for " + destination + "; using fallback.");
                return LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
            }
            long secondsDiff = Duration.between(LocalDateTime.now(), cooldown).getSeconds();
            if (secondsDiff > MAX_TIMER_SECONDS) {
                logWarning(String.format(
                        "Row timer exceeds 2 hours (%d min) for %s; using 1 hour fallback.",
                        secondsDiff / 60, destination));
                return LocalDateTime.now().plusHours(1);
            }
            logInfo("Row cooldown for " + destination + ": " + GameTimeUtils.formatCountdown(cooldown));
            return cooldown;
        }
        return LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
    }

    static AreaData rowTimerArea(ImageSearchResultData rowIcon) {
        PointData center = rowIcon.getPoint();
        int top = Math.max(0, center.getY() - ROW_TIMER_HALF_HEIGHT);
        int bottom = center.getY() + ROW_TIMER_HALF_HEIGHT;
        return new AreaData(
                new PointData(center.getX() + ROW_TIMER_LEFT_OFFSET, top),
                new PointData(center.getX() + ROW_TIMER_RIGHT_OFFSET, bottom));
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
