package ru.souliliki.leaveorsteal;

import java.util.Locale;

/**
 * Классификация предметов по их registry path без обращения к Minecraft runtime.
 * Это позволяет покрывать правила обычными unit-тестами.
 */
final class ItemClassifier {
    private ItemClassifier() {
    }

    static ChestClass classify(String itemPath) {
        String path = itemPath.toLowerCase(Locale.ROOT);

        // Редкие и легендарные предметы проверяются до еды:
        // enchanted_golden_apple содержит apple, но не должен стать обычной едой.
        if (containsAny(path, "elytra", "totem", "nether_star", "dragon_egg", "beacon")) {
            return ChestClass.LEGENDARY;
        }
        if (containsAny(path, "enchanted_golden_apple", "trident", "heavy_core", "shulker")) {
            return ChestClass.RARE;
        }

        if (containsAny(path, "potion", "stew", "food", "apple", "bread", "meat",
            "soup", "carrot", "potato", "berry", "fish", "cookie", "cake",
            "pie", "cheese", "sausage")) {
            return ChestClass.POTIONS_FOOD;
        }

        if (isGearName(path)) {
            if (path.contains("netherite")) return ChestClass.NETHERITE_GEAR;
            if (path.contains("diamond")) return ChestClass.DIAMOND_GEAR;
            if (path.contains("iron")) return ChestClass.IRON_GEAR;
        }

        return ChestClass.COMMON;
    }

    private static boolean isGearName(String path) {
        return containsAny(path, "sword", "pickaxe", "axe", "shovel", "hoe",
            "helmet", "chestplate", "leggings", "boots", "shield", "bow",
            "crossbow", "dagger", "spear", "staff", "wand", "katana",
            "scythe", "hammer", "mace", "greatsword", "battleaxe");
    }

    private static boolean containsAny(String value, String... fragments) {
        for (String fragment : fragments) {
            if (value.contains(fragment)) return true;
        }
        return false;
    }
}
