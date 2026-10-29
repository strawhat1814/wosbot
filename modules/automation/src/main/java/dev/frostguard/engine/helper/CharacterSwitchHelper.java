package dev.frostguard.engine.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.AccountDescriptor;
import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.api.domain.PointData;
import dev.frostguard.api.domain.OcrSettingsData;
import dev.frostguard.engine.emulator.EmulatorController;
import dev.frostguard.engine.input.TapInteractionService;
import dev.frostguard.engine.nav.CommonGameAreas;
import dev.frostguard.engine.service.BotOcrEngine;
import dev.frostguard.vision.logging.ProfileContextLogger;
import dev.frostguard.vision.ocr.ResilientOcrExecutor;

// Handles character verification and switching via the in-game profile menu.
public class CharacterSwitchHelper {

    public static final int CHARACTER_SWITCH_RELOAD_DELAY_MS = 5000;

    private final EmulatorController emu;
    private final String dev;
    private final TapInteractionService taps;
    private final ProfileContextLogger log;
    private final TemplateSearchHelper tpl;
    private final ResilientOcrExecutor<String> ocr;

    public CharacterSwitchHelper(EmulatorController emuManager, String emulatorNumber, AccountDescriptor profile) {
        this.emu = emuManager;
        this.dev = emulatorNumber;
        this.taps = TapInteractionService.forController(emuManager, emulatorNumber);
        this.log = new ProfileContextLogger(CharacterSwitchHelper.class, profile);
        this.tpl = new TemplateSearchHelper(emuManager, emulatorNumber, profile);
        this.ocr = new ResilientOcrExecutor<>(new BotOcrEngine(emuManager, emulatorNumber));
    }

    // --- Character verification ---

    /** Ready-path / handshake classify: garbage OCR must not tear down a ready pair. */
    public enum CharacterVerifyResult {
        MATCH,
        MISMATCH,
        INCONCLUSIVE
    }

    public boolean verifyCurrentCharacter(AccountDescriptor p) {
        return classifyCurrentCharacter(p) == CharacterVerifyResult.MATCH;
    }

    /**
     * Classifies live OCR against the configured identity.
     * Short junk / failed sheet reads are {@link CharacterVerifyResult#INCONCLUSIVE},
     * not mismatch — callers must not re-handshake on that alone.
     */
    public CharacterVerifyResult classifyCurrentCharacter(AccountDescriptor p) {
        String wantName = p.getCharacterName(), wantId = p.getCharacterId();
        if (blank(wantName) && blank(wantId)) {
            log.debug("No character config, skipping");
            return CharacterVerifyResult.MATCH;
        }
        log.info("Verifying character: name='" + wantName + "' id='" + wantId + "'");

        LiveCharacter live = readLiveCharacter();
        CharacterVerifyResult result = classifyLiveAgainstConfigured(wantName, wantId, live);
        if (live != null) {
            log.debug("Live char: name='" + live.name() + "' id='" + live.id() + "' result=" + result);
        } else {
            log.debug("Live char: (unreadable) result=" + result);
        }
        if (result == CharacterVerifyResult.MATCH) {
            log.info("Char verified OK");
        } else if (result == CharacterVerifyResult.MISMATCH) {
            log.warn("Char mismatch");
        } else {
            log.warn("Char verify inconclusive (OCR/sheet unreliable)");
        }
        return result;
    }

    /**
     * Returns true when the live OCR identity matches the configured name and/or id.
     * Only configured fields are checked; an unset id must not make verification succeed.
     */
    static boolean matchesConfiguredIdentity(
            String wantName, String wantId, String liveName, String liveId) {
        boolean nameConfigured = !blank(wantName);
        boolean idConfigured = !blank(wantId);
        if (!nameConfigured && !idConfigured) {
            return true;
        }
        boolean nameOk = nameConfigured && !blank(liveName) && nameMatchStatic(liveName, wantName);
        boolean idOk = idConfigured && !blank(liveId) && wantId.trim().equals(liveId.trim());
        if (nameConfigured && idConfigured) {
            return nameOk || idOk;
        }
        return idConfigured ? idOk : nameOk;
    }

    static CharacterVerifyResult classifyLiveAgainstConfigured(
            String wantName, String wantId, LiveCharacter live) {
        if (blank(wantName) && blank(wantId)) {
            return CharacterVerifyResult.MATCH;
        }
        if (live == null || (blank(live.name()) && blank(live.id()))) {
            return CharacterVerifyResult.INCONCLUSIVE;
        }
        if (matchesConfiguredIdentity(wantName, wantId, live.name(), live.id())) {
            return CharacterVerifyResult.MATCH;
        }
        if (!looksLikeSolidIdentity(live)) {
            return CharacterVerifyResult.INCONCLUSIVE;
        }
        return CharacterVerifyResult.MISMATCH;
    }

    /** True when OCR looks like a real profile identity rather than a partial/junk read. */
    static boolean looksLikeSolidIdentity(LiveCharacter live) {
        if (live == null) {
            return false;
        }
        if (!blank(live.id()) && live.id().trim().matches("\\d{6,}")) {
            return true;
        }
        if (!blank(live.name()) && live.name().trim().length() >= 4) {
            return true;
        }
        return false;
    }

