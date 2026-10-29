package dev.frostguard.tasks.economy;

import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.SidebarNavigator;
import dev.frostguard.engine.nav.CommonOCRSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarDestination;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StaminaService;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

/**
 * Claims Storehouse stamina only through the Daily sidebar A Warm Welcome row.
 */
public class WarmWelcomeRoutine extends DelayedTask {

    private static final int TIMER_OCR_MAX_ATTEMPTS = 3;
    private static final int MAX_TIMER_SECONDS = 7200;
    private static final int FALLBACK_RESCHEDULE_MINUTES = 5;
    private static final int BASE_STOREHOUSE_STAMINA = 120;
    private static final int CLAIM_BUTTON_WAIT_MILLIS = 5000;
    private static final int CLAIM_ANIMATION_MILLIS = 4000;

    private static final PointData STAMINA_AMOUNT_TOP_LEFT = new PointData(436, 632);
    private static final PointData STAMINA_AMOUNT_BOTTOM_RIGHT = new PointData(487, 657);
    private static final Pattern STAMINA_AMOUNT_PATTERN = Pattern.compile(".*?(\\d+).*");

    private static final OcrSettingsData STAMINA_OCR_SETTINGS = OcrSettingsData.assembler()
            .setTextColor(new Color(248, 247, 234))
            .stripBackground(true)
            .charWhitelist("0123456789")
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .build();

    private String storedStaminaTime;
    private ResilientOcrExecutor<LocalDateTime> textHelper;
    private LocalDateTime nextStaminaTime;

    public WarmWelcomeRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected boolean acceptsInjections() {
        return false;
    }

    private void loadConfiguration() {
        storedStaminaTime = profile.getConfig(
                ConfigurationKeyEnum.STOREHOUSE_STAMINA_CLAIM_TIME_STRING, String.class);
        textHelper = new ResilientOcrExecutor<>(provider);
        logDebug(String.format("Configuration loaded - Stored stamina time: %s", storedStaminaTime));
    }

    private void resetExecutionState() {
        nextStaminaTime = null;
        logDebug("Execution state reset");
    }

    @Override
    protected void execute() {
        loadConfiguration();
        resetExecutionState();
        boolean staminaDue = isTimeToClaimStamina();
        if (!staminaDue) {
            scheduleNext();
            return;
        }

        boolean warmWelcomeActivated = activateWarmWelcomeClaim();
        navigationHelper.closeSidebar();
        if (!warmWelcomeActivated) {
            scheduleNext();
            return;
        }

        sleepTask(2000L);
        if (!finishStaminaClaim("after Daily shortcut")) {
            logWarning("Warm Welcome shortcut activated but stamina Claim UI missing.");
            nextStaminaTime = LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
            persistNextStaminaTime();
        }
        scheduleNext();
    }

    private boolean activateWarmWelcomeClaim() {
        logInfo("Claiming Storehouse stamina through Daily A Warm Welcome on World.");
        if (navigationHelper.navigateToSidebarDestination(SidebarDestination.WARM_WELCOME)) {
            sleepTask(2000L);
            logInfo("A Warm Welcome Daily Claim activated (shortcut); looking for reward Claim next.");
            return true;
        }
        logWarning("A Warm Welcome Claim was unavailable (row missing, Claim control not matched, or on cooldown).");
        nextStaminaTime = readRowCooldownOrFallback(SidebarDestination.WARM_WELCOME);
        persistNextStaminaTime();
        return false;
    }

