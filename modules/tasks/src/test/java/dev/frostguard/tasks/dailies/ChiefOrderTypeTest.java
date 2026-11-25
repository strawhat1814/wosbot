package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.TemplatesEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ChiefOrderTypeTest {

    @Test
    void keepsClassicAndFcCooldownHours() {
        assertEquals(8, ChiefOrderRoutine.ChiefOrderType.URGENT_MOBILIZATION.getCooldownHours());
        assertEquals(23, ChiefOrderRoutine.ChiefOrderType.RUSH_JOB.getCooldownHours());
        assertEquals(12, ChiefOrderRoutine.ChiefOrderType.PRODUCTIVITY_DAY.getCooldownHours());
        assertEquals(24, ChiefOrderRoutine.ChiefOrderType.FESTIVITIES.getCooldownHours());
        assertEquals(12, ChiefOrderRoutine.ChiefOrderType.CRUNCH_TIME.getCooldownHours());
        assertEquals(47, ChiefOrderRoutine.ChiefOrderType.DAWN_ALLIANCE_COMMENDATION.getCooldownHours());
    }

    @Test
    void mapsEachOrderToItsTemplateConstant() {
        assertSame(TemplatesEnum.CHIEF_ORDER_URGENT_MOBILISATION,
                ChiefOrderRoutine.ChiefOrderType.URGENT_MOBILIZATION.getTemplate());
        assertSame(TemplatesEnum.CHIEF_ORDER_RUSH_JOB,
                ChiefOrderRoutine.ChiefOrderType.RUSH_JOB.getTemplate());
        assertSame(TemplatesEnum.CHIEF_ORDER_PRODUCTIVITY_DAY,
                ChiefOrderRoutine.ChiefOrderType.PRODUCTIVITY_DAY.getTemplate());
        assertSame(TemplatesEnum.CHIEF_ORDER_FESTIVITIES,
                ChiefOrderRoutine.ChiefOrderType.FESTIVITIES.getTemplate());
        assertSame(TemplatesEnum.CHIEF_ORDER_CRUNCH_TIME,
                ChiefOrderRoutine.ChiefOrderType.CRUNCH_TIME.getTemplate());
        assertSame(TemplatesEnum.CHIEF_ORDER_DAWN_ALLIANCE_COMMENDATION,
                ChiefOrderRoutine.ChiefOrderType.DAWN_ALLIANCE_COMMENDATION.getTemplate());
    }
}
