package dev.frostguard.tasks.exploration;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TundraTrekAutoRoutine extends DelayedTask {

    private static final PointData UPPER_SCREEN_CLICK = new PointData(360, 200);
    private static final PointData TREK_COUNTER_TOP_LEFT = new PointData(516, 22);
    private static final PointData TREK_COUNTER_BOTTOM_RIGHT = new PointData(610, 60);
    private static final PointData IDLE_BUTTON_ZONE_TAP = new PointData(630, 1180);
    private static final Duration IDLE_DIALOG_TIMEOUT = Duration.ofSeconds(15);

    private static final AreaData IDLE_BUTTON_SEARCH_AREA = AreaData.of(554, 1125, 706, 1236);
    private static final AreaData IDLE_CONFIRM_SEARCH_AREA = AreaData.of(180, 820, 540, 980);
    private static final AreaData END_IDLE_SEARCH_AREA = AreaData.of(80, 650, 640, 1000);

    private static final SearchConfig IDLE_BUTTON_SEARCH = SearchConfig.builder()
            .withMaxAttempts(4)
            .withDelay(250L)
            .withThreshold(82)
            .withArea(IDLE_BUTTON_SEARCH_AREA)
            .build();
    private static final SearchConfig IDLE_CONFIRM_SEARCH = SearchConfig.builder()
            .withMaxAttempts(3)
            .withDelay(250L)
            .withThreshold(85)
            .withArea(IDLE_CONFIRM_SEARCH_AREA)
            .build();
    private static final SearchConfig END_IDLE_SEARCH = SearchConfig.builder()
            .withMaxAttempts(3)
            .withDelay(300L)
            .withThreshold(82)
            .withArea(END_IDLE_SEARCH_AREA)
            .build();

    private static final int[][] OCR_REGION_OFFSETS = {
            { 0, 0 },
            { -2, 1 }, { 2, -1 },
            { 3, 0 }, { -2, -3 }
    };

    public TundraTrekAutoRoutine(AccountDescriptor profile, TpDailyTaskEnum tpTask) {
        super(profile, tpTask);
    }

    @Override
    protected void execute() {
        try {
            if (!navigateToTundraMenu()) {
                rescheduleWithDelay(Duration.ofHours(1), "Failed to navigate to Tundra menu");
                return;
            }

            boolean claimedEndIdle = claimEndIdleTrekIfPresent();
            if (checkIfAlreadyComplete()) {
                return;
            }

            if (!startIdleTrek()) {
                if (claimedEndIdle) {
                    logInfo("Claimed End Idle Trek; Idle start not available. Rescheduling by interval.");
                    pressBack();
                    sleepTask(500);
                    rescheduleAfterConfiguredInterval("end-idle claimed, start unavailable");
                    return;
                }

                rescheduleWithDelay(Duration.ofHours(1), "Failed to start Idle Trek");
                return;
            }

            logInfo("Idle Trek started - returning to gameplay. Rescheduling by interval.");
            sleepTask(1500);
            rescheduleAfterConfiguredInterval("idle started");
        } catch (Exception e) {
            logError("Unexpected error during TundraTrekAuto task: " + e.getMessage(), e);
            rescheduleWithDelay(Duration.ofHours(1), "Unexpected error");
        }
    }

    private boolean navigateToTundraMenu() {
        logInfo("Navigating to Tundra Trek (Daily sidebar)");
        boolean sidebarOk = navigationHelper.navigateToSidebarDestination(SidebarDestination.TUNDRA_TREK);
        if (!sidebarOk) {
            logWarning("Sidebar Go reported failure for TUNDRA_TREK - closing panel and verifying Dawn Academy");
            navigationHelper.closeSidebar();
            sleepTask(2000);
        } else {
            sleepTask(1500);
        }

        if (isSuppliesClaimPanelVisible()) {
            logWarning("Opened Trek Supplies claim panel instead of Dawn Academy - backing out");
            pressBack();
            sleepTask(500);
            return false;
        }

        if (isDawnAcademyVisible()) {
            logInfo("Successfully entered Tundra Trek event");
            return true;
        }

        if (sidebarOk) {
            logInfo("Successfully entered Tundra Trek event");
            return true;
        }

        logWarning("Tundra Trek Daily Go did not open Dawn Academy");
        return false;
    }

    private boolean isSuppliesClaimPanelVisible() {
        return templateSearchHelper.locatePattern(
                TemplatesEnum.TUNDRA_TREK_CLAIM_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE).isFound();
    }

    private boolean isDawnAcademyVisible() {
        if (templateSearchHelper.locatePattern(TemplatesEnum.TUNDRA_TREK_END_IDLE, END_IDLE_SEARCH).isFound()) {
            return true;
        }
        if (findIdleButton().isFound()) {
            return true;
        }
        if (templateSearchHelper.locatePattern(
                TemplatesEnum.TUNDRA_TREK_BAG_BUTTON,
                SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
            return true;
        }
        return readTrekCounterOnce() != null;
    }

    private boolean checkIfAlreadyComplete() {
        Integer remaining = readTrekCounterOnce();
        if (remaining == null) {
            logDebug("Pre-check: Could not read counter. Proceeding with Idle Trek.");
            return false;
        }

        logInfo("Pre-check: Trek counter at " + remaining + "/100");
        if (remaining > 0) {
            return false;
        }

        logInfo("Trek already complete (0/100). Exiting event.");
        pressBack();
        sleepTask(500);
        rescheduleAfterConfiguredInterval("already complete");
        return true;
    }

    private boolean claimEndIdleTrekIfPresent() {
        logInfo("Checking for End Idle Trek claim button");
        ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.TUNDRA_TREK_END_IDLE,
                END_IDLE_SEARCH);
        if (!claimBtn.isFound()) {
            logDebug(String.format("End Idle Trek not visible (best score %.1f)", claimBtn.getMatchScore()));
            return false;
        }

        logInfo(String.format(
                "End Idle Trek found at %s (score %.1f) - claiming",
                claimBtn.getPoint(),
                claimBtn.getMatchScore()));
        tapInside(claimBtn);
        sleepTask(2000);
        tapNear(UPPER_SCREEN_CLICK);
        sleepTask(1500);
        return true;
    }

    private boolean startIdleTrek() {
        logInfo("Starting Idle Trek sequence");
        if (!tapIdleButton()) {
            logWarning("Idle button not found on Tundra Trek screen");
            pressBack();
            sleepTask(500);
            return false;
        }
        if (!waitForIdleDialog()) {
            logWarning("Idle Trek dialog did not appear");
            pressBack();
            sleepTask(500);
            return false;
        }
        if (!confirmIdleTrek()) {
            logWarning("Idle Trek confirm button not found");
            pressBack();
            sleepTask(500);
            return false;
        }
        return true;
    }

    private boolean tapIdleButton() {
        logInfo("Searching for Idle button in bottom-right area");
        ImageSearchResultData idleBtn = findIdleButton();
        if (!idleBtn.isFound()) {
            logInfo("Idle button not visible - trying upper screen tap to reveal controls");
            tapNear(UPPER_SCREEN_CLICK);
            sleepTask(1200);
            idleBtn = findIdleButton();
        }

        if (idleBtn.isFound()) {
            logInfo(String.format(
                    "Idle button found at %s (score %.1f) - opening Idle Trek dialog",
                    idleBtn.getPoint(),
                    idleBtn.getMatchScore()));
            tapInside(idleBtn);
            sleepTask(1200);
            return true;
        }

        logWarning(String.format(
                "Idle template miss (best score %.1f) - tapping Idle zone once at %s",
                idleBtn.getMatchScore(),
                IDLE_BUTTON_ZONE_TAP));
        tapNear(IDLE_BUTTON_ZONE_TAP, 14);
        sleepTask(1200);
        idleBtn = findIdleButton();
        if (idleBtn.isFound()) {
            logInfo(String.format(
                    "Idle button found after zone tap at %s (score %.1f)",
                    idleBtn.getPoint(),
                    idleBtn.getMatchScore()));
            tapInside(idleBtn);
            sleepTask(1200);
            return true;
        }

        return true;
    }

    private ImageSearchResultData findIdleButton() {
        return templateSearchHelper.locatePattern(TemplatesEnum.TUNDRA_TREK_IDLE_BUTTON, IDLE_BUTTON_SEARCH);
    }

    private boolean waitForIdleDialog() {
        LocalDateTime deadline = LocalDateTime.now().plus(IDLE_DIALOG_TIMEOUT);
        while (LocalDateTime.now().isBefore(deadline)) {
            ImageSearchResultData title = templateSearchHelper.locatePattern(
                    TemplatesEnum.TUNDRA_TREK_IDLE_DIALOG_TITLE,
                    SearchConfigConstants.SINGLE_WITH_2_RETRIES);
            if (title.isFound()) {
                logInfo("Idle Trek dialog detected");
                return true;
            }
            sleepTask(800);
        }
        return false;
    }

    private boolean confirmIdleTrek() {
        ImageSearchResultData confirm = templateSearchHelper.locatePattern(
                TemplatesEnum.TUNDRA_TREK_IDLE_CONFIRM,
                IDLE_CONFIRM_SEARCH);
        if (!confirm.isFound()) {
            confirm = templateSearchHelper.locatePattern(
                    TemplatesEnum.TUNDRA_TREK_BAG_BUTTON,
                    IDLE_CONFIRM_SEARCH);
        }

        if (confirm.isFound() && confirm.getPoint() != null) {
            PointData tapPoint = new PointData(confirm.getPoint().getX() + 100, confirm.getPoint().getY());
            logInfo("Confirming Idle Trek at max count");
            tapNear(tapPoint, 12);
            sleepTask(1500);
            return true;
        }
        return false;
    }

    private Integer readTrekCounterOnce() {
        Pattern fractionPattern = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)");
        Pattern twoNumbersPattern = Pattern.compile("(\\d{1,3})\\D+(\\d{2,3})");

        for (int[] offset : OCR_REGION_OFFSETS) {
            PointData topLeft = new PointData(
                    TREK_COUNTER_TOP_LEFT.getX() + offset[0],
                    TREK_COUNTER_TOP_LEFT.getY() + offset[1]);
            PointData bottomRight = new PointData(
                    TREK_COUNTER_BOTTOM_RIGHT.getX() + offset[0],
                    TREK_COUNTER_BOTTOM_RIGHT.getY() + offset[1]);

            try {
                String raw = emuManager.readText(EMULATOR_NUMBER, topLeft, bottomRight);
                String normalized = normalizeOcrText(raw);
                Integer parsed = parseRemaining(raw, normalized, fractionPattern, twoNumbersPattern);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                logDebug("OCR exception at offset (" + offset[0] + "," + offset[1] + "): " + e.getMessage());
            }
        }
        return null;
    }

    private String normalizeOcrText(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replace('O', '0')
                .replace('o', '0')
                .replace('I', '1')
                .replace('l', '1')
                .replaceAll("\\s+", "")
                .trim();
    }

    private Integer parseRemaining(
            String raw,
            String normalized,
            Pattern fractionPattern,
            Pattern twoNumbersPattern) {
        Integer result = extractBestFraction(raw, fractionPattern);
        if (result != null) {
            return result;
        }

        if (raw != null) {
            String altRaw = raw
                    .replace(':', '/')
                    .replace(';', '/')
                    .replace('-', '/')
                    .replace('|', '/')
                    .replace('\\', '/');
            result = extractBestFraction(altRaw, fractionPattern);
            if (result != null) {
                return result;
            }
        }

        result = extractBestFraction(normalized, fractionPattern);
        if (result != null) {
            return result;
        }

        result = tryTwoNumbersPattern(raw, twoNumbersPattern);
        if (result != null) {
            return result;
        }

        result = tryTwoNumbersPattern(normalized, twoNumbersPattern);
        if (result != null) {
            return result;
        }

        if (normalized != null
                && (normalized.matches("^0+/?1?0?0+$") || normalized.matches("^0+100$"))) {
            return 0;
        }

        return null;
    }

    private Integer extractBestFraction(String text, Pattern fractionPattern) {
        if (text == null) {
            return null;
        }

        Matcher matcher = fractionPattern.matcher(text);
        Integer best = null;

        while (matcher.find()) {
            try {
                int numerator = Integer.parseInt(matcher.group(1));
                int denominator = Integer.parseInt(matcher.group(2));
                if (denominator < 50 || denominator > 150) {
                    continue;
                }
                if (best == null || numerator < best) {
                    best = numerator;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return best;
    }

    private Integer tryTwoNumbersPattern(String text, Pattern pattern) {
        if (text == null) {
            return null;
        }

        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                int numerator = Integer.parseInt(matcher.group(1));
                int denominator = Integer.parseInt(matcher.group(2));
                if (denominator >= 50 && denominator <= 150) {
                    return numerator;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private void rescheduleWithDelay(Duration delay, String reason) {
        LocalDateTime nextExecution = LocalDateTime.now().plus(delay);
        logWarning(reason + ". Rescheduling for " + nextExecution);
        reschedule(nextExecution);
    }

    private void rescheduleAfterConfiguredInterval(String outcome) {
        int intervalDays = resolveAutomationIntervalDays();
        LocalDateTime nextExecution = GameTimeUtils.dailyResetTimeAfterDays(intervalDays);
        logInfo("Trek " + outcome + ". Next run in " + intervalDays
                + " day(s) at next UTC reset boundary: " + nextExecution);
        reschedule(nextExecution);
    }

    private int resolveAutomationIntervalDays() {
        Integer configured = profile.getConfig(
                ConfigurationKeyEnum.TUNDRA_TREK_AUTOMATION_INTERVAL_DAYS_INT,
                Integer.class);
        int days = configured == null ? 1 : configured;
        return Math.max(1, Math.min(14, days));
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.ANY;
    }
}
