/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  dev.frostguard.api.configs.TemplatesEnum
 *  dev.frostguard.api.configs.TpDailyTaskEnum
 *  dev.frostguard.api.domain.AccountDescriptor
 *  dev.frostguard.api.domain.AreaData
 *  dev.frostguard.api.domain.ImageSearchResultData
 *  dev.frostguard.api.domain.OcrSettingsData
 *  dev.frostguard.api.domain.PointData
 *  dev.frostguard.engine.helper.TemplateSearchHelper
 *  dev.frostguard.engine.helper.TemplateSearchHelper$SearchConfig
 *  dev.frostguard.engine.nav.LeftMenuTextSettings
 *  dev.frostguard.engine.nav.SearchConfigConstants
 *  dev.frostguard.engine.nav.SidebarSection
 *  dev.frostguard.engine.schedule.DelayedTask
 *  dev.frostguard.engine.schedule.LaunchPoint
 *  dev.frostguard.tasks.city.BuildingUpgradeConfirmationFlow
 *  dev.frostguard.tasks.city.BuildingUpgradeConfirmationFlow$Outcome
 *  dev.frostguard.tasks.city.BuildingUpgradeConfirmationFlow$Ui
 *  dev.frostguard.tasks.city.GrowthMissionBuildRoutine$FurnitureResource
 *  dev.frostguard.tasks.city.GrowthMissionBuildRoutine$OpenGrowthResult
 *  dev.frostguard.tasks.city.GrowthMissionBuildRoutine$UpgradeOutcome
 *  dev.frostguard.tasks.city.RepeatedResourceReplenishmentFlow
 *  dev.frostguard.tasks.city.RepeatedResourceReplenishmentFlow$Result
 *  dev.frostguard.tasks.city.RepeatedResourceReplenishmentFlow$Ui
 *  dev.frostguard.vision.convert.GameTimeUtils
 */
package dev.frostguard.tasks.city;

