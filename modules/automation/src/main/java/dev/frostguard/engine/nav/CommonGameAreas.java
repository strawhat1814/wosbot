package dev.frostguard.engine.nav;

import dev.frostguard.api.domain.AreaData;
import dev.frostguard.api.domain.PointData;

/**
 * Coordinate catalogue for interactive game regions and OCR zones.
 * All values target the standard 720 × 1280 viewport.
 */
public final class CommonGameAreas {

    // Event shortcuts sit in two bands: the inset city art and the right-hand
    // column. Sign-in/Deals push the column slot down; keep the top edge under
    // the HUD bar and the bottom above the pet/mail buttons.
    public static final AreaData FISHING_HOME_ENTRY = AreaData.of(475, 115, 720, 580);
    public static final AreaData FISHING_TOURNAMENT_TAB = AreaData.of(45, 105, 225, 175);
    public static final AreaData FISHING_ICE_CAST_BUTTON = AreaData.of(370, 1140, 670, 1240);
    public static final AreaData FISHING_FREE_BAIT = AreaData.of(497, 1095, 579, 1129);
    public static final AreaData FISHING_DEPTH = AreaData.of(20, 20, 140, 93);
    public static final AreaData FISHING_DEPTH_HUD = AreaData.of(0, 0, 175, 100);
    public static final AreaData FISHING_CAPACITY_HUD = AreaData.of(0, 108, 175, 157);
    public static final AreaData FISHING_PROTECTION_HUD = AreaData.of(0, 167, 175, 215);
    public static final AreaData FISHING_CURRENT_DEPTH = AreaData.of(20, 20, 150, 63);
    // Keep the lower/right HUD border outside the text region.
    public static final AreaData FISHING_MAXIMUM_DEPTH = AreaData.of(35, 64, 125, 91);
    public static final AreaData FISHING_LINE_LENGTH = AreaData.of(145, 870, 225, 905);
    public static final AreaData FISHING_HAUL_HEADING = AreaData.of(265, 193, 455, 264);
    public static final AreaData FISHING_HAUL_EXIT_LABEL = AreaData.of(170, 1090, 275, 1147);
    public static final AreaData FISHING_HAUL_EXIT_BUTTON = AreaData.of(100, 1090, 325, 1155);

    private CommonGameAreas() {}

    // ── micro-factories ──────────────────────────────────────────────

    private static AreaData region(int x1, int y1, int x2, int y2) {
        return new AreaData(new PointData(x1, y1), new PointData(x2, y2));
    }

    private static PointData point(int x, int y) {
        return new PointData(x, y);
    }

    // ── account / energy panel ───────────────────────────────────────

    public static final AreaData PROFILE_AVATAR        = region(24, 24, 61, 61);
    // White digits on the green pill directly under the chief avatar. The same pill is on
    // City and World. The box starts to the right of the bolt and stops above the up-arrow.
    // Measured 2026-10-20: "224" glyphs sit at about x=38..61, y=105..113; the right edge
    // leaves room for a fourth digit still inside the pill.
    public static final AreaData STAMINA_HUD_OCR_AREA  = region(34, 100, 90, 118);
    public static final AreaData STAMINA_BUTTON        = region(223, 1101, 244, 1123);
    public static final AreaData STAMINA_OCR_AREA      = region(324, 255, 477, 289);
    public static final AreaData SPENT_STAMINA_OCR_AREA = region(540, 1215, 590, 1245);
    // English "Tap anywhere to exit" hints are rendered in this lower strip on result overlays.
    public static final AreaData STARTUP_EXIT_HINT_OCR_AREA = region(0, 1160, 719, 1279);
    public static final AreaData STARTUP_EXIT_HINT_TAP_AREA = region(180, 1160, 540, 1279);

    /** Android ANR dialog body (…isn't responding / Close app / Wait) on 720×1280. */
    public static final AreaData ANR_DIALOG_OCR_AREA = region(60, 420, 660, 900);
    /** Prefer Wait (right action); never Close app — that kills the game mid-session. */
    public static final AreaData ANR_WAIT_TAP_AREA = region(400, 650, 640, 860);

    /** In-game "Quit Game?" body text when Back is pressed on City/World. */
    public static final AreaData QUIT_GAME_DIALOG_OCR_AREA = region(80, 480, 640, 780);
    /** Left action (Cancel) — never Confirm, which would exit the game. */
    public static final AreaData QUIT_GAME_CANCEL_TAP_AREA = region(90, 880, 350, 1040);

    // The cooldown stays in the top banner while Intel markers remain, then moves to the center
    // after every completed Intel reward has been claimed.
    public static final AreaData INTEL_COOLDOWN_WITH_MARKERS_OCR_AREA = region(378, 103, 508, 146);
    public static final AreaData INTEL_COOLDOWN_EMPTY_MAP_OCR_AREA    = region(378, 580, 530, 640);
    public static final AreaData INTEL_CLAIM_ALL_AREA                 = region(190, 1050, 530, 1200);

