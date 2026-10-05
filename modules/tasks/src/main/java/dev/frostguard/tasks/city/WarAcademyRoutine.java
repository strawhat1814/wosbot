package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.vision.convert.GameTimeUtils;
import dev.frostguard.vision.convert.RegexNumberParser;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

public class WarAcademyRoutine extends DelayedTask {

    private static final PointData LEFT_MENU_SWIPE_START_VALUE = new PointData(255, 477);
    private static final PointData LEFT_MENU_SWIPE_END_VALUE = new PointData(255, 425);
    private static final PointData BUILDING_TAP_CENTER_VALUE = new PointData(360, 790);
    private static final PointData CONFIRM_QTY_OCR_TOP_LEFT = new PointData(449, 670);
    private static final PointData CONFIRM_QTY_OCR_BOTTOM_RIGHT = new PointData(579, 734);

    private static final int MIN_RESEARCH_CENTERS_REQUIRED_FLOOR = 2;
    private static final int BUILDING_TAP_COUNT_VALUE = 5;
    private static final int BUILDING_TAP_DELAY_MS = 100;
    private static final int RETRY_DELAY_MINUTES_MS = 5;
    private static final int ADDITIONAL_SHARDS_DELAY_HOURS_MS = 2;
    private static final int MAX_SHARD_REMAINING = 20;
    private static final int REDEEM_BUTTON_FALLBACK_WIDTH = 119;
    private static final int REDEEM_BUTTON_FALLBACK_HEIGHT = 41;
    private static final int REMAINING_OCR_ABOVE_PX = 52;
    private static final int REMAINING_OCR_LEFT_PAD_PX = 90;

    private static final Pattern DIGITS_PATTERN = Pattern.compile(".*?(\\d+).*");
    private static final OcrSettingsData DIGITS_OCR_SETTINGS = OcrSettingsData.assembler()
            .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
            .charWhitelist("0123456789")
            .build();
    private static final SearchConfig REDEEM_BUTTON_SEARCH = SearchConfig.builder()
            .withMaxAttempts(3)
            .withThreshold(90)
            .withDelay(200L)
            .withMaxResults(4)
            .build();

    public WarAcademyRoutine(AccountDescriptor profile, TpDailyTaskEnum tpDailyTask) {
        super(profile, tpDailyTask);
    }