    /**
     * Opens the profile sheet, OCRs the live character name/id, then closes it.
     * Returns null when the profile UI could not be read.
     */
    public LiveCharacter readLiveCharacter() {
        taps.tapInside(CommonGameAreas.PROFILE_AVATAR, 1, 500);
        sleep(400);
        String liveId = ocrRead(CommonGameAreas.CHARACTER_ID_OCR_AREA, idOcrCfg());
        String liveName = ocrRead(CommonGameAreas.CHARACTER_NAME_OCR_AREA, nameOcrCfg());
        emu.pressBack(dev);
        sleep(300);
        if (blank(liveId) && blank(liveName)) {
            return null;
        }
        return new LiveCharacter(liveName, liveId);
    }

    public record LiveCharacter(String name, String id) {
    }

    // --- Character switching ---

    public boolean switchToCharacter(AccountDescriptor p) {
        return switchToCharacter(p.getCharacterName(), null, true, false);
    }

    /**
     * Switches to {@code targetName}. When {@code beforeConfirm} is non-null, it runs after the
     * confirm dialog is validated and before Confirm/Connect is tapped (shared-login barrier).
     * When {@code closeEmulatorOnFailure} is false, roster miss does not close the emulator.
     * When {@code openProfileFirst} is true, opens the avatar sheet before Settings (needed when
     * Settings is not visible on the bare home/world screen).
     */
    public boolean switchToCharacter(String targetName, BooleanSupplier beforeConfirm,
            boolean closeEmulatorOnFailure) {
        return switchToCharacter(targetName, beforeConfirm, closeEmulatorOnFailure, false);
    }