    // Furnace detail entry, distinct from the final building confirmation dialog.
    public static final AreaData FURNACE_PANEL_TITLE = region(0, 650, 245, 760);
    public static final AreaData FURNACE_PANEL_UPGRADE = region(475, 650, 715, 760);

    // ── side panel navigation ────────────────────────────────────────

    // The collapsed panel exposes only a thin handle at the left edge. A trigger tap is allowed only
    // after the caller has proved that no selected tab is visible, and the result is then verified.
    // Operator rectangle: top-left (1,485) to bottom-right (7,615). Entire area is valid
    // (corners and mid). Open uses one vertical half first; fallback uses the opposite half
    // so a World "Add March" overlap does not get hit twice.
    public static final AreaData LEFT_MENU_TRIGGER        = region(1, 485, 7, 615);
    /** Full operator rectangle retained for callers that still reference the fallback name. */
    public static final AreaData LEFT_MENU_TRIGGER_FALLBACK = region(1, 485, 7, 615);
    public static final AreaData LEFT_MENU_CITY_TAB       = region(9, 246, 146, 293);
    public static final AreaData LEFT_MENU_WILDERNESS_TAB = region(155, 246, 293, 293);
    public static final AreaData LEFT_MENU_DAILY_TAB      = region(302, 246, 438, 293);
    public static final AreaData LEFT_MENU_CLOSE          = region(450, 510, 482, 594);

    // Interior samples intentionally avoid rounded borders. The selected tab is substantially
    // brighter than both unselected tabs in every supplied post-update frame.
    private static final AreaData LEFT_MENU_CITY_TAB_SAMPLE       = region(20, 252, 135, 288);
    private static final AreaData LEFT_MENU_WILDERNESS_TAB_SAMPLE = region(165, 252, 286, 288);
    private static final AreaData LEFT_MENU_DAILY_TAB_SAMPLE      = region(310, 252, 430, 288);

    public static final AreaData SIDEBAR_CONTENT = region(10, 300, 440, 880);
    public static final AreaData SIDEBAR_ROW_ICON_COLUMN = region(15, 300, 80, 880);
    public static final PointData SIDEBAR_RESET_FROM = point(360, 350);
    public static final PointData SIDEBAR_RESET_TO = point(360, 820);
    public static final PointData SIDEBAR_SCROLL_FROM = point(360, 800);
    public static final PointData SIDEBAR_SCROLL_TO = point(360, 350);
    public static final PointData SIDEBAR_SCROLL_TOWARD_BOTTOM_FROM = point(360, 720);
    public static final PointData SIDEBAR_SCROLL_TOWARD_BOTTOM_TO = point(360, 600);

    public static AreaData sidebarTab(SidebarSection section) {
        return switch (section) {
            case CITY -> LEFT_MENU_CITY_TAB;
            case WILDERNESS -> LEFT_MENU_WILDERNESS_TAB;
            case DAILY -> LEFT_MENU_DAILY_TAB;
        };
    }

    public static AreaData sidebarTabSample(SidebarSection section) {
        return switch (section) {
            case CITY -> LEFT_MENU_CITY_TAB_SAMPLE;
            case WILDERNESS -> LEFT_MENU_WILDERNESS_TAB_SAMPLE;
            case DAILY -> LEFT_MENU_DAILY_TAB_SAMPLE;
        };
    }

    // ── shop footer navigation ──────────────────────────────────────

    public static final int SHOP_TAB_VISIBLE_COUNT = 3;
    private static final int SHOP_TAB_LEFT = 2;
    private static final int SHOP_TAB_TOP = 1208;
    private static final int SHOP_TAB_WIDTH = 189;
    private static final int SHOP_TAB_HEIGHT = 65;
    private static final int SHOP_TAB_PITCH = 198;
    private static final int SHOP_TAB_TAP_MARGIN = 10;
    private static final int SHOP_VIEWPORT_WIDTH = 720;

    public static final AreaData SHOP_LEFTMOST_TAB_OCR_AREA =
            region(SHOP_TAB_LEFT, SHOP_TAB_TOP,
                    SHOP_TAB_LEFT + SHOP_TAB_WIDTH, SHOP_TAB_TOP + SHOP_TAB_HEIGHT);
    public static final AreaData SHOP_RIGHTMOST_TAB_OCR_AREA =
            region(SHOP_VIEWPORT_WIDTH - SHOP_TAB_LEFT - SHOP_TAB_WIDTH, SHOP_TAB_TOP,
                    SHOP_VIEWPORT_WIDTH - SHOP_TAB_LEFT, SHOP_TAB_TOP + SHOP_TAB_HEIGHT);

