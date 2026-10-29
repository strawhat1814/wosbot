package dev.frostguard.tasks.exploration;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.vision.convert.GameTimeUtils;

import java.awt.Color;
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
    private static final Duration HUD_CLAIM_DIALOG_TIMEOUT = Duration.ofSeconds(12);
    /** After Idle Trek starts, poll City/World for the post-complete claim icon. */
    private static final Duration HUD_CLAIM_CHECK_DELAY = Duration.ofMinutes(15);

    private enum HudPassOutcome {
        /** Claimed End Idle Trek from City/World HUD; long-interval reschedule already set. */
        CLAIMED,
        /** Claim attempted but dialog missing; short retry already scheduled. */
        WAITING,
        /** Claim icon not on City/World — fall through to Dawn Academy Idle start. */
        ABSENT
    }

    private static final AreaData IDLE_BUTTON_SEARCH_AREA = AreaData.of(554, 1125, 706, 1236);
    private static final AreaData IDLE_CONFIRM_SEARCH_AREA = AreaData.of(180, 820, 540, 980);
    private static final AreaData END_IDLE_SEARCH_AREA = AreaData.of(80, 650, 640, 1000);
    /** City/World HUD strip — full width; icon side varies by layout/overlays. */
    private static final AreaData HUD_ICON_SEARCH_AREA = AreaData.of(0, 120, 720, 1100);

    private static final int HUD_FRACTION_PAD_X = 28;
    private static final int HUD_FRACTION_HEIGHT = 40;

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
    private static final SearchConfig HUD_ICON_SEARCH = SearchConfig.builder()
            .withMaxAttempts(3)
            .withDelay(200L)
            .withThreshold(70)
            .withArea(HUD_ICON_SEARCH_AREA)
            .build();
    private static final SearchConfig HUD_CLAIM_SEARCH = SearchConfig.builder()
            .withMaxAttempts(3)
            .withDelay(300L)
            .withThreshold(80)
            .withArea(END_IDLE_SEARCH_AREA)
            .build();

    private static final OcrSettingsData HUD_FRACTION_OCR = OcrSettingsData.assembler()
            .setTextColor(new Color(255, 255, 255))
            .stripBackground(true)
            .charWhitelist("0123456789/")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
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
            // Claim only via City/World HUD icon (appears after Idle Trek completes).
            // Never claim End Idle inside Dawn Academy.
            HudPassOutcome hudOutcome = evaluateHudClaimPass();
            if (hudOutcome == HudPassOutcome.CLAIMED || hudOutcome == HudPassOutcome.WAITING) {
                return;
            }

            // Icon absent: start Idle Trek the normal way (Daily → Dawn Academy).
            // Do not sit on the map forever — the claim icon is not shown while Idle runs.
            if (isAwaitingHudClaim()) {
                logInfo("HUD claim icon not visible — checking Dawn Academy for Idle start");
            }

            if (!navigateToTundraMenu()) {
                rescheduleWithDelay(Duration.ofHours(1), "Failed to navigate to Tundra menu");
                return;
            }

            if (isEndIdleClaimVisible()) {
                logInfo("End Idle Trek waiting in-event — marking HUD claim pending and leaving");
                pressBack();
                sleepTask(500);
                setAwaitingHudClaim(true);
                rescheduleForHudClaimCheck("end-idle waiting for HUD icon claim");
                return;
            }

            if (checkIfAlreadyComplete()) {
                return;
            }

            if (!startIdleTrek()) {
                // Idle unavailable usually means a run is already active — keep polling HUD.
                if (isAwaitingHudClaim()) {
                    rescheduleForHudClaimCheck("Idle start unavailable; keep waiting for HUD claim icon");
                    return;
                }
                rescheduleWithDelay(Duration.ofHours(1), "Failed to start Idle Trek");
                return;
            }

            logInfo("Idle Trek started - returning to gameplay. Will check HUD claim soon.");
            sleepTask(1500);
            setAwaitingHudClaim(true);
            rescheduleForHudClaimCheck("idle started");
        } catch (Exception e) {
            logError("Unexpected error during TundraTrekAuto task: " + e.getMessage(), e);
            rescheduleWithDelay(Duration.ofHours(1), "Unexpected error");
        }
    }

    /**
     * City/World HUD path: the map icon appears only when Idle Trek is complete and
     * ready to claim. Tap icon → End Idle. Does not open Daily / Dawn Academy.
     */
    private HudPassOutcome evaluateHudClaimPass() {
        ImageSearchResultData icon = templateSearchHelper.locatePattern(
                TemplatesEnum.TUNDRA_TREK_HUD_ICON, HUD_ICON_SEARCH);
        if (!icon.isFound()) {
            logInfo(String.format(
                    "Trek HUD claim icon not visible on City/World (best score %.1f)",
                    icon.getMatchScore()));
            return HudPassOutcome.ABSENT;
        }

        TrekHudProgress progress = readHudProgress(icon);
        if (progress != null) {
            logInfo(String.format(
                    "Trek HUD claim icon at %s with %d/%d",
                    icon.getPoint(), progress.current(), progress.max()));
            if (!progress.isComplete()) {
                // Icon is claim-only; incomplete OCR is treated as a read miss, not in-progress.
                logInfo("HUD fraction not complete — treating as OCR miss and not claiming yet");
                return HudPassOutcome.ABSENT;
            }
        } else {
            logInfo(String.format(
                    "Trek HUD claim icon at %s — tapping for End Idle (fraction OCR unavailable)",
                    icon.getPoint()));
        }

        logInfo("Trek HUD claim ready — tapping City/World map icon");
        return tapHudIconAndClaim(icon);
    }

    private HudPassOutcome tapHudIconAndClaim(ImageSearchResultData icon) {
        tapInside(icon);
        sleepTask(1500);

        if (!claimHudEndIdleIfPresent()) {
            logWarning("Tapped HUD map icon but End Idle Trek claim button not found — retrying soon");
            pressBack();
            sleepTask(500);
            setAwaitingHudClaim(true);
            rescheduleForHudClaimCheck("HUD claim button missing after map icon tap");
            return HudPassOutcome.WAITING;
        }

        tapNear(UPPER_SCREEN_CLICK);
        sleepTask(1000);
        pressBack();
        sleepTask(500);
        setAwaitingHudClaim(false);
        rescheduleAfterConfiguredInterval("HUD trek claimed");
        return HudPassOutcome.CLAIMED;
    }

    private boolean isAwaitingHudClaim() {
        Boolean awaiting = profile.getConfig(
                ConfigurationKeyEnum.TUNDRA_TREK_AWAITING_HUD_CLAIM_BOOL, Boolean.class);
        return Boolean.TRUE.equals(awaiting);
    }

    private void setAwaitingHudClaim(boolean awaiting) {
        profile.setConfig(ConfigurationKeyEnum.TUNDRA_TREK_AWAITING_HUD_CLAIM_BOOL, awaiting);
        shouldUpdateConfig = true;
    }

    private TrekHudProgress readHudProgress(ImageSearchResultData icon) {
        AreaData fractionArea = fractionAreaBelowIcon(icon);
        if (fractionArea == null) {
            return null;
        }

        for (int[] offset : OCR_REGION_OFFSETS) {
            PointData topLeft = new PointData(
                    fractionArea.topLeft().getX() + offset[0],
                    fractionArea.topLeft().getY() + offset[1]);
            PointData bottomRight = new PointData(
                    fractionArea.bottomRight().getX() + offset[0],
                    fractionArea.bottomRight().getY() + offset[1]);
            try {
                String raw = emuManager.readText(
                        EMULATOR_NUMBER, topLeft, bottomRight, HUD_FRACTION_OCR);
                TrekHudProgress parsed = TrekHudProgressParser.parse(raw);
                if (parsed != null) {
                    return parsed;
                }
                // Fallback without colour isolation when the HUD label is not pure white.
                raw = emuManager.readText(EMULATOR_NUMBER, topLeft, bottomRight);
                parsed = TrekHudProgressParser.parse(raw);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                logDebug("HUD fraction OCR failed at offset ("
                        + offset[0] + "," + offset[1] + "): " + e.getMessage());
            }
        }
        return null;
    }

    static AreaData fractionAreaBelowIcon(ImageSearchResultData icon) {
        AreaData matched = icon != null ? icon.getMatchedArea() : null;
        if (matched == null) {
            PointData center = icon != null ? icon.getPoint() : null;
            if (center == null) {
                return null;
            }
            int halfW = 30;
            int halfH = 24;
            matched = AreaData.of(
                    center.getX() - halfW,
                    center.getY() - halfH,
                    center.getX() + halfW,
                    center.getY() + halfH);
        }

        int left = Math.max(0, matched.topLeft().getX() - HUD_FRACTION_PAD_X);
        int right = Math.min(719, matched.bottomRight().getX() + HUD_FRACTION_PAD_X);
        int top = matched.bottomRight().getY() + 1;
        int bottom = Math.min(1279, top + HUD_FRACTION_HEIGHT);
        if (bottom <= top || right <= left) {
            return null;
        }
        return AreaData.of(left, top, right, bottom);
    }

    private boolean claimHudEndIdleIfPresent() {
        LocalDateTime deadline = LocalDateTime.now().plus(HUD_CLAIM_DIALOG_TIMEOUT);
        while (LocalDateTime.now().isBefore(deadline)) {
            ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                    TemplatesEnum.TUNDRA_TREK_HUD_CLAIM, HUD_CLAIM_SEARCH);
            if (!claimBtn.isFound()) {
                claimBtn = templateSearchHelper.locatePattern(
                        TemplatesEnum.TUNDRA_TREK_END_IDLE, END_IDLE_SEARCH);
            }
            if (claimBtn.isFound()) {
                logInfo(String.format(
                        "End Idle Trek claim at %s (score %.1f)",
                        claimBtn.getPoint(),
                        claimBtn.getMatchScore()));
                tapInside(claimBtn);
                sleepTask(2000);
                return true;
            }
            sleepTask(700);
        }
        return false;
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

        logInfo("Trek already complete (0/100). Leaving event for City/World HUD claim.");
        pressBack();
        sleepTask(500);
        setAwaitingHudClaim(true);
        rescheduleForHudClaimCheck("in-event counter already complete");
        return true;
    }

    private boolean isEndIdleClaimVisible() {
        ImageSearchResultData claimBtn = templateSearchHelper.locatePattern(
                TemplatesEnum.TUNDRA_TREK_END_IDLE,
                END_IDLE_SEARCH);
        if (!claimBtn.isFound()) {
            logDebug(String.format("End Idle Trek not visible (best score %.1f)", claimBtn.getMatchScore()));
            return false;
        }
        logInfo(String.format(
                "End Idle Trek visible at %s (score %.1f) — not claiming here",
                claimBtn.getPoint(),
                claimBtn.getMatchScore()));
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

    private void rescheduleForHudClaimCheck(String outcome) {
        setAwaitingHudClaim(true);
        LocalDateTime nextExecution = LocalDateTime.now().plus(HUD_CLAIM_CHECK_DELAY);
        logInfo("Trek " + outcome + ". Next HUD claim check at " + nextExecution
                + " (in " + HUD_CLAIM_CHECK_DELAY.toMinutes() + " min)");
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
