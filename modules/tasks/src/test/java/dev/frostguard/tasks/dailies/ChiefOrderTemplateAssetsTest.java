package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.TemplatesEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bundled-asset checks for Chief Order icons ready for live testing.
 *
 * <p>Festivities stays wired in the panel but its PNG is still pending capture;
 * flip {@link #festivitiesTemplateStillPendingCapture()} when that asset lands.
 */
class ChiefOrderTemplateAssetsTest {

    @ParameterizedTest
    @EnumSource(
            value = ChiefOrderRoutine.ChiefOrderType.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "FESTIVITIES")
    void shipsBundledIconsForOrdersUnderTest(ChiefOrderRoutine.ChiefOrderType type) {
        assertTrue(type.getTemplate().existsAtPath(),
                type.name() + " should ship " + type.getTemplate().resourcePath());
    }

    @Test
    void shipsMenuAndEnactControls() {
        assertTrue(TemplatesEnum.CHIEF_ORDER_MENU_BUTTON.existsAtPath());
        assertTrue(TemplatesEnum.CHIEF_ORDER_ENACT_BUTTON.existsAtPath());
    }

    @Test
    void festivitiesTemplateStillPendingCapture() {
        assertFalse(TemplatesEnum.CHIEF_ORDER_FESTIVITIES.existsAtPath(),
                "Remove this assertion and add chiefOrderFestivities.png when the icon is captured");
    }

    @Test
    void doesNotKeepRetiredDoubleTimeConstant() {
        assertFalse(
                java.util.Arrays.stream(TemplatesEnum.values())
                        .map(Enum::name)
                        .anyMatch(name -> name.contains("DOUBLE_TIME")));
    }
}