    @Override
    protected void execute() {
        if (!reachWarAcademy()) {
            logWarning(routineLogWarAcademyLine("Could not navigate to War Academy."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        if (!openUpRedeemSection()) {
            logWarning(routineLogWarAcademyLine("Could not open Redeem section."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        ShardRedeemCandidate shardRow = locateShardRedeemRow();
        if (shardRow == null) {
            logInfo(routineLogWarAcademyLine("No shard Redeem row with Remaining 1-20."));
            reschedule(GameTimeUtils.dailyResetTime());
            return;
        }

        logInfo(routineLogWarAcademyLine(
                String.format("Selected shard Redeem row with Remaining %d.", shardRow.remaining())));

        if (!redeemShardRow(shardRow)) {
            logWarning(routineLogWarAcademyLine("Could not redeem shards safely."));
            reschedule(LocalDateTime.now().plusMinutes(RETRY_DELAY_MINUTES_MS));
            return;
        }

        managePostRedemptionCheck();
    }

    @Override
    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    @Override
    public boolean provideDailyMissionProgress() {
        return false;
    }

    private void managePostRedemptionCheck() {
        logInfo(routineLogWarAcademyLine("Inspecting for additional shards after redemption."));
        ShardRedeemCandidate leftover = locateShardRedeemRow();
        if (leftover == null) {
            logInfo(routineLogWarAcademyLine("No remaining shard Redeem row. Planning next run for game reset."));
            reschedule(GameTimeUtils.dailyResetTime());
        } else {
            logInfo(routineLogWarAcademyLine(String.format(
                    "Additional shards detected: %d. Planning next run in %d hours.",
                    leftover.remaining(), ADDITIONAL_SHARDS_DELAY_HOURS_MS)));
            reschedule(LocalDateTime.now().plusHours(ADDITIONAL_SHARDS_DELAY_HOURS_MS));
        }
    }

    private String routineLogWarAcademyLine(String note) {
        return "WarAcademyRoutine | " + note;
    }

    private ShardRedeemCandidate locateShardRedeemRow() {
        logInfo(routineLogWarAcademyLine("Scanning Redeem rows by Remaining count."));
        List<ImageSearchResultData> redeemButtons = templateSearchHelper.locateAllPatterns(
                TemplatesEnum.WAR_ACADEMY_SHARD_REDEEM_BUTTON, REDEEM_BUTTON_SEARCH);
        if (redeemButtons.isEmpty()) {
            logWarning(routineLogWarAcademyLine("No Redeem buttons detected on list."));
            return null;
        }

        List<ShardRedeemCandidate> shardRows = new ArrayList<>();
        for (ImageSearchResultData button : redeemButtons) {
            Integer remaining = readRemainingAboveRedeem(button);
            if (remaining == null) {
                logDebug(routineLogWarAcademyLine(String.format(
                        "Remaining OCR missed above Redeem @ (%d,%d).", button.getHitX(), button.getHitY())));
            } else {
                logInfo(routineLogWarAcademyLine(String.format(
                        "Redeem @ (%d,%d) Remaining=%d.", button.getHitX(), button.getHitY(), remaining)));
                if (remaining > MAX_SHARD_REMAINING) {
                    logInfo(routineLogWarAcademyLine(String.format(
                            "Skipping crystal row Remaining=%d (>%d).", remaining, MAX_SHARD_REMAINING)));
                } else if (remaining > 0) {
                    shardRows.add(new ShardRedeemCandidate(button, remaining));
                }
            }
        }

        if (shardRows.isEmpty()) {
            return null;
        }
        if (shardRows.size() > 1) {
            logWarning(routineLogWarAcademyLine(String.format(
                    "Multiple shard rows matched (%d); aborting to avoid wrong redeem.", shardRows.size())));
            return null;
        }
        return shardRows.getFirst();
    }

    private Integer readRemainingAboveRedeem(ImageSearchResultData redeemButton) {
        PointData[] corners = remainingOcrCorners(redeemButton);
        return integerHelper.attemptRecognition(
                corners[0],
                corners[1],
                4,
                200L,
                DIGITS_OCR_SETTINGS,
                text -> RegexNumberParser.conformsTo(text, DIGITS_PATTERN),
                text -> RegexNumberParser.extractByPattern(text, DIGITS_PATTERN));
    }

    private PointData[] remainingOcrCorners(ImageSearchResultData redeemButton) {
        int width = redeemButton.getMatchWidth() > 0 ? redeemButton.getMatchWidth() : REDEEM_BUTTON_FALLBACK_WIDTH;
        int height = redeemButton.getMatchHeight() > 0 ? redeemButton.getMatchHeight() : REDEEM_BUTTON_FALLBACK_HEIGHT;
        AreaData matched = redeemButton.getMatchedArea();
        int left;
        int top;
        int right;
        if (matched != null) {
            left = matched.topLeft().getX();
            top = matched.topLeft().getY();
            right = matched.bottomRight().getX();
        } else {
            left = redeemButton.getHitX() - width / 2;
            top = redeemButton.getHitY() - height / 2;
            right = left + width - 1;
        }

        int ocrLeft = Math.max(0, left - REMAINING_OCR_LEFT_PAD_PX);
        int ocrTop = Math.max(0, top - REMAINING_OCR_ABOVE_PX);
        int ocrBottom = Math.max(ocrTop + 1, top - 2);
        int ocrRight = right + 10;
        return new PointData[] {
                new PointData(ocrLeft, ocrTop),
                new PointData(ocrRight, ocrBottom)
        };
    }

    private boolean redeemShardRow(ShardRedeemCandidate shardRow) {
        logInfo(routineLogWarAcademyLine("Tapping shard Redeem button."));
        tapInside(shardRow.button());
        sleepTask(700L);

        ImageSearchResultData maxButton = templateSearchHelper.locatePattern(
                TemplatesEnum.WAR_ACADEMY_REDEEM_CONFIRM_MAX, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!maxButton.isFound()) {
            logError(routineLogWarAcademyLine("Confirm Max button not detected; backing out."));
            abortConfirmPopup();
            return false;
        }

        logDebug(routineLogWarAcademyLine("Pressing Max on confirm popup."));
        tapInside(maxButton);
        sleepTask(400L);

        Integer quantity = readConfirmQuantity();
        if (quantity == null) {
            logError(routineLogWarAcademyLine("Could not OCR confirm quantity; backing out."));
            abortConfirmPopup();
            return false;
        }
        if (quantity <= 0 || quantity > MAX_SHARD_REMAINING) {
            logError(routineLogWarAcademyLine(String.format(
                    "Confirm quantity %d outside shard range 1-%d; backing out.", quantity, MAX_SHARD_REMAINING)));
            abortConfirmPopup();
            return false;
        }

        logInfo(routineLogWarAcademyLine("Confirm quantity OK: " + quantity));
        ImageSearchResultData steel = templateSearchHelper.locatePattern(
                TemplatesEnum.WAR_ACADEMY_REDEEM_CONFIRM_STEEL, SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!steel.isFound()) {
            logError(routineLogWarAcademyLine("Steel cost icon not on confirm button; backing out."));
            abortConfirmPopup();
            return false;
        }

        logDebug(routineLogWarAcademyLine("Confirming shard redeem via steel-cost button."));
        tapInside(steel);
        sleepTask(1000L);
        logInfo(routineLogWarAcademyLine("Shards redeemed finished cleanly."));
        StatisticsService.obtain().addToCounter(profile, "War Academy Shards Redeemed", 1);
        return true;
    }

    private Integer readConfirmQuantity() {
        logInfo(routineLogWarAcademyLine("Reading confirm quantity via OCR."));
        Integer quantity = integerHelper.attemptRecognition(
                CONFIRM_QTY_OCR_TOP_LEFT,
                CONFIRM_QTY_OCR_BOTTOM_RIGHT,
                5,
                200L,
                DIGITS_OCR_SETTINGS,
                text -> RegexNumberParser.conformsTo(text, DIGITS_PATTERN),
                text -> RegexNumberParser.extractByPattern(text, DIGITS_PATTERN));
        if (quantity != null) {
            logInfo(routineLogWarAcademyLine("Confirm quantity OCR: " + quantity));
        }
        return quantity;
    }

    private void abortConfirmPopup() {
        pressBack();
        sleepTask(500L);
    }

    private boolean openUpRedeemSection() {
        logInfo(routineLogWarAcademyLine("Looking for Redeem tab to confirm War Academy."));
        ImageSearchResultData redeemTab = templateSearchHelper.locatePattern(
                TemplatesEnum.VALIDATION_WAR_ACADEMY_UI, SearchConfigConstants.RESILIENT);
        if (!redeemTab.isFound()) {
            logError(routineLogWarAcademyLine("Redeem tab not detected after Research."));
            return false;
        }

        logDebug(routineLogWarAcademyLine("Entering Redeem section via detected tab"));
        tapInside(redeemTab);
        sleepTask(500L);
        return true;
    }

    private boolean reachWarAcademy() {
        logInfo(routineLogWarAcademyLine("Moving to War Academy."));
        marchHelper.openLeftMenuCitySection(true);

        logDebug(routineLogWarAcademyLine("Swiping to reveal Research Centers"));
        swipe(LEFT_MENU_SWIPE_START_VALUE, LEFT_MENU_SWIPE_END_VALUE);
        sleepTask(500L);

        List<ImageSearchResultData> researchCenters = templateSearchHelper.locateAllPatterns(
                TemplatesEnum.GAME_HOME_SHORTCUTS_RESEARCH_CENTER,
                SearchConfigConstants.MULTIPLE_RESULTS);

        if (researchCenters.size() < MIN_RESEARCH_CENTERS_REQUIRED_FLOOR) {
            logError(routineLogWarAcademyLine(String.format(
                    "Only detected %d Research Centers, need at least %d.",
                    researchCenters.size(), MIN_RESEARCH_CENTERS_REQUIRED_FLOOR)));
            return false;
        }

        logInfo(routineLogWarAcademyLine(String.format("Detected %d Research Centers.", researchCenters.size())));

        ImageSearchResultData warAcademyCenter = researchCenters.stream()
                .max(Comparator.comparingInt(r -> r.getPoint().getY()))
                .orElseThrow(() -> new RuntimeException("No valid Research Center found"));

        logDebug(routineLogWarAcademyLine("Pressing War Academy (bottommost Research Center)"));
        tapInside(warAcademyCenter);
        sleepTask(1000L);

        logDebug(routineLogWarAcademyLine("Pressing building center to enter"));
        tapInside(BUILDING_TAP_CENTER_VALUE, BUILDING_TAP_CENTER_VALUE, BUILDING_TAP_COUNT_VALUE, BUILDING_TAP_DELAY_MS);
        sleepTask(1000L);

        ImageSearchResultData researchButton = templateSearchHelper.locatePattern(
                TemplatesEnum.BUILDING_BUTTON_RESEARCH,
                SearchConfigConstants.SINGLE_WITH_RETRIES);
        if (!researchButton.isFound()) {
            logError(routineLogWarAcademyLine("Research button not detected."));
            return false;
        }

        logDebug(routineLogWarAcademyLine("Entering Research section"));
        tapInside(researchButton);
        sleepTask(800L);
        logInfo(routineLogWarAcademyLine("Research opened; Redeem tab will confirm War Academy."));
        return true;
    }

    private record ShardRedeemCandidate(ImageSearchResultData button, int remaining) {
    }

    static boolean redemptionMadeProgress(int initialShards, int finalShards) {
        return initialShards > 0 && finalShards < initialShards;
    }
}