    public boolean switchToCharacter(String targetName, BooleanSupplier beforeConfirm,
            boolean closeEmulatorOnFailure, boolean openProfileFirst) {
        if (blank(targetName)) { log.error("No character name configured"); return false; }
        log.info("Switching to '" + targetName + "'" + (openProfileFirst ? " (via profile sheet)" : ""));

        if (openProfileFirst) {
            if (!openProfileSettings()) {
                log.error("Settings button not found after opening profile");
                return false;
            }
        } else if (!tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_BUTTON_AREA)) {
            log.error("Settings button not found");
            return false;
        }
        if (!tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON, null)) {
            log.error("Switch Character button not found"); emu.pressBack(dev); return false;
        }
        sleep(2000);

        for (int pass = 0; pass < 3; pass++) {
            log.debug("Roster scan " + (pass + 1) + "/3");
            ImageSearchResultData hit = findInRoster(targetName);
            if (hit == null) { log.debug("Not visible, scrolling"); scrollDown(); continue; }

            // Check if already active via checkmark badge
            if (checkmarkAt(hit.getPoint())) {
                log.info("Already active — done");
                if (beforeConfirm != null && !beforeConfirm.getAsBoolean()) {
                    log.warn("Pre-confirm barrier failed while character already active");
                    emu.pressBack(dev);
                    return false;
                }
                emu.pressBack(dev); sleep(500); return true;
            }

            // Tap roster row, validate dialog, optionally wait, then confirm
            taps.tapInside(hit, 1, 500); sleep(500);
            if (!dialogNameMatches(targetName)) {
                log.warn("Confirm dialog name mismatch, canceling");
                cancelSwitch();
                continue;
            }
            if (beforeConfirm != null && !beforeConfirm.getAsBoolean()) {
                log.warn("Pre-confirm barrier failed, canceling switch");
                cancelSwitch();
                return false;
            }
            if (tapConfirmButton()) {
                sleep(CHARACTER_SWITCH_RELOAD_DELAY_MS);
                log.info("Switch OK");
                return true;
            }
            log.warn("Confirm tap failed, canceling");
            cancelSwitch();
        }
        log.error("Character not found after 3 passes");
        if (closeEmulatorOnFailure) {
            emu.closeEmulator(dev);
        }
        return false;
    }

    // --- Roster scanning (merged active + inactive search) ---

    private ImageSearchResultData findInRoster(String targetName) {
        TemplateSearchHelper.SearchConfig cfg = TemplateSearchHelper.SearchConfig.builder()
                .withArea(CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CHARACTER_LIST_AREA)
                .withMaxAttempts(3).withThreshold(90).withMaxResults(5).build();

        List<ImageSearchResultData> hits = new ArrayList<>();
        addAll(hits, tpl.locateAllPatterns(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_ACTIVE, cfg));
        addAll(hits, tpl.locateAllPatterns(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_FURNACE_LEVEL_INACTIVE, cfg));
        if (hits.isEmpty()) return null;
        log.debug(hits.size() + " furnace indicators found");

        for (ImageSearchResultData h : hits) {
            PointData fp = h.getPoint();
            String name = ocr.attemptRecognition(
                    new PointData(CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_X_START,
                            fp.getY() - CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_TOP_OFFSET_Y),
                    new PointData(CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_X_END,
                            fp.getY() - CommonGameAreas.CHARACTER_NAME_ABOVE_FURNACE_BOTTOM_OFFSET_Y),
                    2, 200, nameOcrCfg(), t -> t != null && !t.trim().isEmpty(), String::trim);
            if (name != null && nameMatch(name, targetName)) { log.info("Found: '" + name + "'"); return h; }
        }
        return null;
    }

    private boolean checkmarkAt(PointData fp) {
        int cx = fp.getX() + 264, cy = fp.getY() - 40;
        return tpl.locatePattern(TemplatesEnum.GAME_PROFILE_SETTINGS_CHARACTER_ACTIVE_CHECKMARK,
                TemplateSearchHelper.SearchConfig.builder()
                        .withArea(new AreaData(new PointData(cx, cy), new PointData(cx + 60, cy + 60)))
                        .withMaxAttempts(1).withThreshold(90).build()).isFound();
    }

    // --- Confirm / cancel dialog ---

    private boolean confirmSwitch(String expectedName) {
        if (!dialogNameMatches(expectedName)) {
            return false;
        }
        return tapConfirmButton();
    }

    private boolean dialogNameMatches(String expectedName) {
        String dialogName = ocr.attemptRecognition(
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA.topLeft(),
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA.bottomRight(),
                2, 200, nameOcrCfg(), t -> t != null && !t.trim().isEmpty(), String::trim);
        if (blank(dialogName) || !nameMatch(dialogName, expectedName)) {
            log.warn("Dialog name mismatch: got='" + dialogName + "' want='" + expectedName + "'");
            return false;
        }
        log.info("Dialog name OK: '" + dialogName + "'");
        return true;
    }

    private boolean tapConfirmButton() {
        return tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_BUTTON,
                CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA);
    }

    /** Opens the avatar sheet and taps Settings, with one Back+retry if Settings is missing. */
    private boolean openProfileSettings() {
        for (int attempt = 1; attempt <= 2; attempt++) {
            taps.tapInside(CommonGameAreas.PROFILE_AVATAR, 1, 500);
            sleep(500);
            if (tapTemplate(TemplatesEnum.GAME_PROFILE_SETTINGS_BUTTON,
                    CommonGameAreas.PROFILE_SETTINGS_BUTTON_AREA)) {
                return true;
            }
            log.warn("Settings not found on profile sheet (attempt " + attempt + "/2); backing out");
            emu.pressBack(dev);
            sleep(400);
        }
        return false;
    }

    private void cancelSwitch() {
        ImageSearchResultData btn = tpl.locatePattern(
                TemplatesEnum.GAME_PROFILE_SETTINGS_SWITCH_CHARACTER_CANCEL_BUTTON,
                TemplateSearchHelper.SearchConfig.builder()
                        .withArea(CommonGameAreas.PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA)
                        .withMaxAttempts(3).withDelay(500).withThreshold(80).build());
        if (btn == null || !btn.isFound()) emu.pressBack(dev);
        else taps.tapInside(btn, 1, 500);
        sleep(500);
    }

    // --- Shared utilities ---

    private boolean tapTemplate(TemplatesEnum t, AreaData area) {
        TemplateSearchHelper.SearchConfig.Builder b = TemplateSearchHelper.SearchConfig.builder()
                .withMaxAttempts(3).withDelay(500).withThreshold(80);
        if (area != null) b.withArea(area);
        ImageSearchResultData hit = tpl.locatePattern(t, b.build());
        if (hit == null || !hit.isFound()) return false;
        taps.tapInside(hit, 1, 500); return true;
    }

    private boolean nameMatch(String ocrText, String expected) {
        return nameMatchStatic(ocrText, expected);
    }

    private static boolean nameMatchStatic(String ocrText, String expected) {
        if (blank(ocrText) || blank(expected)) {
            return false;
        }
        String a = ocrText.trim().replaceAll("\\s+", "");
        String b = expected.trim().replaceAll("\\s+", "");
        return Pattern.compile(Pattern.quote(b), Pattern.CASE_INSENSITIVE).matcher(a).find();
    }

    private String ocrRead(AreaData area, OcrSettingsData cfg) {
        return ocr.attemptRecognition(area, 3, 300L, cfg, t -> t != null && !t.trim().isEmpty(), String::trim);
    }

    private static boolean blank(String s) {
        return s == null || s.isEmpty() || "null".equalsIgnoreCase(s);
    }

    private void scrollDown() {
        emu.swipeScreen(dev, new PointData(360, 800), new PointData(360, 400));
        sleep(1000);
    }

    private static void addAll(List<ImageSearchResultData> dst, List<ImageSearchResultData> src) {
        if (src != null) dst.addAll(src);
    }

    private OcrSettingsData idOcrCfg() {
        return OcrSettingsData.builder()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .setAllowedChars("0123456789")
                .setRemoveBackground(true).build();
    }

    private OcrSettingsData nameOcrCfg() {
        return OcrSettingsData.builder()
                .textLayout(OcrSettingsData.TextLayout.SINGLE_LINE)
                .setAllowedChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 ")
                .setRemoveBackground(true).build();
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