    private boolean finishStaminaClaim(String context) {
        boolean claimVisible = waitForStaminaClaimButton(CLAIM_BUTTON_WAIT_MILLIS);
        if (!claimVisible) {
            logWarning("Storehouse stamina Claim button not found " + context + ".");
            return false;
        }

        Integer agnesStamina = integerHelper.attemptRecognition(
                STAMINA_AMOUNT_TOP_LEFT,
                STAMINA_AMOUNT_BOTTOM_RIGHT,
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                STAMINA_OCR_SETTINGS,
                text -> RegexNumberParser.conformsTo(text, STAMINA_AMOUNT_PATTERN),
                text -> RegexNumberParser.extractByPattern(text, STAMINA_AMOUNT_PATTERN));
        logDebug("Agnes stamina OCR result: " + (agnesStamina != null ? agnesStamina : "null"));

        ImageSearchResultData claimButton = templateSearchHelper.locatePattern(
                TemplatesEnum.STOREHOUSE_STAMINA_CLAIM_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        if (!claimButton.isFound()) {
            claimButton = templateSearchHelper.locatePattern(
                    TemplatesEnum.DAILY_MISSION_CLAIM_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
        }
        if (!claimButton.isFound()) {
            logWarning("Storehouse stamina Claim button not found " + context + ".");
            return false;
        }

        logInfo("Tapping Storehouse stamina Claim button " + context + ": " + claimButton);
        tapInside(claimButton);
        sleepTask(CLAIM_ANIMATION_MILLIS);
        StaminaService.getServices().addExternalStamina(profile.getId(), BASE_STOREHOUSE_STAMINA);
        if (agnesStamina != null && agnesStamina > 0) {
            StaminaService.getServices().addExternalStamina(profile.getId(), agnesStamina);
            logInfo(String.format(
                    "Claimed %d base stamina + %d from Agnes bonus %s.",
                    BASE_STOREHOUSE_STAMINA, agnesStamina, context));
        } else {
            logInfo("Claimed " + BASE_STOREHOUSE_STAMINA + " base stamina " + context + ".");
        }

        StatisticsService.obtain().addToCounter(profile, "Warm Welcome Claims", 1);
        nextStaminaTime = GameTimeUtils.nextCycleReset();
        persistNextStaminaTime();
        return true;
    }

    private boolean waitForStaminaClaimButton(int timeoutMs) {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < timeoutMs) {
            ImageSearchResultData storehouseClaim = templateSearchHelper.locatePattern(
                    TemplatesEnum.STOREHOUSE_STAMINA_CLAIM_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
            if (storehouseClaim.isFound()) {
                return true;
            }
            ImageSearchResultData dailyClaim = templateSearchHelper.locatePattern(
                    TemplatesEnum.DAILY_MISSION_CLAIM_BUTTON, SearchConfigConstants.DEFAULT_SINGLE);
            if (dailyClaim.isFound()) {
                return true;
            }
            sleepTask(400L);
        }
        return false;
    }

    private LocalDateTime readRowCooldownOrFallback(SidebarDestination destination) {
        ImageSearchResultData row = navigationHelper.findSidebarDestinationRow(destination);
        if (!row.isFound() || row.getPoint() == null) {
            // Row gone (often Hide after mission completion) after a successful claim cycle.
            logWarning("Warm Welcome row not found; waiting until next cycle reset.");
            return GameTimeUtils.nextCycleReset();
        }

        AreaData timerArea = SidebarNavigator.rowActionAreaFor(row);
        LocalDateTime cooldown = textHelper.attemptRecognition(
                timerArea.topLeft(),
                timerArea.bottomRight(),
                TIMER_OCR_MAX_ATTEMPTS,
                200L,
                CommonOCRSettings.TRAVEL_TIME_SETTINGS,
                GameTimeUtils::isAcceptedFormat,
                text -> LocalDateTime.now().plus(GameTimeUtils.parseDuration(text)));
        if (cooldown == null) {
            // Claim control was absent and the slot timer is unreadable: do not 5-minute
            // hammer. Warm Welcome is cycle-bound once the checkmark is gone.
            logWarning("Row timer OCR empty for " + destination
                    + "; assuming claimed until next cycle reset.");
            return GameTimeUtils.nextCycleReset();
        }
        long secondsDiff = Duration.between(LocalDateTime.now(), cooldown).getSeconds();
        if (secondsDiff < 60) {
            logWarning(String.format(
                    "Ignoring implausible Warm Welcome countdown (%d s); waiting until next cycle reset.",
                    secondsDiff));
            return GameTimeUtils.nextCycleReset();
        }
        if (secondsDiff > MAX_TIMER_SECONDS) {
            logWarning(String.format(
                    "Row timer exceeds 2 hours (%d min) for %s; using next cycle reset.",
                    secondsDiff / 60, destination));
            return GameTimeUtils.nextCycleReset();
        }
        logInfo("Row cooldown for " + destination + ": " + GameTimeUtils.formatCountdown(cooldown));
        return cooldown;
    }

    private boolean isTimeToClaimStamina() {
        if (storedStaminaTime != null && !storedStaminaTime.isEmpty()) {
            try {
                LocalDateTime nextClaimTime = LocalDateTime.parse(storedStaminaTime);
                boolean timeToClaimAgain = LocalDateTime.now().isAfter(nextClaimTime);
                if (!timeToClaimAgain) {
                    logDebug("Stamina already claimed. Next claim at: "
                            + nextClaimTime.format(DATETIME_FORMATTER));
                }
                nextStaminaTime = nextClaimTime;
                return timeToClaimAgain;
            } catch (Exception ex) {
                logWarning("Failed to parse stored stamina claim time: " + ex.getMessage());
            }
        }
        return true;
    }

    private void persistNextStaminaTime() {
        if (nextStaminaTime != null) {
            writeProfileSetting(
                    ConfigurationKeyEnum.STOREHOUSE_STAMINA_CLAIM_TIME_STRING,
                    nextStaminaTime.toString());
        }
    }

    private void scheduleNext() {
        LocalDateTime now = LocalDateTime.now();
        if (nextStaminaTime != null && nextStaminaTime.isBefore(now)) {
            nextStaminaTime = null;
        }

        LocalDateTime scheduledTime = nextStaminaTime != null
                ? nextStaminaTime
                : LocalDateTime.now().plusMinutes(FALLBACK_RESCHEDULE_MINUTES);
        String reason = nextStaminaTime != null ? "stamina claim" : "No valid times (fallback)";
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
