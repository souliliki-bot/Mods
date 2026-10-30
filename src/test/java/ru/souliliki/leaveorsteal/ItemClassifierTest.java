package ru.souliliki.leaveorsteal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemClassifierTest {
    @Test
    void classifiesEnchantedGoldenAppleAsRareBeforeGenericFood() {
        assertEquals(ChestClass.RARE, ItemClassifier.classify("enchanted_golden_apple"));
    }

    @Test
    void classifiesOrdinaryFood() {
        assertEquals(ChestClass.POTIONS_FOOD, ItemClassifier.classify("golden_apple"));
        assertEquals(ChestClass.POTIONS_FOOD, ItemClassifier.classify("modded_cheese"));
    }

    @Test
    void classifiesTieredGear() {
        assertEquals(ChestClass.IRON_GEAR, ItemClassifier.classify("iron_pickaxe"));
        assertEquals(ChestClass.DIAMOND_GEAR, ItemClassifier.classify("diamond_sword"));
        assertEquals(ChestClass.NETHERITE_GEAR, ItemClassifier.classify("netherite_greatsword"));
    }

    @Test
    void doesNotClassifyNetheriteMaterialsAsGear() {
        assertEquals(ChestClass.COMMON, ItemClassifier.classify("netherite_scrap"));
        assertEquals(ChestClass.COMMON, ItemClassifier.classify("ancient_debris"));
    }

    @Test
    void recognizesRareAndLegendaryItems() {
        assertEquals(ChestClass.RARE, ItemClassifier.classify("modded_trident"));
        assertEquals(ChestClass.LEGENDARY, ItemClassifier.classify("modded_totem"));
    }

    @Test
    void excludesDecorativeBlockItemPaths() {
        assertEquals(true, ItemClassifier.isDecorativeBlockPath("oak_stairs"));
        assertEquals(true, ItemClassifier.isDecorativeBlockPath("modded_glass_pane"));
        assertEquals(false, ItemClassifier.isDecorativeBlockPath("diamond_sword"));
        assertEquals(false, ItemClassifier.isDecorativeBlockPath("iron_ingot"));
    }

    @Test
    void keepsUnknownModdedItemsInCommon() {
        assertEquals(ChestClass.COMMON, ItemClassifier.classify("arcane_crystal"));
    }
}
