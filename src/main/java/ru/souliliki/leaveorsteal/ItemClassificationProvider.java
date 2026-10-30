package ru.souliliki.leaveorsteal;

import net.minecraft.util.Identifier;

/**
 * Entrypoint для стороннего мода, который хочет добавить правила классификации.
 *
 * В fabric.mod.json стороннего мода:
 * "entrypoints": {
 *   "leave_or_steal:item_classifiers": ["example.mod.ExampleItemClassifier"]
 * }
 */
@FunctionalInterface
public interface ItemClassificationProvider {
    void register(ItemClassificationRegistry registry);
}