import dev.frostguard.api.configs.ConfigurationKeyEnum;
import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.configs.TpDailyTaskEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.engine.helper.FurnacePanelDetector;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.nav.LeftMenuTextSettings;
import dev.frostguard.engine.nav.SearchConfigConstants;
import dev.frostguard.engine.nav.SidebarSection;
import dev.frostguard.engine.schedule.DelayedTask;
import dev.frostguard.engine.schedule.LaunchPoint;
import dev.frostguard.engine.service.StatisticsService;
import dev.frostguard.engine.service.TelegramBotService;
import dev.frostguard.vision.convert.GameTimeUtils;
import java.awt.Color;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class GrowthMissionBuildRoutine
extends DelayedTask {
    private static final int MAX_FURNITURE_STEPS = 16;
    private static final int MAX_RESOURCE_REPLENISHMENTS = 4;
    private static final int MAX_CLAIM_TAPS = 12;
    private static final int COMPLETION_SETTLE_SECONDS = 2;
    private static final int DEFAULT_RETRY_MINUTES = 20;
    private static final int FURNITURE_PROGRESS_RETRY_MINUTES = 3;
    /** After Claim/Go miss + second Growth OCR still miss — back off one hour. */
    private static final int NO_MAIN_ACTION_RETRY_HOURS = 1;
    private static final int STEEL_WAIT_MINUTES = 15;
    /** Fast furniture: every hold is a fixed 2s ADB swipe, then inspect. */
    private static final int FAST_FURNITURE_HOLD_MS = 2000;
    /** Cap hold bursts per run (Next taps do not count). Live Carl/Stuart needed 2. */
    private static final int FAST_FURNITURE_MAX_HOLDS = 8;
    /** Piece Upgrade sits below the building header Upgrade band (header area ends ~y 610). */
    private static final int FURNITURE_UPGRADE_MIN_Y = 620;
    private static final double RESOURCE_STEEL_GUARD_THRESHOLD = 65.0;
    private static final AreaData MISSIONS_BUTTON_AREA = new AreaData(new PointData(15, 1025), new PointData(95, 1085));
    private static final PointData REPLENISH_CONFIRM_POINT = new PointData(511, 1056);
    private static final AreaData GROWTH_TITLE_AREA = new AreaData(new PointData(180, 40), new PointData(540, 110));
    /**
     * Missions tab label band only (Stuart Copy Debug OCR 2026-10-10):
     * TopLeft(73, 1116), BottomRight(650, 1147). Holds 1–3 tabs: Daily / Growth / Chapter.
     * Do not split into fixed thirds — Growth X moves with tab count.
     */
    private static final AreaData MISSIONS_TAB_BAR_AREA = new AreaData(new PointData(73, 1116), new PointData(650, 1147));
    /** Legacy template search band — kept inside the tab strip. */
    private static final AreaData GROWTH_TAB_AREA = MISSIONS_TAB_BAR_AREA;
    private static final AreaData DAILY_TAB_SELECTED_AREA = new AreaData(new PointData(360, 1145), new PointData(720, 1205));
    /** Inset from OCR window edges so random taps stay on Growth, not the neighbor tab. */
    private static final int TAB_OCR_TAP_INSET_X = 28;
    /** Clamp taps to the traced label band (avoids Heroes nav below the pills). */
    private static final int GROWTH_TAB_SAFE_Y_MIN = 1116;
    private static final int GROWTH_TAB_SAFE_Y_MAX = 1147;
    private static final int GROWTH_TAB_SAFE_X_MIN = 73;
    private static final int GROWTH_TAB_SAFE_X_MAX = 650;
    /**
     * Sliding OCR window across {@link #MISSIONS_TAB_BAR_AREA}. Wide enough for one tab
     * label on 2–3 tab layouts; stepped so 1-tab Growth still lands in a window.
     */
    private static final int TAB_OCR_WINDOW_WIDTH = 220;
    private static final int TAB_OCR_WINDOW_STEP = 70;
    /** Same Y as {@link #MISSIONS_TAB_BAR_AREA} — the traced region is already label-only. */
    private static final int TAB_OCR_LABEL_Y_MIN = 1116;
    private static final int TAB_OCR_LABEL_Y_MAX = 1147;
    /** Selected tab text is dark blue on a light pill — white OCR presets miss it. */
    private static final OcrSettingsData TAB_DARK_TEXT_SETTINGS = OcrSettingsData.builder()
            .setRemoveBackground(true)
            .setTextColor(new Color(30, 55, 120))
            .setAllowedChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .build();
    /** Debug-style read: no colour isolation (matches Debugging panel OCR). */
    private static final OcrSettingsData TAB_PLAIN_OCR_SETTINGS = OcrSettingsData.builder()
            .setRemoveBackground(false)
            .setAllowedChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ")
            .build();
    private static final AreaData DAILY_TITLE_AREA = new AreaData(new PointData(180, 40), new PointData(540, 170));
    /**
     * Main Growth mission Go/Claim — right column of the upper mission card.
     * Live Main Go ~y 341; Main Claim ~y 571. Lower Side/list Go near y~910 must not match.
     */
    private static final AreaData MAIN_GO_CLAIM_AREA = new AreaData(new PointData(480, 140), new PointData(720, 620));
    /**
     * Main Go only — keep above the Claim row. Live Kevin matched Go at y~577 (Claim band)
     * at 96% and then took the building road with no city up-arrow (queue still Idle).
     */
    private static final AreaData MAIN_GO_AREA = new AreaData(new PointData(480, 140), new PointData(720, 480));
    private static final int MAIN_GO_MAX_Y = 480;
    /** Live Main Go/Claim pill center when OCR confirms the label but templates miss. */
    private static final PointData MAIN_GO_OCR_FALLBACK_POINT = new PointData(596, 341);
    private static final PointData MAIN_CLAIM_OCR_FALLBACK_POINT = new PointData(595, 341);
    private static final AreaData ALL_COMPLETE_OCR_AREA = new AreaData(new PointData(80, 400), new PointData(640, 900));
    private static final AreaData HEADER_UPGRADE_AREA = new AreaData(new PointData(540, 500), new PointData(720, 610));
    /** Piece Upgrade only — keep below header Upgrade (~y 500–610) so holds never hit main. */
    private static final AreaData FURNITURE_UPGRADE_AREA = new AreaData(new PointData(500, 620), new PointData(720, 760));
    /**
     * Blue "Next" on the furniture detail card (live ~577,650). Same band as Upgrade,
     * kept as its own area so Next search can be tuned independently.
     */
    private static final AreaData FURNITURE_NEXT_AREA = new AreaData(new PointData(520, 620), new PointData(700, 720));
    /**
     * Bottom-left "Furniture" tab/label that identifies the furniture upgrade panel.
     * Live marker ~ (136,1195) on Kevin furniture frames.
     */
    private static final AreaData FURNITURE_PANEL_AREA = new AreaData(new PointData(100, 1160), new PointData(300, 1260));
    private static final AreaData CITY_UPGRADE_HEX_TAP_AREA = new AreaData(new PointData(385, 855), new PointData(455, 930));
    /**
     * After Growth Go, tap here (with area jitter) to dismiss the tutorial guide hand /
     * highlight circles that obscure the Upgrade arrow. Building road only — furniture
     * never uses this. Allowed twice if the up-arrow is still missing after the first tap.
     * Anchored ~30px below screen center so the tap hits the selected building, not HUD.
     */
    private static final AreaData BUILDING_CENTER_DISMISS_AREA =
            new AreaData(new PointData(300, 590), new PointData(420, 750));
    private static final int BUILDING_CENTER_DISMISS_SETTLE_MS = 1200;
    private static final int MAX_GUIDE_CENTER_DISMISS = 2;
    /** Building-road center dismiss count for this execute (furniture never increments). */
    private int growthGuideDismissCount;
    /**
     * City view after Growth Go — blue up-arrow bubble on the selected building.
     * Avoids bottom nav / mission tabs.
     */
    private static final AreaData CITY_UPGRADE_ARROW_AREA =
            new AreaData(new PointData(40, 120), new PointData(680, 1050));
    /**
     * Blue Upgrade on the building requirement dialog (right; Finish/gems stay left).
     * Used after city hex tap so we do not hang in panel-Upgrade template locate.
     */
    private static final AreaData BUILDING_DIALOG_UPGRADE_TAP_AREA =
            new AreaData(new PointData(500, 1100), new PointData(680, 1230));
    private static final AreaData BUILDING_PANEL_UPGRADE_AREA = new AreaData(new PointData(380, 1000), new PointData(700, 1220));
    private static final AreaData RESOURCE_ICON_AREA = new AreaData(new PointData(500, 620), new PointData(640, 740));
    private static final AreaData BUILDING_CONFIRM_UPGRADE_AREA = new AreaData(new PointData(350, 900), new PointData(700, 1255));
    private static final AreaData QUEUE_AREA_1 = new AreaData(new PointData(95, 370), new PointData(358, 407));
    private static final AreaData QUEUE_AREA_2 = new AreaData(new PointData(95, 443), new PointData(358, 480));
    /**
     * City-map construction countdown when CITY sidebar queue OCR is unavailable
     * (early accounts). Stuart Copy Debug: TopLeft(271, 347) → BottomRight(432, 385).
     * Still needs heavy live testing — position may shift with camera / building.
     */
    private static final AreaData CITY_MAP_CONSTRUCTION_TIMER_AREA =
            new AreaData(new PointData(271, 347), new PointData(432, 385));
    private static final int CITY_MAP_TIMER_OCR_ATTEMPTS = 3;
    private static final TemplateSearchHelper.SearchConfig GROWTH_TITLE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(90).withArea(GROWTH_TITLE_AREA).build();
    private static final TemplateSearchHelper.SearchConfig DAILY_TITLE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(250L).withThreshold(85).withArea(DAILY_TITLE_AREA).build();
    private static final TemplateSearchHelper.SearchConfig GROWTH_TAB_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(85).withArea(GROWTH_TAB_AREA).build();
    private static final TemplateSearchHelper.SearchConfig DAILY_TAB_SELECTED_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(200L).withThreshold(85).withArea(DAILY_TAB_SELECTED_AREA).build();
    private static final TemplateSearchHelper.SearchConfig GO_MAIN_SEARCH;
    private static final TemplateSearchHelper.SearchConfig GO_SCORE_PROBE;
    private static final TemplateSearchHelper.SearchConfig CLAIM_QUICK_SEARCH;
    private static final TemplateSearchHelper.SearchConfig CLAIM_FALLBACK_QUICK_SEARCH;
    private static final TemplateSearchHelper.SearchConfig HEADER_UPGRADE_SEARCH;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_UPGRADE_SEARCH;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_NEXT_SEARCH;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_NEXT_SCORE_PROBE;
    private static final TemplateSearchHelper.SearchConfig FURNITURE_PANEL_SEARCH;
    private static final TemplateSearchHelper.SearchConfig CITY_UPGRADE_ARROW_SEARCH;
    private static final TemplateSearchHelper.SearchConfig CITY_UPGRADE_ARROW_SCORE_PROBE;
    private static final TemplateSearchHelper.SearchConfig BUILDING_PANEL_UPGRADE_SEARCH;
    /**
     * Obtain-more bottom sheet Replenish All. Wider than the legacy (180,1070)–(535,1195)
     * band — Stuart Copy missed Replenish after Obtain with that tight 90% window.
     */
    private static final AreaData REPLENISH_ALL_AREA =
            new AreaData(new PointData(60, 1000), new PointData(660, 1240));
    /** Center of the usual Replenish All pill when OCR confirms the label. */
    private static final PointData REPLENISH_ALL_OCR_TAP = new PointData(358, 1135);
    private static final TemplateSearchHelper.SearchConfig REPLENISH_BUTTON_RECHECK;
    private static final TemplateSearchHelper.SearchConfig REPLENISH_BUTTON_RELAXED;
    private static final TemplateSearchHelper.SearchConfig REPLENISH_BUTTON_SCORE_PROBE;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_SEARCH;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_RELAXED;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_POSTCONDITION;
    private static final TemplateSearchHelper.SearchConfig BUILDING_CONFIRM_UPGRADE_SCORE_PROBE;

    public GrowthMissionBuildRoutine(AccountDescriptor profile, TpDailyTaskEnum taskType) {
        super(profile, taskType);
    }

    protected LaunchPoint getRequiredStartLocation() {
        return LaunchPoint.HOME;
    }

    protected void execute() {
        // Task instances are reused across runs — reset per-execute dismiss budget.
        this.growthGuideDismissCount = 0;
        OpenGrowthResult openResult = this.openGrowthMissionsAndTapGo();
        if (openResult == OpenGrowthResult.ALL_COMPLETE) {
            this.logInfo(this.logLine("All Growth missions complete \u2014 rescheduling for daily reset."));
            this.notifyTelegram("All Growth missions complete");
            this.dismissToHome();
            this.reschedule(GameTimeUtils.dailyResetTime());
            return;
        }
        if (openResult == OpenGrowthResult.NO_MAIN_ACTION) {
            this.logInfo(this.logLine("No Main Claim/Go after Growth OCR retries \u2014 dismissing and aborting for "
                    + NO_MAIN_ACTION_RETRY_HOURS + "h"));
            this.dismissToHome();
            this.reschedule(LocalDateTime.now().plusHours(NO_MAIN_ACTION_RETRY_HOURS));
            return;
        }
        if (openResult != OpenGrowthResult.READY_FOR_BUILD) {
            this.dismissToHome();
            this.reschedule(LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES));
            return;
        }
        UpgradeOutcome outcome = this.performBuildingUpgrades();
        if (outcome.avoidBackOnExit()) {
            // Back/ESC after a failed city up-arrow search closes the upgrade bubble.
            this.recoverHomeWithoutBack();
        } else {
            this.dismissToHome();
        }
        if (outcome.startedConstruction()) {
            this.notifyTelegram("Growth construction started");
            StatisticsService.obtain().addToCounter(this.profile, "Growth Construction Started", 1);
            this.scheduleAfterConstruction();
        } else if (outcome.cityArrowMissed()) {
            // No up-arrow usually means the selected building is already under construction
            // (Growth cannot start the next building until that queue finishes).
            this.logInfo(this.logLine(
                    "City up-arrow miss — checking construction queue before retrying Growth"));
            if (!this.tryScheduleFromConstructionQueue("city up-arrow miss")) {
                this.reschedule(LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES));
            }
        } else if (outcome.steelBlocked()) {
            this.logInfo(this.logLine("Steel guard blocked furniture spend. Retrying in 15 minutes."));
            this.reschedule(LocalDateTime.now().plusMinutes(STEEL_WAIT_MINUTES));
        } else if (outcome.furnitureProgress()) {
            this.notifyTelegram("Growth furniture progress");
            StatisticsService.obtain().addToCounter(this.profile, "Growth Furniture Upgrades", 1);
            this.logInfo(this.logLine("Furniture pieces progressed — retrying in "
                    + FURNITURE_PROGRESS_RETRY_MINUTES + " minutes"));
            this.reschedule(LocalDateTime.now().plusMinutes(FURNITURE_PROGRESS_RETRY_MINUTES));
        } else {
            this.reschedule(LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES));
        }
    }

    private OpenGrowthResult openGrowthMissionsAndTapGo() {
        this.logInfo(this.logLine("Opening missions panel"));
        this.tapInside(MISSIONS_BUTTON_AREA);
        this.sleepTask(2500L);
        if (!this.ensureGrowthTabSelected()) {
            // Live Stuart 2026-09-19 14:01: first open after Storehouse left no missions chrome.
            this.logInfo(this.logLine("Missions panel missing after first open — retrying missions button"));
            this.tapInside(MISSIONS_BUTTON_AREA);
            this.sleepTask(2800L);
            if (!this.ensureGrowthTabSelected()) {
                this.logWarning(this.logLine("Could not reach Growth Missions tab"));
                return OpenGrowthResult.FAILED;
            }
        }

        OpenGrowthResult firstPass = this.drainClaimsAndTapGo("first pass");
        if (firstPass != OpenGrowthResult.NO_MAIN_ACTION) {
            return firstPass;
        }

        // No Claim and no Go — tap Growth again (OCR then template) and recheck.
        this.logInfo(this.logLine("No Claim/Go on Growth list — second Growth tab tap"));
        if (!this.tapGrowthTabOnce(2)) {
            this.logWarning(this.logLine("Second Growth tab miss — aborting Claim/Go search"));
            this.logGoMiss();
            return OpenGrowthResult.NO_MAIN_ACTION;
        }
        this.sleepTask(1200L);

        OpenGrowthResult secondPass = this.drainClaimsAndTapGo("after second Growth tap");
        if (secondPass != OpenGrowthResult.NO_MAIN_ACTION) {
            return secondPass;
        }
        this.logGoMiss();
        return OpenGrowthResult.NO_MAIN_ACTION;
    }

    /** Claim-first, then Go. Returns NO_MAIN_ACTION when neither Claim nor Go is available. */
    private OpenGrowthResult drainClaimsAndTapGo(String phase) {
        this.sleepTask(900L);
        this.logInfo(this.logLine("Looking for Growth mission Claim (before Go) [" + phase + "]"));
        int claimed = this.claimAllReadyGrowthMissions();
        if (claimed > 0) {
            this.logInfo(this.logLine("Claimed " + claimed + " Growth mission reward(s) [" + phase + "]"));
            StatisticsService.obtain().addToCounter(this.profile, "Growth Missions Claimed", claimed);
            this.sleepTask(800L);
        }
        this.logInfo(this.logLine("Looking for Growth mission Go [" + phase + "]"));
        if (this.tapAnyGoButton()) {
            return OpenGrowthResult.READY_FOR_BUILD;
        }
        if (this.isAllGrowthMissionsComplete()) {
            return OpenGrowthResult.ALL_COMPLETE;
        }
        return OpenGrowthResult.NO_MAIN_ACTION;
    }

    private boolean tapAnyGoButton() {
        ImageSearchResultData mainGo = this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GO_BUTTON_MAIN, GO_MAIN_SEARCH);
        if (mainGo.isFound()) {
            PointData goPoint = mainGo.getPoint();
            if (goPoint != null && goPoint.getY() > MAIN_GO_MAX_Y) {
                this.logWarning(this.logLine(String.format(Locale.ROOT,
                        "Ignoring Main Go match at %s (below Go band y<=%d — Claim-row false hit)",
                        goPoint, MAIN_GO_MAX_Y)));
                return false;
            }
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Tapping Main mission Go at %s (score %.1f)", goPoint, mainGo.getMatchScore())));
            this.tapInside(mainGo);
            this.sleepTask(2000L);
            return true;
        }
        // Template miss — OCR the upper Go band and tap the live Main Go pill if it reads "Go".
        if (this.ocrAreaLooksLike(MAIN_GO_AREA, GrowthMissionBuildRoutine::looksLikeGoLabel)) {
            this.logInfo(this.logLine("Main Go template missed — OCR read Go; tapping fallback "
                    + String.valueOf(MAIN_GO_OCR_FALLBACK_POINT)));
            this.tapNear(MAIN_GO_OCR_FALLBACK_POINT, 4);
            this.sleepTask(2000L);
            return true;
        }
        // Side / lower-list Go is intentionally unused — those rows false-match Main builds (~y 910).
        return false;
    }

    private int claimAllReadyGrowthMissions() {
        int claimed = 0;
        for (int i = 0; i < MAX_CLAIM_TAPS; ++i) {
            ImageSearchResultData claim;
            try {
                claim = this.findGrowthClaimButtonQuick();
            }
            catch (Exception e) {
                this.logWarning(this.logLine("Claim search error \u2014 stopping Claim loop: " + e.getMessage()));
                break;
            }
            if (!claim.isFound()) {
                // One OCR-assisted Claim tap when templates flake but the green pill is still there.
                if (this.ocrAreaLooksLike(MAIN_GO_CLAIM_AREA, GrowthMissionBuildRoutine::looksLikeClaimLabel)) {
                    this.logInfo(this.logLine(String.format(Locale.ROOT,
                            "Claim template missed (best=%.1f) — OCR read Claim; tapping fallback %s",
                            claim.getMatchScore(), MAIN_CLAIM_OCR_FALLBACK_POINT)));
                    this.tapNear(MAIN_CLAIM_OCR_FALLBACK_POINT, 4);
                    ++claimed;
                    this.sleepTask(900L);
                    continue;
                }
                if (claimed == 0) {
                    this.logInfo(this.logLine(String.format(Locale.ROOT, "Claim not visible (best=%.1f) \u2014 checking Go", claim.getMatchScore())));
                } else {
                    this.logInfo(this.logLine(String.format(Locale.ROOT, "No further Claim (best=%.1f) after %d claim(s)", claim.getMatchScore(), claimed)));
                }
                break;
            }
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Tapping Growth Claim at %s (score %.1f)", claim.getPoint(), claim.getMatchScore())));
            this.tapInside(claim);
            ++claimed;
            this.sleepTask(900L);
        }
        if (claimed >= MAX_CLAIM_TAPS) {
            this.logWarning(this.logLine("Claim loop hit safety cap (" + MAX_CLAIM_TAPS + ") \u2014 continuing to Go"));
        }
        return claimed;
    }

    private ImageSearchResultData findGrowthClaimButtonQuick() {
        // Do not fall back to DAILY_MISSION_CLAIM_BUTTON — that fires while still on
        // Daily after a false Growth-tab switch and drains Daily claims instead.
        ImageSearchResultData claim = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_CLAIM_BUTTON, CLAIM_QUICK_SEARCH);
        if (claim.isFound()) {
            return claim;
        }
        return this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_CLAIM_BUTTON, CLAIM_FALLBACK_QUICK_SEARCH);
    }

    private boolean isAllGrowthMissionsComplete() {
        OcrSettingsData[] presets;
        for (OcrSettingsData preset : presets = new OcrSettingsData[]{LeftMenuTextSettings.WHITE_SETTINGS, LeftMenuTextSettings.WHITE_NUMBERS, LeftMenuTextSettings.ORANGE_SETTINGS}) {
            try {
                String text = this.emuManager.readText(this.EMULATOR_NUMBER, ALL_COMPLETE_OCR_AREA.topLeft(), ALL_COMPLETE_OCR_AREA.bottomRight(), preset, true);
                if (text == null || text.isBlank()) continue;
                String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
                if (!GrowthMissionBuildRoutine.looksLikeAllGrowthComplete(normalized = normalized.replaceAll("\\s+", " ").trim())) continue;
                this.logInfo(this.logLine("Detected all-complete Growth text: \"" + text.trim() + "\""));
                return true;
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return false;
    }

    static boolean looksLikeAllGrowthComplete(String normalizedLower) {
        boolean hasMissionContext;
        if (normalizedLower == null || normalizedLower.isBlank()) {
            return false;
        }
        boolean hasComplete = normalizedLower.contains("complet") || normalizedLower.contains("finished") || normalizedLower.contains("done");
        boolean bl = hasMissionContext = normalizedLower.contains("mission") || normalizedLower.contains("growth") || normalizedLower.contains("all");
        if (hasComplete && hasMissionContext) {
            return true;
        }
        return normalizedLower.contains("no available") || normalizedLower.contains("come back later") || normalizedLower.contains("already completed");
    }

    private void logGoMiss() {
        ImageSearchResultData mainProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GO_BUTTON_MAIN, GO_SCORE_PROBE);
        ImageSearchResultData claimProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_CLAIM_BUTTON, GO_SCORE_PROBE);
        this.logWarning(this.logLine(String.format(Locale.ROOT, "No Growth mission Main Go in upper band. Best scores main=%.1f claim=%.1f", mainProbe.getMatchScore(), claimProbe.getMatchScore())));
    }

    private boolean ensureGrowthTabSelected() {
        if (!this.isMissionsPanelOpen()) {
            this.logWarning(this.logLine("Missions panel not detected after opening"));
            return false;
        }
        // One locate+tap only — a second unselected-template tap hits Chapter (Stuart Copy).
        if (this.tapGrowthTabOnce(1)) {
            this.sleepTask(1200L);
            return true;
        }
        this.logWarning(this.logLine("Could not locate Growth tab"));
        return false;
    }

    /**
     * OCR first; if live OCR returns garbage (e.g. strip="s"), fall back to the unselected
     * Growth template that worked before OCR-only. Never left-point fallback.
     */
    private boolean tapGrowthTabOnce(int attempt) {
        this.logInfo(this.logLine("Growth tab locate (attempt " + attempt + ") — OCR then template"));
        Optional<AreaData> ocrTapArea = this.findGrowthTabTapAreaByOcr();
        if (ocrTapArea.isPresent()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "OCR located Growth tab — jitter tap inside %s (attempt %d)",
                    ocrTapArea.get(), attempt)));
            this.tapInside(ocrTapArea.get());
            return true;
        }

        ImageSearchResultData unselected = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_UNSELECTED, GROWTH_TAB_SEARCH);
        if (unselected.isFound() && unselected.getPoint() != null) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "OCR miss — tapping Growth unselected template at %s (score %.1f)",
                    unselected.getPoint(), unselected.getMatchScore())));
            this.tapGrowthTabHit(unselected);
            return true;
        }
        ImageSearchResultData selected = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_SELECTED, GROWTH_TAB_SEARCH);
        if (selected.isFound() && selected.getPoint() != null && selected.getPoint().getX() < 360) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "OCR miss — tapping Growth selected template at %s (score %.1f)",
                    selected.getPoint(), selected.getMatchScore())));
            this.tapGrowthTabHit(selected);
            return true;
        }
        this.logWarning(this.logLine("Growth tab OCR and templates missed (attempt " + attempt + ")"));
        return false;
    }

    private void tapGrowthTabHit(ImageSearchResultData hit) {
        PointData point = hit.getPoint();
        int x = point.getX();
        int y = point.getY();
        if (x < GROWTH_TAB_SAFE_X_MIN) {
            x = GROWTH_TAB_SAFE_X_MIN;
        } else if (x > GROWTH_TAB_SAFE_X_MAX) {
            x = GROWTH_TAB_SAFE_X_MAX;
        }
        if (y < GROWTH_TAB_SAFE_Y_MIN) {
            y = GROWTH_TAB_SAFE_Y_MIN;
        } else if (y > GROWTH_TAB_SAFE_Y_MAX) {
            y = GROWTH_TAB_SAFE_Y_MAX;
        }
        AreaData jitter = new AreaData(
                new PointData(Math.max(GROWTH_TAB_SAFE_X_MIN, x - 24), GROWTH_TAB_SAFE_Y_MIN),
                new PointData(Math.min(GROWTH_TAB_SAFE_X_MAX, x + 24), GROWTH_TAB_SAFE_Y_MAX));
        this.tapInside(jitter);
    }

    /**
     * Locate Growth on {@link #MISSIONS_TAB_BAR_AREA}. Prefer Debugging-style full-strip
     * OCR (live sliding windows returned garbage like "eeee"/"cl" while Debug read Growth).
     * Sliding windows only accept Growth-like text; else word-order heuristic places the tap.
     */
    private Optional<AreaData> findGrowthTabTapAreaByOcr() {
        this.emuManager.captureScreen(this.EMULATOR_NUMBER);

        String fullStrip = this.readTabBarBestText(MISSIONS_TAB_BAR_AREA);
        if (fullStrip != null && !fullStrip.isBlank()) {
            this.logInfo(this.logLine("Tab-strip OCR: \"" + fullStrip + "\""));
        }

        List<TabOcrHit> hits = new ArrayList<>();
        int barLeft = MISSIONS_TAB_BAR_AREA.topLeft().getX();
        int barRight = MISSIONS_TAB_BAR_AREA.bottomRight().getX();
        int barTop = TAB_OCR_LABEL_Y_MIN;
        int barBottom = TAB_OCR_LABEL_Y_MAX;
        for (int left = barLeft; left + TAB_OCR_WINDOW_WIDTH <= barRight; left += TAB_OCR_WINDOW_STEP) {
            int right = left + TAB_OCR_WINDOW_WIDTH;
            AreaData window = new AreaData(new PointData(left, barTop), new PointData(right, barBottom));
            // Only keep windows that actually read as Growth — ignore OCR garbage.
            Optional<String> growth = this.readTabBarGrowthLabel(window);
            if (growth.isEmpty()) {
                continue;
            }
            this.logDebug(this.logLine(String.format(Locale.ROOT,
                    "Tab OCR Growth window [%d,%d]: \"%s\"", left, right, growth.get())));
            hits.add(new TabOcrHit(left, right, growth.get()));
        }
        int lastLeft = Math.max(barLeft, barRight - TAB_OCR_WINDOW_WIDTH);
        if (hits.isEmpty() || hits.get(hits.size() - 1).left() != lastLeft) {
            AreaData tail = new AreaData(new PointData(lastLeft, barTop), new PointData(barRight, barBottom));
            Optional<String> growth = this.readTabBarGrowthLabel(tail);
            if (growth.isPresent()) {
                hits.add(new TabOcrHit(lastLeft, barRight, growth.get()));
            }
        }
        Optional<TabOcrHit> best = pickGrowthTabHit(hits);
        if (best.isPresent()) {
            return Optional.of(jitterAreaForTabHit(best.get()));
        }

        Optional<AreaData> fromFull = tapAreaFromTabStripText(fullStrip, MISSIONS_TAB_BAR_AREA);
        if (fromFull.isPresent()) {
            this.logInfo(this.logLine("Tab-strip OCR placed Growth via word order — jitter "
                    + fromFull.get()));
            return fromFull;
        }
        this.logWarning(this.logLine("Tab-bar OCR found no Growth label (windows="
                + hits.size() + ", strip=\"" + (fullStrip == null ? "" : fullStrip) + "\")"));
        return Optional.empty();
    }

    /**
     * Shrinks the OCR window horizontally (neighbor-tab margin) and clamps Y to the
     * safe pill band so {@link #tapInside(AreaData)} can randomize without Heroes nav.
     */
    static AreaData jitterAreaForTabHit(TabOcrHit hit) {
        int left = hit.left() + TAB_OCR_TAP_INSET_X;
        int right = hit.right() - TAB_OCR_TAP_INSET_X;
        if (right - left < 24) {
            int center = (hit.left() + hit.right()) / 2;
            left = center - 12;
            right = center + 12;
        }
        if (left < GROWTH_TAB_SAFE_X_MIN) {
            left = GROWTH_TAB_SAFE_X_MIN;
        }
        if (right > GROWTH_TAB_SAFE_X_MAX) {
            right = GROWTH_TAB_SAFE_X_MAX;
        }
        if (right <= left) {
            right = left + 16;
        }
        return new AreaData(
                new PointData(left, GROWTH_TAB_SAFE_Y_MIN),
                new PointData(right, GROWTH_TAB_SAFE_Y_MAX));
    }

    /** All non-blank OCR candidates for an area (Debug-like default + presets). */
    private List<String> readTabBarOcrCandidates(AreaData area) {
        List<String> out = new ArrayList<>();
        try {
            // Same path Debugging uses conceptually: no colour filter / whitelist.
            String text = this.emuManager.readText(
                    this.EMULATOR_NUMBER, area.topLeft(), area.bottomRight());
            addNormalizedOcrCandidate(out, text);
        } catch (Exception exception) {
            this.logDebug(this.logLine("Tab OCR default read failed on " + area + ": " + exception.getMessage()));
        }
        OcrSettingsData[] presets = new OcrSettingsData[]{
                TAB_PLAIN_OCR_SETTINGS,
                LeftMenuTextSettings.WHITE_SETTINGS,
                TAB_DARK_TEXT_SETTINGS,
                LeftMenuTextSettings.ORANGE_SETTINGS};
        for (OcrSettingsData preset : presets) {
            try {
                String text = this.emuManager.readText(
                        this.EMULATOR_NUMBER, area.topLeft(), area.bottomRight(), preset, true);
                addNormalizedOcrCandidate(out, text);
            } catch (Exception exception) {
                this.logDebug(this.logLine("Tab OCR failed on " + area + ": " + exception.getMessage()));
            }
        }
        return out;
    }

    private static void addNormalizedOcrCandidate(List<String> out, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return;
        }
        if (!out.contains(normalized)) {
            out.add(normalized);
        }
        // Map near-misses onto the known Mission tab vocabulary (Daily/Growth/Chapter).
        String canonical = canonicalizeMissionTabOcr(normalized);
        if (!canonical.isBlank() && !out.contains(canonical)) {
            out.add(canonical);
        }
    }

    private String readTabBarBestText(AreaData area) {
        List<String> candidates = this.readTabBarOcrCandidates(area);
        for (String candidate : candidates) {
            if (looksLikeGrowthLabel(candidate)) {
                return candidate;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    /** Only returns text when a candidate looks like Growth — never OCR garbage. */
    private Optional<String> readTabBarGrowthLabel(AreaData area) {
        for (String candidate : this.readTabBarOcrCandidates(area)) {
            if (looksLikeGrowthLabel(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * When sliding windows fail, place Growth from left-to-right tab words in the
     * full-strip OCR (Chapter|Growth → right slot; Daily|Growth|Chapter → middle).
     */
    static Optional<AreaData> tapAreaFromTabStripText(String normalized, AreaData bar) {
        if (normalized == null || !looksLikeGrowthLabel(normalized)) {
            return Optional.empty();
        }
        List<TabToken> tokens = new ArrayList<>();
        addTabToken(tokens, normalized, "daily");
        addTabToken(tokens, normalized, "growth");
        addTabToken(tokens, normalized, "growt");
        addTabToken(tokens, normalized, "grwth");
        addTabToken(tokens, normalized, "chapter");
        // Collapse growth truncations to one Growth token (earliest index).
        TabToken growth = null;
        List<TabToken> ordered = new ArrayList<>();
        for (TabToken token : tokens.stream()
                .sorted((a, b) -> Integer.compare(a.index(), b.index()))
                .toList()) {
            if (token.name().startsWith("grow")) {
                if (growth == null) {
                    growth = new TabToken("growth", token.index());
                    ordered.add(growth);
                }
                continue;
            }
            ordered.add(token);
        }
        if (growth == null) {
            return Optional.empty();
        }
        int slot = ordered.indexOf(growth);
        int slots = Math.max(1, ordered.size());
        int barLeft = bar.topLeft().getX();
        int barRight = bar.bottomRight().getX();
        int slotWidth = Math.max(80, (barRight - barLeft) / slots);
        int left = barLeft + slot * slotWidth + TAB_OCR_TAP_INSET_X;
        int right = barLeft + (slot + 1) * slotWidth - TAB_OCR_TAP_INSET_X;
        if (right - left < 24) {
            int center = (left + right) / 2;
            left = center - 12;
            right = center + 12;
        }
        left = Math.max(GROWTH_TAB_SAFE_X_MIN, left);
        right = Math.min(GROWTH_TAB_SAFE_X_MAX, right);
        if (right <= left) {
            right = left + 16;
        }
        return Optional.of(new AreaData(
                new PointData(left, GROWTH_TAB_SAFE_Y_MIN),
                new PointData(right, GROWTH_TAB_SAFE_Y_MAX)));
    }

    private static void addTabToken(List<TabToken> tokens, String normalized, String word) {
        int idx = normalized.indexOf(word);
        if (idx >= 0) {
            tokens.add(new TabToken(word, idx));
        }
    }

    private record TabToken(String name, int index) {
    }

    /**
     * Picks the OCR window that best matches Growth alone. Mixed reads like
     * "chapter growth" are penalized so the jitter area stays on the Growth pill.
     */
    static Optional<TabOcrHit> pickGrowthTabHit(List<TabOcrHit> hits) {
        TabOcrHit best = null;
        int bestScore = Integer.MAX_VALUE;
        for (TabOcrHit hit : hits) {
            if (!looksLikeGrowthLabel(hit.normalized())) {
                continue;
            }
            int score = hit.normalized().length();
            if (hit.normalized().contains("chapter") || hit.normalized().contains("daily")) {
                score += 50;
            }
            if (best == null || score < bestScore) {
                best = hit;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean isMissionsPanelOpen() {
        if (this.isOnGrowthMissions() || this.isOnDailyMissions()) {
            return true;
        }
        if (this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_UNSELECTED, GROWTH_TAB_SEARCH).isFound()
                || this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_GROWTH_TAB_SELECTED, GROWTH_TAB_SEARCH).isFound()) {
            return true;
        }
        // Chapter-only early cities have neither Daily title nor Growth title yet.
        this.emuManager.captureScreen(this.EMULATOR_NUMBER);
        return this.ocrAreaLooksLike(MISSIONS_TAB_BAR_AREA, text ->
                looksLikeGrowthLabel(text)
                        || text.contains("daily")
                        || text.contains("chapter"));
    }

    record TabOcrHit(int left, int right, String normalized) {
    }

    private boolean isOnDailyMissions() {
        if (this.isDailyTabSelected()) {
            return true;
        }
        return this.templateSearchHelper.locatePattern(TemplatesEnum.DAILY_MISSION_SCREEN_TITLE, DAILY_TITLE_SEARCH).isFound();
    }

    private boolean isOnGrowthMissions() {
        if (this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_SCREEN_TITLE, GROWTH_TITLE_SEARCH).isFound()) {
            return true;
        }
        // Title template flakes on some skins — OCR the header for "Growth".
        if (this.ocrAreaLooksLike(GROWTH_TITLE_AREA, GrowthMissionBuildRoutine::looksLikeGrowthLabel)) {
            this.logInfo(this.logLine("Growth title template missed — OCR confirmed Growth header"));
            return true;
        }
        return false;
    }

    /**
     * Reads {@code area} with white/orange alphabetic presets. Returns true when any
     * normalized OCR string matches {@code matcher}.
     */
    private boolean ocrAreaLooksLike(AreaData area, java.util.function.Predicate<String> matcher) {
        OcrSettingsData[] presets = new OcrSettingsData[]{
                LeftMenuTextSettings.WHITE_SETTINGS,
                LeftMenuTextSettings.ORANGE_SETTINGS};
        for (OcrSettingsData preset : presets) {
            try {
                String text = this.emuManager.readText(
                        this.EMULATOR_NUMBER, area.topLeft(), area.bottomRight(), preset, true);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ");
                normalized = normalized.replaceAll("\\s+", " ").trim();
                if (matcher.test(normalized)) {
                    this.logDebug(this.logLine("OCR match in " + area + ": \"" + text.trim() + "\""));
                    return true;
                }
            } catch (Exception exception) {
                this.logDebug(this.logLine("OCR read failed on " + area + ": " + exception.getMessage()));
            }
        }
        return false;
    }

    /** Known Missions bottom-tab labels — OCR is biased toward these words only. */
    private static final String[] MISSION_TAB_LABELS = {"daily", "growth", "chapter"};

    /**
     * Rewrites OCR tokens onto {@link #MISSION_TAB_LABELS} when close (edit distance).
     * "growlh"→growth, "chapler"→chapter. Junk like "s" / "eeee" stays unmatched.
     */
    static String canonicalizeMissionTabOcr(String normalizedLower) {
        if (normalizedLower == null || normalizedLower.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String token : normalizedLower.split(" ")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String mapped = closestMissionTabLabel(token).orElse(token);
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(mapped);
        }
        return out.toString();
    }

    static Optional<String> closestMissionTabLabel(String token) {
        if (token == null || token.length() < 4) {
            return Optional.empty();
        }
        for (String label : MISSION_TAB_LABELS) {
            if (label.equals(token) || label.startsWith(token) || token.startsWith(label)) {
                return Optional.of(label);
            }
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String label : MISSION_TAB_LABELS) {
            int distance = editDistance(token, label);
            int maxAllowed = label.length() <= 5 ? 1 : 2;
            if (distance <= maxAllowed && distance < bestDistance) {
                bestDistance = distance;
                best = label;
            }
        }
        return Optional.ofNullable(best);
    }

    static int editDistance(String left, String right) {
        int n = left.length();
        int m = right.length();
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; ++j) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; ++i) {
            cur[0] = i;
            char a = left.charAt(i - 1);
            for (int j = 1; j <= m; ++j) {
                int cost = a == right.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[m];
    }

    /** Header / left-tab label — exact Growth plus near-miss OCR mapped to Growth. */
    static boolean looksLikeGrowthLabel(String normalizedLower) {
        if (normalizedLower == null || normalizedLower.isBlank()) {
            return false;
        }
        if (normalizedLower.contains("growth")
                || normalizedLower.contains("growt")
                || normalizedLower.contains("grwth")) {
            return true;
        }
        return canonicalizeMissionTabOcr(normalizedLower).contains("growth");
    }

    /** Main mission green Claim pill. */
    static boolean looksLikeClaimLabel(String normalizedLower) {
        return normalizedLower != null && normalizedLower.contains("claim");
    }

    /**
     * Main mission blue Go pill. Reject Claim so a Claim-row OCR does not fire Go.
     * Accept short "go" / "g0" reads from the compact button crop.
     */
    static boolean looksLikeGoLabel(String normalizedLower) {
        if (normalizedLower == null || normalizedLower.isBlank()) {
            return false;
        }
        if (normalizedLower.contains("claim")) {
            return false;
        }
        String spaced = (" " + normalizedLower + " ").replaceAll("\\s+", " ");
        return spaced.contains(" go ")
                || spaced.contains(" g0 ")
                || normalizedLower.equals("go")
                || normalizedLower.equals("g0");
    }

    private boolean isDailyTabSelected() {
        return this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_DAILY_TAB_SELECTED, DAILY_TAB_SELECTED_SEARCH).isFound();
    }

    private UpgradeOutcome performBuildingUpgrades() {
        // Furnace before furniture: panel title is specific; furniture marker must not steal it.
        FurnacePanelDetector.Evidence furnace = this.inspectFurnacePanel();
        if (furnace.panelVisible()) {
            this.logInfo(this.logLine("Furnace panel detected — taking furnace road (" + furnace + ")"));
            return this.runFurnaceRoad(furnace);
        }
        // Identify the furniture panel before any center dismiss so we do not tap blindly.
        boolean furniturePanel = this.isFurniturePanelOpen();
        if (furniturePanel) {
            this.logInfo(this.logLine("Furniture panel marker confirmed — taking furniture road"));
            // Furniture missions have no guide-hand overlay; center dismiss is building-road only.
            return this.runFurnitureRoad();
        }
        this.logInfo(this.logLine("No furnace/furniture panel — taking building road"));
        return this.runBuildingRoad();
    }

    private FurnacePanelDetector.Evidence inspectFurnacePanel() {
        return FurnacePanelDetector.inspect((template, area, threshold) ->
                this.templateSearchHelper.locatePattern(template,
                        TemplateSearchHelper.SearchConfig.builder()
                                .withMaxAttempts(1)
                                .withThreshold(threshold)
                                .withArea(area)
                                .build()));
    }

    /**
     * Furnace Growth mission: {@link FurnacePanelDetector} proves identity. Upgrade control
     * may be the orange furnace entry or the same blue Upgrade as completed furniture.
     */
    private UpgradeOutcome runFurnaceRoad(FurnacePanelDetector.Evidence evidence) {
        ImageSearchResultData upgrade = this.resolveFurnaceUpgradeControl(evidence);
        if (upgrade == null || !upgrade.isFound()) {
            this.logWarning(this.logLine(
                    "Furnace panel visible but no Upgrade control matched — aborting furnace road"));
            return new UpgradeOutcome(false, false, false, false);
        }
        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Furnace road: tapping Upgrade at %s (score %.1f)",
                upgrade.getPoint(), upgrade.getMatchScore())));
        boolean started = this.confirmBuildingUpgrade(upgrade);
        return new UpgradeOutcome(started, false, false, false);
    }

    /** Prefer detector Upgrade; else Growth header / furniture Upgrade in the furnace band. */
    private ImageSearchResultData resolveFurnaceUpgradeControl(FurnacePanelDetector.Evidence evidence) {
        if (evidence.actionable()) {
            this.logInfo(this.logLine("Furnace road: using FurnacePanelDetector Upgrade"));
            return evidence.upgrade();
        }
        ImageSearchResultData header = this.findHeaderUpgrade();
        if (header.isFound()) {
            this.logInfo(this.logLine(
                    "Furnace road: detector Upgrade miss — using Growth header Upgrade (completed-furniture style)"));
            return header;
        }
        ImageSearchResultData furnitureStyle = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_UPGRADE,
                TemplateSearchHelper.SearchConfig.builder()
                        .withMaxAttempts(2)
                        .withDelay(200L)
                        .withThreshold(88)
                        .withArea(CommonGameAreas.FURNACE_PANEL_UPGRADE)
                        .build());
        if (furnitureStyle.isFound()) {
            this.logInfo(this.logLine(
                    "Furnace road: using furniture-style Upgrade in furnace button band"));
            return furnitureStyle;
        }
        this.logInfo(this.logLine("Furnace road: " + evidence
                + "; header/furniture Upgrade also missed"));
        return ImageSearchResultData.miss();
    }

    /**
     * Furniture mission loop:
     * <ul>
     *   <li>Save-steel (default): single Upgrade taps, Next between pieces, refuse steel cost.</li>
     *   <li>Fast hold: inspect main Upgrade and Next first; only then 2s piece holds.</li>
     *   <li>When every piece is done, header Upgrade upgrades the building (confirm OK).</li>
     * </ul>
     */
    private UpgradeOutcome runFurnitureRoad() {
        if (this.isFurnitureSaveSteelEnabled()) {
            this.logInfo(this.logLine("Furniture road: save-steel mode (single taps)"));
            return this.runFurnitureRoadSaveSteel();
        }
        this.logInfo(this.logLine("Furniture road: fast hold mode (inspect Upgrade/Next before any hold)"));
        return this.runFurnitureRoadFastHold();
    }

    private boolean isFurnitureSaveSteelEnabled() {
        Boolean saveSteel = this.profile.getConfig(
                ConfigurationKeyEnum.CITY_GROWTH_MISSION_FURNITURE_SAVE_STEEL_BOOL, Boolean.class);
        return saveSteel == null || saveSteel;
    }

    private UpgradeOutcome runFurnitureRoadSaveSteel() {
        boolean buildingStarted = false;
        boolean furnitureProgress = false;
        boolean steelBlocked = false;
        for (int step = 1; step <= MAX_FURNITURE_STEPS; ++step) {
            this.sleepTask(500L);

            // All furniture finished — building Upgrade is available.
            ImageSearchResultData headerUpgrade = this.findHeaderUpgrade();
            if (headerUpgrade.isFound()) {
                this.logInfo(this.logLine("All furniture done — tapping building header Upgrade (step " + step + ")"));
                if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                    break;
                }
                buildingStarted = true;
                break;
            }

            FurnitureStepResult furniture = this.tryFurnitureRoadStep(step);
            if (furniture == FurnitureStepResult.ADVANCED) {
                furnitureProgress = true;
                continue;
            }
            if (furniture == FurnitureStepResult.STARTED) {
                furnitureProgress = true;
                continue;
            }
            if (furniture == FurnitureStepResult.STEEL_BLOCKED) {
                steelBlocked = true;
                headerUpgrade = this.findHeaderUpgrade();
                if (!headerUpgrade.isFound()) {
                    break;
                }
                this.logInfo(this.logLine("Building header Upgrade after steel furniture guard"));
                if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                    break;
                }
                buildingStarted = true;
                break;
            }
            if (furniture == FurnitureStepResult.FAILED) {
                break;
            }

            this.logInfo(this.logLine("Furniture road: no Next/Upgrade controls (step " + step + ")"));
            break;
        }
        return new UpgradeOutcome(buildingStarted, steelBlocked, furnitureProgress, false);
    }

    /**
     * Fast furniture: always inspect main (header) Upgrade and Next before any piece
     * hold. Holds are only for unfinished pieces. Main Upgrade is tap+confirm, never held.
     * Live Stuart Copy held first when header was already ready but the first inspect missed.
     */
    private UpgradeOutcome runFurnitureRoadFastHold() {
        boolean buildingStarted = false;
        boolean furnitureProgress = false;
        int holds = 0;

        // Settle + preflight so we never 2s-hold when main Upgrade/Next is already up.
        this.sleepTask(900L);
        Optional<UpgradeOutcome> preflight = this.tryFastFurnitureInspectActions(0, true);
        if (preflight.isPresent()) {
            return preflight.get();
        }

        for (int step = 1; step <= MAX_FURNITURE_STEPS; ++step) {
            this.sleepTask(400L);

            Optional<UpgradeOutcome> inspected = this.tryFastFurnitureInspectActions(step, false);
            if (inspected.isPresent()) {
                UpgradeOutcome outcome = inspected.get();
                if (outcome.startedConstruction()) {
                    return outcome;
                }
                if (outcome.furnitureProgress()) {
                    furnitureProgress = true;
                }
                continue;
            }

            ImageSearchResultData furnitureUpgrade = this.findFurniturePieceUpgrade();
            if (!furnitureUpgrade.isFound()) {
                this.logInfo(this.logLine("Fast furniture: no Upgrade/Next/header (step " + step + ")"));
                break;
            }

            // Last look before committing a 2s hold — header can appear as the panel settles.
            Optional<UpgradeOutcome> beforeHold = this.tryFastFurnitureInspectActions(step, false);
            if (beforeHold.isPresent()) {
                UpgradeOutcome outcome = beforeHold.get();
                if (outcome.startedConstruction()) {
                    return outcome;
                }
                if (outcome.furnitureProgress()) {
                    furnitureProgress = true;
                }
                continue;
            }

            if (holds >= FAST_FURNITURE_MAX_HOLDS) {
                this.logWarning(this.logLine(
                        "Fast furniture: reached max 2s holds (" + FAST_FURNITURE_MAX_HOLDS + ")"));
                furnitureProgress = true;
                break;
            }

            ++holds;
            PointData holdPoint = furnitureUpgrade.getPoint();
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Fast furniture: 2s hold on piece Upgrade at %s (%d/%d, step %d)",
                    holdPoint, holds, FAST_FURNITURE_MAX_HOLDS, step)));
            this.swipe(holdPoint, holdPoint, FAST_FURNITURE_HOLD_MS);
            this.sleepTask(700L);
            furnitureProgress = true;
        }

        return new UpgradeOutcome(buildingStarted, false, furnitureProgress, false);
    }

    /**
     * Inspect-only actions for fast furniture: main Upgrade (start building) or Next
     * (piece complete). Empty = neither ready, caller may hold a piece Upgrade.
     */
    private Optional<UpgradeOutcome> tryFastFurnitureInspectActions(int step, boolean preflight) {
        String phase = preflight ? "preflight" : ("step " + step);
        ImageSearchResultData headerUpgrade = this.findHeaderUpgrade();
        if (headerUpgrade.isFound()) {
            this.logInfo(this.logLine("Fast furniture: main Upgrade ready (" + phase
                    + ") — tap confirm only, no hold"));
            if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                return Optional.of(new UpgradeOutcome(false, false, false, false));
            }
            return Optional.of(new UpgradeOutcome(true, false, false, false));
        }

        ImageSearchResultData nextButton = this.findFurnitureNext();
        if (nextButton.isFound()) {
            this.logInfo(this.logLine("Fast furniture: Next ready (" + phase + ") — tapping "
                    + nextButton.getPoint()));
            this.tapNear(nextButton.getPoint(), 6);
            this.sleepTask(1200L);
            return Optional.of(new UpgradeOutcome(false, false, true, false));
        }
        if (preflight) {
            this.logInfo(this.logLine("Fast furniture: preflight — no main Upgrade/Next yet"));
        }
        return Optional.empty();
    }

    /** Piece Upgrade only; reject hits in the building header band. */
    private ImageSearchResultData findFurniturePieceUpgrade() {
        ImageSearchResultData furnitureUpgrade = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_UPGRADE, FURNITURE_UPGRADE_SEARCH);
        if (!furnitureUpgrade.isFound()) {
            return furnitureUpgrade;
        }
        PointData point = furnitureUpgrade.getPoint();
        if (point != null && point.getY() < FURNITURE_UPGRADE_MIN_Y) {
            this.logWarning(this.logLine(String.format(Locale.ROOT,
                    "Ignoring Upgrade at %s (y<%d — building header band, not piece)",
                    point, FURNITURE_UPGRADE_MIN_Y)));
            return ImageSearchResultData.miss();
        }
        return furnitureUpgrade;
    }

    private UpgradeOutcome runBuildingRoad() {
        boolean started = false;
        boolean cityArrowMissed = false;
        for (int step = 1; step <= MAX_FURNITURE_STEPS; ++step) {
            this.sleepTask(500L);

            ImageSearchResultData headerUpgrade = this.findHeaderUpgrade();
            if (headerUpgrade.isFound()) {
                this.logInfo(this.logLine("Building road: main header Upgrade (step " + step + ")"));
                if (!this.confirmBuildingUpgrade(headerUpgrade)) {
                    break;
                }
                started = true;
                break;
            }

            this.logInfo(this.logLine("Building road: searching city up-arrow (step " + step + ")"));
            ImageSearchResultData cityArrow = this.locateCityUpgradeArrowWithGuideRetry();
            if (cityArrow.isFound()) {
                this.logInfo(this.logLine(String.format(Locale.ROOT,
                        "Building road: city up-arrow at %s (score %.1f, step %d)",
                        cityArrow.getPoint(), cityArrow.getMatchScore(), step)));
                if (!this.upgradeViaCityArrow(cityArrow)) {
                    break;
                }
                started = true;
                break;
            }
            this.logInfo(this.logLine("Building road: no city up-arrow (step " + step + ")"));
            cityArrowMissed = true;
            break;
        }
        return new UpgradeOutcome(started, false, false, cityArrowMissed && !started);
    }

    private boolean isFurniturePanelOpen() {
        return this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_PANEL, FURNITURE_PANEL_SEARCH).isFound();
    }

    /**
     * Per-furniture step: Next means this piece is done (100%) — advance.
     * Upgrade means upgrade this piece only (no building confirm dialog).
     * Building confirm is only for header Upgrade after every piece is done.
     */
    private FurnitureStepResult tryFurnitureRoadStep(int step) {
        ImageSearchResultData nextButton = this.findFurnitureNext();
        if (nextButton.isFound()) {
            this.logInfo(this.logLine("Furniture road: piece complete — tapping Next at "
                    + nextButton.getPoint() + " (step " + step + ")"));
            this.tapNear(nextButton.getPoint(), 6);
            this.sleepTask(1200L);
            return FurnitureStepResult.ADVANCED;
        }

        ImageSearchResultData furnitureUpgrade = this.findFurniturePieceUpgrade();
        if (!furnitureUpgrade.isFound()) {
            this.logFurnitureNextMiss(step);
            return FurnitureStepResult.NONE;
        }

        if (this.isSteelFurnitureCost()) {
            this.logWarning(this.logLine("Furniture road: STEEL SAVE — refusing furniture Upgrade"));
            return FurnitureStepResult.STEEL_BLOCKED;
        }

        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Furniture road: tapping piece Upgrade at %s (score %.1f, step %d)",
                furnitureUpgrade.getPoint(), furnitureUpgrade.getMatchScore(), step)));
        this.tapInside(furnitureUpgrade);
        this.sleepTask(1000L);
        if (!this.refillResourcesIfNeeded()) {
            this.logWarning(this.logLine("Furniture road: resource replenishment failed"));
            return FurnitureStepResult.FAILED;
        }
        // Piece Upgrade has no confirm dialog; button becomes Next when the piece hits 100%.
        this.sleepTask(1500L);
        nextButton = this.findFurnitureNext();
        if (nextButton.isFound()) {
            this.logInfo(this.logLine("Furniture road: piece hit 100% — tapping Next at "
                    + nextButton.getPoint() + " (step " + step + ")"));
            this.tapNear(nextButton.getPoint(), 6);
            this.sleepTask(1200L);
            return FurnitureStepResult.ADVANCED;
        }
        return FurnitureStepResult.STARTED;
    }

    private ImageSearchResultData findFurnitureNext() {
        return this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_NEXT, FURNITURE_NEXT_SEARCH);
    }

    private void logFurnitureNextMiss(int step) {
        ImageSearchResultData probe = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_FURNITURE_NEXT, FURNITURE_NEXT_SCORE_PROBE);
        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Furniture road: Next not visible (best=%.1f, step %d)",
                probe.getMatchScore(), step)));
    }

    /**
     * Building road only: center-tap to clear the guide hand, then look for the up-arrow.
     * If the arrow is still missing, center-tap once more and search again. Cap is two
     * center taps — further repeats used to leave the city view stuck. Furniture never
     * calls this.
     */
    private ImageSearchResultData locateCityUpgradeArrowWithGuideRetry() {
        this.dismissGrowthGuideOverlay();
        ImageSearchResultData arrow = this.findCityUpgradeArrow(false);
        if (arrow.isFound()) {
            return arrow;
        }
        this.logInfo(this.logLine("City up-arrow missed after first center dismiss — retrying center tap once"));
        this.dismissGrowthGuideOverlay();
        return this.findCityUpgradeArrow(true);
    }

    /**
     * After Growth Go on a normal building, a guide hand / highlight may cover the
     * Upgrade arrow. One jittered center tap clears it. A second tap is allowed only
     * when the up-arrow is still missing. Furniture road never calls this.
     */
    private void dismissGrowthGuideOverlay() {
        if (this.growthGuideDismissCount >= MAX_GUIDE_CENTER_DISMISS) {
            return;
        }
        ++this.growthGuideDismissCount;
        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Tapping building center to dismiss guide hand / highlight overlay (%d/%d)",
                this.growthGuideDismissCount, MAX_GUIDE_CENTER_DISMISS)));
        this.tapInside(BUILDING_CENTER_DISMISS_AREA);
        this.sleepTask(BUILDING_CENTER_DISMISS_SETTLE_MS);
    }

    private ImageSearchResultData findHeaderUpgrade() {
        return this.templateSearchHelper.locatePattern(TemplatesEnum.GROWTH_MISSION_BUILDING_HEADER_UPGRADE, HEADER_UPGRADE_SEARCH);
    }

    private ImageSearchResultData findCityUpgradeArrow(boolean logMiss) {
        ImageSearchResultData arrow = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_CITY_UPGRADE_ARROW, CITY_UPGRADE_ARROW_SEARCH);
        if (arrow.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "City up-arrow template matched at %s (score %.1f)",
                    arrow.getPoint(), arrow.getMatchScore())));
            return arrow;
        }

        ImageSearchResultData legacy = this.templateSearchHelper.locatePattern(
                TemplatesEnum.BUILDING_BUTTON_UPGRADE, CITY_UPGRADE_ARROW_SEARCH);
        if (legacy.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "City up-arrow matched via legacy building/upgradeButton (score %.1f at %s)",
                    legacy.getMatchScore(), legacy.getPoint())));
            return legacy;
        }

        if (logMiss) {
            ImageSearchResultData growthProbe = this.templateSearchHelper.locatePattern(
                    TemplatesEnum.GROWTH_MISSION_BUILDING_CITY_UPGRADE_ARROW, CITY_UPGRADE_ARROW_SCORE_PROBE);
            ImageSearchResultData legacyProbe = this.templateSearchHelper.locatePattern(
                    TemplatesEnum.BUILDING_BUTTON_UPGRADE, CITY_UPGRADE_ARROW_SCORE_PROBE);
            this.logWarning(this.logLine(String.format(Locale.ROOT,
                    "City up-arrow templates missed after center-dismiss retry (best growth=%.1f legacy=%.1f)",
                    growthProbe.getMatchScore(), legacyProbe.getMatchScore())));
        }
        return ImageSearchResultData.miss();
    }

    private boolean upgradeViaCityArrow(ImageSearchResultData cityArrow) {
        this.tapInside(cityArrow);
        this.sleepTask(2000L);
        if (!this.refillResourcesIfNeeded()) {
            return false;
        }

        ImageSearchResultData panelUpgrade = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GROWTH_MISSION_BUILDING_PANEL_UPGRADE, BUILDING_PANEL_UPGRADE_SEARCH);
        if (panelUpgrade.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Tapping building panel Upgrade at %s (score %.1f)",
                    panelUpgrade.getPoint(), panelUpgrade.getMatchScore())));
            this.tapInside(panelUpgrade);
            this.sleepTask(1500L);
            if (this.locateConfirmUpgradeButton().isFound()) {
                return this.confirmUpgradeDialog();
            }
            this.tapAllianceHelp();
            return true;
        }

        this.logInfo(this.logLine("Panel Upgrade template missed — trying confirm Upgrade text/icon"));
        return this.confirmUpgradeDialog();
    }

    /**
     * Steel guard only — furniture Upgrade itself is resource-agnostic.
     * Compares steel against other cost icons so a weak steel false-positive does not block meat/wood rows.
     */
    private boolean isSteelFurnitureCost() {
        double meat = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_MEAT);
        double wood = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_WOOD);
        double iron = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_IRON);
        double coal = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_COAL);
        double steel = this.matchScore(TemplatesEnum.GROWTH_MISSION_RESOURCE_ICON_STEEL);
        this.logInfo(this.logLine(String.format(Locale.ROOT,
                "Furniture steel-guard scores meat=%.1f wood=%.1f iron=%.1f coal=%.1f steel=%.1f (steel>=%.0f)",
                meat, wood, iron, coal, steel, RESOURCE_STEEL_GUARD_THRESHOLD)));
        return steel >= RESOURCE_STEEL_GUARD_THRESHOLD
                && steel >= meat
                && steel >= wood
                && steel >= iron
                && steel >= coal;
    }

    private double matchScore(TemplatesEnum iconTemplate) {
        ImageSearchResultData hit = this.templateSearchHelper.locatePattern(iconTemplate, TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(RESOURCE_ICON_AREA).build());
        return hit.isFound() ? hit.getMatchScore() : 0.0;
    }

    private boolean confirmBuildingUpgrade(ImageSearchResultData upgradeButton) {
        this.tapInside(upgradeButton);
        this.sleepTask(2000L);
        if (!this.refillResourcesIfNeeded()) {
            return false;
        }
        return this.confirmUpgradeDialog();
    }

    private ImageSearchResultData locateConfirmUpgradeButton() {
        ImageSearchResultData textHit = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, BUILDING_CONFIRM_UPGRADE_SEARCH);
        if (textHit.isFound()) {
            return textHit;
        }
        ImageSearchResultData relaxedText = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, BUILDING_CONFIRM_UPGRADE_RELAXED);
        if (relaxedText.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Confirm Upgrade matched at relaxed threshold (score %.1f at %s)", relaxedText.getMatchScore(), relaxedText.getPoint())));
            return relaxedText;
        }
        ImageSearchResultData iconHit = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE, BUILDING_CONFIRM_UPGRADE_RELAXED);
        if (iconHit.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT, "Confirm Upgrade icon fallback matched (score %.1f at %s)", iconHit.getMatchScore(), iconHit.getPoint())));
            return iconHit;
        }
        return ImageSearchResultData.miss();
    }

    private void logConfirmUpgradeMiss() {
        ImageSearchResultData textProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT, BUILDING_CONFIRM_UPGRADE_SCORE_PROBE);
        ImageSearchResultData iconProbe = this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE, BUILDING_CONFIRM_UPGRADE_SCORE_PROBE);
        this.logWarning(this.logLine(String.format(Locale.ROOT, "Confirm Upgrade not found. Best scores text=%.1f icon=%.1f obtainVisible=%s", textProbe.getMatchScore(), iconProbe.getMatchScore(), this.templateSearchHelper.locatePattern(TemplatesEnum.GAME_HOME_SHORTCUTS_OBTAIN, SearchConfigConstants.DEFAULT_SINGLE).isFound())));
    }

    private boolean confirmUpgradeDialog() {
        BuildingUpgradeConfirmationFlow.Outcome outcome = BuildingUpgradeConfirmationFlow.run(
                new BuildingUpgradeConfirmationFlow.Ui() {
                    @Override
                    public boolean tapDetectedUpgrade() {
                        ImageSearchResultData upgrade = locateConfirmUpgradeButton();
                        if (upgrade.isFound()) {
                            logInfo(logLine(String.format(Locale.ROOT,
                                    "Upgrade confirmation detected at %s (score %.1f)",
                                    upgrade.getPoint(), upgrade.getMatchScore())));
                            return tapInside(upgrade);
                        }
                        logConfirmUpgradeMiss();
                        // Last resort only — dialog Upgrade Y shifts by building; prefer label match.
                        logWarning(logLine("Upgrade label miss — last-resort tap of dialog Upgrade area "
                                + BUILDING_DIALOG_UPGRADE_TAP_AREA));
                        tapInside(BUILDING_DIALOG_UPGRADE_TAP_AREA);
                        return true;
                    }

                    @Override
                    public void waitForTransition() {
                        sleepTask(500L);
                    }

                    @Override
                    public boolean isConfirmationPending() {
                        boolean upgradeActionVisible = templateSearchHelper.locatePattern(
                                TemplatesEnum.GAME_HOME_SHORTCUTS_UPGRADE_TEXT,
                                BUILDING_CONFIRM_UPGRADE_POSTCONDITION).isFound();
                        if (upgradeActionVisible) {
                            return true;
                        }
                        return !templateSearchHelper.locatePattern(
                                TemplatesEnum.GAME_HOME_FURNACE,
                                SearchConfigConstants.DEFAULT_SINGLE).isFound();
                    }
                },
                3);
        if (outcome == BuildingUpgradeConfirmationFlow.Outcome.CONFIRMED) {
            this.tapAllianceHelp();
            return true;
        }
        // Dave 2026-09-19: Upgrade tap started construction but Home furnace was not
        // visible in time → POSTCONDITION_NOT_MET skipped queue OCR and Growth kept
        // opening the next mission while a build was still running.
        if (outcome == BuildingUpgradeConfirmationFlow.Outcome.POSTCONDITION_NOT_MET) {
            this.logWarning(this.logLine(
                    "Upgrade confirmation outcome: POSTCONDITION_NOT_MET — treating as started; will OCR construction queue"));
            this.tapAllianceHelp();
            return true;
        }
        this.logWarning(this.logLine("Upgrade confirmation outcome: " + String.valueOf(outcome)));
        return false;
    }

    private boolean refillResourcesIfNeeded() {
        RepeatedResourceReplenishmentFlow.Result result = RepeatedResourceReplenishmentFlow.run(
                new RepeatedResourceReplenishmentFlow.Ui() {
                    @Override
                    public PointData findReplenishAll() {
                        return findReplenishAllButton();
                    }

                    @Override
                    public PointData findObtain() {
                        ImageSearchResultData hit = templateSearchHelper.locatePattern(
                                TemplatesEnum.GAME_HOME_SHORTCUTS_OBTAIN,
                                SearchConfigConstants.DEFAULT_SINGLE);
                        return hit.isFound() ? hit.getPoint() : null;
                    }

                    @Override
                    public void openObtain(PointData point) {
                        logInfo(logLine("Opening Obtain more for missing Growth resources at " + point));
                        tapNear(point);
                        sleepTask(1200L);
                        emuManager.captureScreen(EMULATOR_NUMBER);
                    }

                    @Override
                    public void waitAfterObtainOpened(int attempt) {
                        logInfo(logLine("Waiting for Replenish All after Obtain (poll " + attempt + ")"));
                        sleepTask(700L);
                        emuManager.captureScreen(EMULATOR_NUMBER);
                    }

                    @Override
                    public void replenishAndConfirm(PointData point) {
                        logInfo(logLine("Refilling one missing resource for Growth building upgrade..."));
                        tapNear(point);
                        sleepTask(300L);
                        tapNear(REPLENISH_CONFIRM_POINT);
                        sleepTask(1000L);
                    }
                },
                MAX_RESOURCE_REPLENISHMENTS);
        if (!result.ready()) {
            this.logWarning(this.logLine("Resource replenishment outcome: " + String.valueOf(result.outcome())));
        }
        return result.ready();
    }

    /**
     * Locate bottom-sheet Replenish All after Obtain. Stuart Copy missed the tight
     * 90% band within 500ms — widen, relax, multi-scale, then OCR.
     */
    private PointData findReplenishAllButton() {
        this.emuManager.captureScreen(this.EMULATOR_NUMBER);
        ImageSearchResultData hit = this.templateSearchHelper.locatePattern(
                TemplatesEnum.REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_RECHECK);
        if (hit.isFound()) {
            return hit.getPoint();
        }
        hit = this.templateSearchHelper.locatePattern(
                TemplatesEnum.REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_RELAXED);
        if (hit.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Replenish All matched relaxed template at %s (score %.1f)",
                    hit.getPoint(), hit.getMatchScore())));
            return hit.getPoint();
        }
        hit = this.templateSearchHelper.locatePatternMultiScale(
                TemplatesEnum.REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_RELAXED);
        if (hit != null && hit.isFound()) {
            this.logInfo(this.logLine(String.format(Locale.ROOT,
                    "Replenish All matched multi-scale at %s (score %.1f)",
                    hit.getPoint(), hit.getMatchScore())));
            return hit.getPoint();
        }
        Optional<PointData> ocrTap = this.findReplenishAllByOcr();
        if (ocrTap.isPresent()) {
            return ocrTap.get();
        }
        ImageSearchResultData probe = this.templateSearchHelper.locatePattern(
                TemplatesEnum.REPLENISH_ALL_BUTTON, REPLENISH_BUTTON_SCORE_PROBE);
        this.logWarning(this.logLine(String.format(Locale.ROOT,
                "Replenish All not found (best template score %.1f in %s)",
                probe.getMatchScore(), REPLENISH_ALL_AREA)));
        return null;
    }

    /** OCR backup when the Obtain-more sheet shows Replenish All but the template misses. */
    private Optional<PointData> findReplenishAllByOcr() {
        try {
            String text = this.emuManager.readText(
                    this.EMULATOR_NUMBER,
                    REPLENISH_ALL_AREA.topLeft(),
                    REPLENISH_ALL_AREA.bottomRight());
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ");
            normalized = normalized.replaceAll("\\s+", " ").trim();
            if (!(normalized.contains("replenish") || normalized.contains("replenishall"))) {
                this.logDebug(this.logLine("Replenish OCR miss: \"" + text.trim() + "\""));
                return Optional.empty();
            }
            this.logInfo(this.logLine("Replenish All via OCR (\"" + text.trim()
                    + "\") — tapping " + REPLENISH_ALL_OCR_TAP));
            return Optional.of(REPLENISH_ALL_OCR_TAP);
        } catch (Exception exception) {
            this.logDebug(this.logLine("Replenish OCR error: " + exception.getMessage()));
            return Optional.empty();
        }
    }

    private boolean tapAllianceHelp() {
        ImageSearchResultData help = this.templateSearchHelper.locatePatternMultiScale(TemplatesEnum.GAME_HOME_SHORTCUTS_HELP_REQUEST4, SearchConfigConstants.HIGH_SENSITIVITY);
        if (help == null || !help.isFound()) {
            return false;
        }
        this.tapInside(help.getPoint(), help.getPoint(), 1, 500);
        return true;
    }

    private void scheduleAfterConstruction() {
        if (!this.tryScheduleFromConstructionQueue("after construction start")) {
            LocalDateTime fallback = LocalDateTime.now().plusMinutes(DEFAULT_RETRY_MINUTES);
            this.logWarning(this.logLine(
                    "No construction timer from CITY sidebar or city-map OCR. "
                            + "Fixed fallback reschedule at " + fallback.format(DATETIME_FORMATTER)));
            this.reschedule(fallback);
        }
    }

    /**
     * Prefer CITY left-menu queue timers; if that path fails (sidebar locked on early
     * accounts, or unreadable), OCR {@link #CITY_MAP_CONSTRUCTION_TIMER_AREA}.
     */
    private boolean tryScheduleFromConstructionQueue(String reason) {
        // Construction UI needs a moment before the left-menu timer is readable.
        this.sleepTask(2500L);
        // execute() already dismissed to Home — avoid pressing Back again on Home
        // (that can leave the CITY sidebar in a half-open state before OCR).
        if (!this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
            this.dismissToHome();
        }
        if (!this.navigationHelper.openSidebarSection(SidebarSection.CITY)) {
            this.logInfo(this.logLine(
                    "CITY sidebar unavailable — trying city-map construction timer OCR (" + reason + ")"));
            return this.tryScheduleFromCityMapConstructionTimer(reason);
        }
        this.sleepTask(1500L);

        for (int attempt = 1; attempt <= 5; ++attempt) {
            // Force a fresh frame — reuseFrame OCR would otherwise read the Home
            // capture from the furnace check taken before the CITY sidebar opened.
            this.emuManager.captureScreen(this.EMULATOR_NUMBER);
            Optional<Duration> wait = this.readShortestQueueWait(attempt == 5);
            if (wait.isPresent()) {
                LocalDateTime next = LocalDateTime.now().plus(wait.get()).plusSeconds(2L);
                this.logInfo(this.logLine("Construction timer OCR \u2192 next run at "
                        + next.format(DATETIME_FORMATTER)
                        + " (attempt " + attempt + ", " + reason + ")"));
                this.reschedule(next);
                this.marchHelper.closeLeftMenu();
                return true;
            }
            this.logInfo(this.logLine(
                    "Construction timer OCR miss (attempt " + attempt + "/5, " + reason + ")"));
            this.sleepTask(800L);
        }

        this.marchHelper.closeLeftMenu();
        this.logInfo(this.logLine(
                "CITY sidebar queue OCR failed — trying city-map construction timer (" + reason + ")"));
        return this.tryScheduleFromCityMapConstructionTimer(reason);
    }

    /**
     * Experimental fallback: OCR a fixed city-map countdown band when sidebar queue
     * OCR cannot run. Region and reliability still need heavy live testing.
     */
    private boolean tryScheduleFromCityMapConstructionTimer(String reason) {
        for (int attempt = 1; attempt <= CITY_MAP_TIMER_OCR_ATTEMPTS; ++attempt) {
            this.emuManager.captureScreen(this.EMULATOR_NUMBER);
            Optional<Duration> wait = this.readQueueDuration(CITY_MAP_CONSTRUCTION_TIMER_AREA, true);
            if (wait.isPresent()) {
                LocalDateTime next = LocalDateTime.now().plus(wait.get()).plusSeconds(2L);
                this.logInfo(this.logLine("City-map construction timer OCR \u2192 next run at "
                        + next.format(DATETIME_FORMATTER)
                        + " (attempt " + attempt + "/" + CITY_MAP_TIMER_OCR_ATTEMPTS
                        + ", " + reason + ") — experimental fallback"));
                this.reschedule(next);
                return true;
            }
            this.logInfo(this.logLine(
                    "City-map construction timer OCR miss (attempt " + attempt + "/"
                            + CITY_MAP_TIMER_OCR_ATTEMPTS + ", " + reason + ")"));
            this.sleepTask(600L);
        }
        return false;
    }

    private Optional<Duration> readShortestQueueWait(boolean logRawMisses) {
        Duration shortest = null;
        for (AreaData area : new AreaData[]{QUEUE_AREA_1, QUEUE_AREA_2}) {
            Optional<Duration> candidate = this.readQueueDuration(area, logRawMisses);
            if (candidate.isEmpty() || shortest != null && candidate.get().compareTo(shortest) >= 0) {
                continue;
            }
            shortest = candidate.get();
        }
        return Optional.ofNullable(shortest);
    }

    private Optional<Duration> readQueueDuration(AreaData area, boolean logRawMisses) {
        OcrSettingsData[] presets = new OcrSettingsData[]{
                LeftMenuTextSettings.WHITE_SETTINGS,
                LeftMenuTextSettings.WHITE_NUMBERS,
                LeftMenuTextSettings.RED_SETTINGS,
                LeftMenuTextSettings.ORANGE_SETTINGS};
        for (OcrSettingsData preset : presets) {
            try {
                String text = this.emuManager.readText(
                        this.EMULATOR_NUMBER, area.topLeft(), area.bottomRight(), preset, true);
                if (text == null || text.isBlank()) {
                    continue;
                }
                String normalized = text.replaceAll("\\s+", "");
                if (!GameTimeUtils.isAcceptedFormat(normalized)) {
                    if (logRawMisses) {
                        this.logInfo(this.logLine(String.format(Locale.ROOT,
                                "Queue OCR rejected raw='%s' normalized='%s' area=%s",
                                text.trim(), normalized, area)));
                    }
                    continue;
                }
                return Optional.of(GameTimeUtils.parseDuration(normalized));
            } catch (Exception exception) {
                if (logRawMisses) {
                    this.logDebug(this.logLine("Queue OCR error on " + area + ": " + exception.getMessage()));
                }
            }
        }
        return Optional.empty();
    }

    private void dismissToHome() {
        this.pressBack();
        this.sleepTask(400L);
        this.pressBack();
        this.sleepTask(400L);
        this.navigationHelper.ensureCorrectScreenLocation(LaunchPoint.HOME);
    }

    /**
     * After a failed city up-arrow search, Back/ESC closes the selected-building
     * bubble (hides the arrow). If Home furnace is already visible, stay put;
     * otherwise only try Home↔World taps — never press Back.
     */
    private void recoverHomeWithoutBack() {
        this.logInfo(this.logLine("Recovering Home without Back/ESC after city up-arrow miss"));
        if (this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
            return;
        }
        ImageSearchResultData world = this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_WORLD, SearchConfigConstants.DEFAULT_SINGLE);
        if (world.isFound()) {
            this.tapInside(world);
            this.sleepTask(1500L);
        }
        if (this.templateSearchHelper.locatePattern(
                TemplatesEnum.GAME_HOME_FURNACE, SearchConfigConstants.DEFAULT_SINGLE).isFound()) {
            return;
        }
        this.logWarning(this.logLine("Home furnace not confirmed without Back — leaving screen as-is"));
    }

    private void notifyTelegram(String outcome) {
        try {
            String profileName = this.profile.getDisplayName();
            if (profileName == null || profileName.isBlank()) {
                profileName = "id " + this.profile.getId();
            }
            // Legacy Markdown: strip chars that break parse_mode rather than escaping mid-sentence.
            String safeName = profileName.replace("_", " ").replace("*", "").replace("`", "").replace("[", "");
            TelegramBotService.getInstance().notifyAllowedChat(
                    "✅ *" + outcome + "*\nProfile: " + safeName);
        } catch (Exception e) {
            this.logDebug(this.logLine("Telegram notify skipped: " + e.getMessage()));
        }
    }

    private String logLine(String note) {
        return "GrowthMissionBuildRoutine | " + note;
    }

    private enum FurnitureStepResult {
        NONE,
        ADVANCED,
        STARTED,
        STEEL_BLOCKED,
        FAILED
    }

    private enum OpenGrowthResult {
        READY_FOR_BUILD,
        ALL_COMPLETE,
        NO_MAIN_ACTION,
        FAILED
    }


    private record UpgradeOutcome(
            boolean startedConstruction,
            boolean steelBlocked,
            boolean furnitureProgress,
            boolean cityArrowMissed) {
        boolean avoidBackOnExit() {
            return cityArrowMissed && !startedConstruction && !furnitureProgress;
        }
    }

    static {
        GO_MAIN_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(82).withArea(MAIN_GO_AREA).build();
        GO_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(MAIN_GO_AREA).build();
        CLAIM_QUICK_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(250L).withThreshold(82).withArea(MAIN_GO_CLAIM_AREA).build();
        CLAIM_FALLBACK_QUICK_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(200L).withThreshold(78).withArea(MAIN_GO_CLAIM_AREA).build();
        HEADER_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(88).withArea(HEADER_UPGRADE_AREA).build();
        FURNITURE_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(88).withArea(FURNITURE_UPGRADE_AREA).build();
        FURNITURE_NEXT_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(300L).withThreshold(82).withArea(FURNITURE_NEXT_AREA).build();
        FURNITURE_NEXT_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(FURNITURE_NEXT_AREA).build();
        FURNITURE_PANEL_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(200L).withThreshold(88).withArea(FURNITURE_PANEL_AREA).build();
        CITY_UPGRADE_ARROW_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(4).withDelay(400L).withThreshold(70).withArea(CITY_UPGRADE_ARROW_AREA).build();
        CITY_UPGRADE_ARROW_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(CITY_UPGRADE_ARROW_AREA).build();
        BUILDING_PANEL_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(4).withDelay(400L).withThreshold(78).withArea(BUILDING_PANEL_UPGRADE_AREA).build();
        REPLENISH_BUTTON_RECHECK = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(350L).withThreshold(85).withArea(REPLENISH_ALL_AREA).build();
        REPLENISH_BUTTON_RELAXED = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(2).withDelay(300L).withThreshold(78).withArea(REPLENISH_ALL_AREA).build();
        REPLENISH_BUTTON_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(REPLENISH_ALL_AREA).build();
        BUILDING_CONFIRM_UPGRADE_SEARCH = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(5).withDelay(500L).withThreshold(85).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
        BUILDING_CONFIRM_UPGRADE_RELAXED = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(3).withDelay(400L).withThreshold(78).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
        BUILDING_CONFIRM_UPGRADE_POSTCONDITION = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(85).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
        BUILDING_CONFIRM_UPGRADE_SCORE_PROBE = TemplateSearchHelper.SearchConfig.builder().withMaxAttempts(1).withThreshold(0).withArea(BUILDING_CONFIRM_UPGRADE_AREA).build();
    }
}
