package dev.frostguard.api.stats;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared counter → category mapping for the Statistics UI and Telegram reports.
 */
public final class StatisticsCategories {

    public static final List<String> CATEGORY_ORDER = List.of(
            "Combat",
            "Intel & Exploration",
            "Economy",
            "City & Growth",
            "Training & Research",
            "Utility",
            "Other");

    private static final Map<String, String> COUNTER_CATEGORIES = new LinkedHashMap<>();

    static {
        // Combat
        COUNTER_CATEGORIES.put("Arena Battles Won", "Combat");
        COUNTER_CATEGORIES.put("Arena Battles Lost", "Combat");
        COUNTER_CATEGORIES.put("Arena Gems Spent", "Combat");
        COUNTER_CATEGORIES.put("Arena Refreshes", "Combat");
        COUNTER_CATEGORIES.put("Exploration Fights Won", "Combat");
        COUNTER_CATEGORIES.put("Exploration Fights Lost", "Combat");
        COUNTER_CATEGORIES.put("Polar Terror Rallies Deployed", "Combat");
        COUNTER_CATEGORIES.put("Bear Trap Rallies Joined", "Combat");
        COUNTER_CATEGORIES.put("Bear Trap Own Rallies", "Combat");
        COUNTER_CATEGORIES.put("Manual Rally Joins", "Combat");

        // Intel & Exploration
        COUNTER_CATEGORIES.put("Intel Beast", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Intel Survivor Camps", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Intel Journeys", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Beast Attacks Sent", "Intel & Exploration");
        COUNTER_CATEGORIES.put("Pet Adventure Chests Claimed", "Intel & Exploration");

        // Economy
        COUNTER_CATEGORIES.put("Mystery Shop Purchases", "Economy");
        COUNTER_CATEGORIES.put("Mystery Shop Free Claims", "Economy");
        COUNTER_CATEGORIES.put("Daily Refreshes Used", "Economy");
        COUNTER_CATEGORIES.put("Alliance Shop Purchases", "Economy");
        COUNTER_CATEGORIES.put("Gather Marches Deployed", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant Free Resources Claimed", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant VIP Points Purchased", "Economy");
        COUNTER_CATEGORIES.put("Nomadic Merchant Daily Refresh Used", "Economy");
        COUNTER_CATEGORIES.put("Storehouse Chests Claimed", "Economy");
        COUNTER_CATEGORIES.put("Warm Welcome Claims", "Economy");
        COUNTER_CATEGORIES.put("Alliance Chests Claimed", "Economy");

        // City & Growth
        COUNTER_CATEGORIES.put("Growth Missions Claimed", "City & Growth");
        COUNTER_CATEGORIES.put("Growth Construction Started", "City & Growth");
        COUNTER_CATEGORIES.put("Growth Furniture Upgrades", "City & Growth");
        COUNTER_CATEGORIES.put("War Academy Shards Redeemed", "City & Growth");

        // Training & Research
        COUNTER_CATEGORIES.put("Training Batches Started", "Training & Research");
        COUNTER_CATEGORIES.put("Research Started", "Training & Research");

        // Utility
        COUNTER_CATEGORIES.put("Mail Rewards Claimed", "Utility");
        COUNTER_CATEGORIES.put("Daily Missions Claimed", "Utility");
    }

    private StatisticsCategories() {
    }

    public static String categoryFor(String counterName) {
        if (counterName == null) {
            return "Other";
        }
        return COUNTER_CATEGORIES.getOrDefault(counterName, "Other");
    }

    public static Map<String, String> allMappings() {
        return Map.copyOf(COUNTER_CATEGORIES);
    }
}
