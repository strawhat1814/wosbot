package dev.frostguard.api.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StatisticsCategoriesTest {

    @Test
    void knownCountersMapToExpectedCategories() {
        assertEquals("Economy", StatisticsCategories.categoryFor("Gather Marches Deployed"));
        assertEquals("Combat", StatisticsCategories.categoryFor("Arena Battles Won"));
        assertEquals("Other", StatisticsCategories.categoryFor("Something Unknown"));
    }
}