    public static AreaData shopTabTapArea(int visibleSlot) {
        if (visibleSlot < 0 || visibleSlot >= SHOP_TAB_VISIBLE_COUNT) {
            throw new IllegalArgumentException("Shop tab slot must be between 0 and 2");
        }
        int left = SHOP_TAB_LEFT + visibleSlot * SHOP_TAB_PITCH;
        return insetShopTabArea(left);
    }

    public static AreaData shopTabTapAreaFromRight(int visibleSlotFromRight) {
        if (visibleSlotFromRight < 0 || visibleSlotFromRight >= SHOP_TAB_VISIBLE_COUNT) {
            throw new IllegalArgumentException("Shop tab slot from right must be between 0 and 2");
        }
        int rightmostLeft = SHOP_VIEWPORT_WIDTH - SHOP_TAB_LEFT - SHOP_TAB_WIDTH;
        int left = rightmostLeft - visibleSlotFromRight * SHOP_TAB_PITCH;
        return insetShopTabArea(left);
    }

    private static AreaData insetShopTabArea(int left) {
        return region(
                left + SHOP_TAB_TAP_MARGIN,
                SHOP_TAB_TOP + SHOP_TAB_TAP_MARGIN,
                left + SHOP_TAB_WIDTH - SHOP_TAB_TAP_MARGIN,
                SHOP_TAB_TOP + SHOP_TAB_HEIGHT - SHOP_TAB_TAP_MARGIN);
    }

    // ── march slot grid (top-left / bottom-right, slot 6→1) ─────────

    public static final PointData[] MARCH_SLOTS_TOP_LEFT = {
            point(189, 740), point(189, 667), point(189, 594),
            point(189, 521), point(189, 448), point(189, 375)
    };

    public static final PointData[] MARCH_SLOTS_BOTTOM_RIGHT = {
            point(258, 768), point(258, 695), point(258, 622),
            point(258, 549), point(258, 476), point(258, 403)
    };

    // ── wilderness March Queue rows (index 0 → queue 1, top) ─────────
    //
    // Each row carries an activity icon on the left and, below its title, a status line holding
    // either a word ("Idle"/"Unlock"/"Unavailable") or a countdown. Stationed troops show no status
    // line at all. The timer window matches the geometry GatherRoutine has been reading for a while.

    private static final int[] MARCH_QUEUE_ROW_Y = { 375, 448, 521, 594, 667, 740 };

    public static final AreaData[] MARCH_QUEUE_STATUS = marchQueueRows(150, 0, 300, 28);
    public static final AreaData[] MARCH_QUEUE_TIMER  = marchQueueRows(152, 3, 292, 22);
    public static final AreaData[] MARCH_QUEUE_TITLE  = marchQueueRows(70, -29, 340, -1);
    // padded past the 46x46 icon so template matching has room to slide
    public static final AreaData[] MARCH_QUEUE_ICON   = marchQueueRows(18, -27, 72, 27);

