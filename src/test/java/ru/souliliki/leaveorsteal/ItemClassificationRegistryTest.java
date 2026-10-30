package ru.souliliki.leaveorsteal;

import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemClassificationRegistryTest {
    private static final Identifier ITEM_ID = Identifier.of("example", "ruby_sword");

    @Test
    void triesNextNamespaceProviderWhenPreviousReturnsNull() {
        ChestClass result = ItemClassificationRegistry.classifyWithNamespaceRules(
            List.of(
                id -> null,
                id -> ChestClass.RARE,
                id -> ChestClass.LEGENDARY
            ),
            ITEM_ID
        );

        assertEquals(ChestClass.RARE, result);
    }

    @Test
    void returnsNullWhenNoNamespaceProviderRecognizesItem() {
        ChestClass result = ItemClassificationRegistry.classifyWithNamespaceRules(
            List.of(id -> null, id -> null),
            ITEM_ID
        );

        assertNull(result);
    }

    @Test
    void continuesAfterProviderThrows() {
        ChestClass result = ItemClassificationRegistry.classifyWithNamespaceRules(
            List.of(
                id -> { throw new IllegalStateException("broken integration"); },
                id -> ChestClass.DIAMOND_GEAR
            ),
            ITEM_ID
        );

        assertEquals(ChestClass.DIAMOND_GEAR, result);
    }
}
