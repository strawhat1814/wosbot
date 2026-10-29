package dev.frostguard.api.configs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ExpertSkillItemEnumTest {

    @Test
    void fromKeyResolvesPersistedConfigTokens() {
        assertEquals(ExpertSkillItemEnum.AGNES_SLOT_1, ExpertSkillItemEnum.fromKey("agnes_skill_1"));
        assertEquals(ExpertSkillItemEnum.AGNES_SLOT_1, ExpertSkillItemEnum.fromKey("AGNES_SKILL_1"));
        assertEquals(ExpertSkillItemEnum.CYRILLE_SLOT_3, ExpertSkillItemEnum.fromKey("cyrille_skill_3"));
        assertEquals(ExpertSkillItemEnum.FABIAN_SLOT_4, ExpertSkillItemEnum.fromKey("fabian_skill_4"));
    }

    @Test
    void fromKeyRejectsEnumConstantNamesAndUnknownTokens() {
        assertNull(ExpertSkillItemEnum.fromKey("AGNES_SLOT_1"));
        assertNull(ExpertSkillItemEnum.fromKey("agnes_slot_1"));
        assertNull(ExpertSkillItemEnum.fromKey("unknown_skill_1"));
        assertNull(ExpertSkillItemEnum.fromKey(null));
        assertNull(ExpertSkillItemEnum.fromKey(""));
    }
}