    private static AreaData[] marchQueueRows(int x1, int offsetY1, int x2, int offsetY2) {
        AreaData[] rows = new AreaData[MARCH_QUEUE_ROW_Y.length];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = region(x1, MARCH_QUEUE_ROW_Y[i] + offsetY1, x2, MARCH_QUEUE_ROW_Y[i] + offsetY2);
        }
        return rows;
    }

    // ── alliance war controls ────────────────────────────────────────

    public static final AreaData BOTTOM_MENU_ALLIANCE_BUTTON       = region(493, 1187, 561, 1240);
    // War list tabs (720x1280). Measured 2026-10-28; Solo↔Rally refresh uses both.
    public static final AreaData ALLIANCE_WAR_RALLY_TAB            = region(48, 99, 210, 146);
    public static final AreaData ALLIANCE_WAR_SOLO_TAB             = region(276, 103, 442, 147);
    public static final AreaData ALLIANCE_AUTOJOIN_MENU_BUTTON     = region(260, 1200, 450, 1240);
    public static final AreaData ALLIANCE_AUTOJOIN_DISABLE_BUTTON  = region(120, 1069, 249, 1122);

    // World-map free-march fraction (used/total) under the march queue HUD.
    // Measured 2026-10-28 for Bear B; do not reuse the older 203,200–246,226 box.
    public static final AreaData WORLD_FREE_MARCHES_OCR            = region(182, 217, 242, 250);

    // ── rally flag & deployment ──────────────────────────────────────

    // The whole flag tab strip. Slot centres drift a few pixels between profiles, so padlocks are
    // located across the strip and mapped to the nearest slot rather than searched slot by slot.
    public static final AreaData RALLY_FLAG_BAR         = region(0, 88, 700, 158);
    // Equalize sits in the bottom button bar, but its x shifts once a profile unlocks the Balance
    // button beside it, so it is matched inside the bar rather than tapped at a fixed point.
    public static final AreaData RALLY_BOTTOM_BUTTON_BAR      = region(0, 1130, 460, 1279);
    public static final AreaData RALLY_TROOP_TRAINING_AREA    = region(190, 900, 530, 1060);
    public static final AreaData RALLY_MARCH_QUEUE_FULL_AREA  = region(220, 300, 500, 380);
    public static final PointData RALLY_MARCH_QUEUE_FULL_CLOSE = point(640, 338);
    // Body of the "Other Troops are marching toward the same target" confirmation.
    public static final AreaData SAME_TARGET_DIALOG_AREA      = region(60, 555, 680, 650);

    // ── Hold-a-rally preparation time ────────────────────────────────
    //
    // The dialog keeps whatever preparation time was last picked in game and the bot never sets it,
    // so the ticked option is read per rally. Only a green tick marks the active option.

    public static final int[] RALLY_SET_TIME_MINUTES = { 3, 5, 10, 15 };
    public static final AreaData[] RALLY_SET_TIME_CHECKBOXES = {
            region(110, 592, 152, 634), region(375, 592, 417, 634),
            region(110, 670, 152, 712), region(375, 670, 417, 712)
    };

    // Mercenary level digits from 2026-10-17 captures (720x1280):
    // Pre-scout = pink "Lv. N" badge on the Events merc card (white digits).
    // Pre-attack = "Lv. N …" title on the World merc popup (blue digits on white).
    public static final AreaData MERCENARY_LEVEL_PRE_SCOUT_OCR_AREA = region(355, 870, 435, 906);
    /** Wide enough for two-digit World popup titles (Lv. 10–25); narrow crops were reading 25 as 2. */
    public static final AreaData MERCENARY_LEVEL_PRE_ATTACK_OCR_AREA = region(175, 840, 340, 890);

    // Polar Terror search panel: the level number sits in the pill right of the slider, not on the
    // slider bar itself.
    public static final AreaData POLAR_LEVEL_DISPLAY = region(565, 1030, 665, 1078);
    public static final AreaData POLAR_SEARCH_BUTTON = region(301, 1200, 412, 1229);
    // Operator crop: top-left (182,499) to bottom-right (537,573).
    public static final AreaData POLAR_SPECIAL_REWARDS_HEADER = region(182, 499, 537, 573);
    public static final PointData POLAR_REWARD_DETAILS_CLOSE = point(665, 195);

    // ── stamina "Obtain more" dialog ─────────────────────────────────
    //
    // Reachable both from a red deploy cost and straight from the profile stamina bar.

    public static final AreaData STAMINA_DIALOG_CURRENT     = region(340, 248, 470, 292);
    public static final AreaData STAMINA_DIALOG_ITEM_COUNT  = region(116, 535, 154, 570);
    public static final AreaData STAMINA_DIALOG_USE_BUTTON  = region(490, 480, 670, 565);
    public static final PointData STAMINA_DIALOG_CLOSE      = point(665, 135);
    // Includes the complete first digit. Starting at x=510 clipped its left half on the Intel
    // formation layout, turning "00:00:15" into the valid but false ten-hour value "10:00:15".
    public static final AreaData TRAVEL_TIME_OCR_AREA   = region(500, 1134, 622, 1162);

    // ── character identity ───────────────────────────────────────────

    // Profile sheet name/ID bands (2026-10-20 UI): name sits above the ID line.
    // Captured from live avatar sheet; old y~890–980 was reading the ID as the name.
    public static final AreaData CHARACTER_ID_OCR_AREA   = region(275, 901, 462, 932);
    public static final AreaData CHARACTER_NAME_OCR_AREA = region(281, 831, 610, 884);

    public static final AreaData PROFILE_SETTINGS_BUTTON_AREA =
            region(540, 1150, 720, 1250);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_BUTTON_AREA =
            region(30, 280, 340, 380);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_CHARACTER_LIST_AREA =
            region(60, 380, 660, 1100);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_PROMPT_BUTTON_AREA =
            region(50, 750, 670, 850);
    public static final AreaData PROFILE_SETTINGS_SWITCH_CHARACTER_CONFIRM_DIALOG_NAME_OCR_AREA =
            region(170, 650, 550, 700);

    // ── character name above furnace template (relative offsets) ─────

    public static final int CHARACTER_NAME_ABOVE_FURNACE_TOP_OFFSET_Y    = 60;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_BOTTOM_OFFSET_Y = 10;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_X_START          = 210;
    public static final int CHARACTER_NAME_ABOVE_FURNACE_X_END            = 500;
}
