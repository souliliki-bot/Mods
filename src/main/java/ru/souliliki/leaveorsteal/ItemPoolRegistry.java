package ru.souliliki.leaveorsteal;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class ItemPoolRegistry {
    private static final ItemPoolRegistry INSTANCE = new ItemPoolRegistry();
    private final Path configPath = FabricLoader.getInstance().getConfigDir().resolve("leave-or-steal.properties");
    private final Properties config = new Properties();
    private final EnumMap<ChestClass, List<Identifier>> pools = new EnumMap<>(ChestClass.class);

    private ItemPoolRegistry() {
        for (ChestClass chestClass : ChestClass.values()) pools.put(chestClass, new ArrayList<>());
    }

    public static ItemPoolRegistry get() { return INSTANCE; }

    public synchronized void reload() throws IOException {
        if (!Files.exists(configPath)) writeDefaultConfig();
        try (Reader reader = Files.newBufferedReader(configPath)) {
            config.clear();
            config.load(reader);
        }
        boolean addedDefaultFilter = config.putIfAbsent("excludedPathKeywords",
            "spawn_egg,command_block,barrier,structure_block,jigsaw,debug_stick,knowledge_book") == null;
        if (addedDefaultFilter) {
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                config.store(writer, "Leave or Steal item pools. Chest classes: " +
                    String.join(", ", Arrays.stream(ChestClass.values()).map(Enum::name).toList()));
            }
        }
        rebuildPools();
    }

    public synchronized int count(ChestClass chestClass) { return pools.get(chestClass).size(); }

    public synchronized List<Identifier> getItems(ChestClass chestClass) {
        return List.copyOf(pools.get(chestClass));
    }

    public synchronized ChestClass getClassFor(Identifier id) {
        String override = config.getProperty("item." + id, "").trim();
        if (!override.isEmpty()) {
            try { return ChestClass.valueOf(override.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { return null; }
        }
        Item item = Registries.ITEM.get(id);
        if (item == null) return null;
        return classifyByDefault(id, item);
    }

    private void rebuildPools() {
        pools.values().forEach(List::clear);
        Set<String> enabled = csv(config.getProperty("enabledNamespaces", "*"));
        Set<String> excludedNamespaces = csv(config.getProperty("excludedNamespaces", ""));
        Set<String> excludedItems = csv(config.getProperty("excludedItems", ""));
        Set<String> excludedPathKeywords = csv(config.getProperty("excludedPathKeywords", ""));
        for (Identifier id : Registries.ITEM.getIds()) {
            if (!enabled.contains("*") && !enabled.contains(id.getNamespace())) continue;
            if (excludedNamespaces.contains(id.getNamespace()) || excludedItems.contains(id.toString()) ||
                matchesExcludedPath(id, excludedPathKeywords)) continue;
            ChestClass chestClass = getClassFor(id);
            if (chestClass != null) pools.get(chestClass).add(id);
        }
    }

    private boolean matchesExcludedPath(Identifier id, Set<String> excludedPathKeywords) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String keyword : excludedPathKeywords) {
            if (!keyword.isBlank() && path.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private ChestClass classifyByDefault(Identifier id, Item item) {
        return ItemClassifier.classify(id.getPath());
    }

    private Set<String> csv(String value) {
        Set<String> result = new HashSet<>();
        for (String part : value.split(",")) if (!part.isBlank()) result.add(part.trim());
        return result;
    }

    private void writeDefaultConfig() throws IOException {
        Files.createDirectories(configPath.getParent());
        config.setProperty("enabledNamespaces", "*");
        config.setProperty("excludedNamespaces", "");
        config.setProperty("excludedItems", "");
        config.setProperty("excludedPathKeywords",
            "spawn_egg,command_block,barrier,structure_block,jigsaw,debug_stick,knowledge_book");
        config.setProperty("# item.<namespace:item>=<CHEST_CLASS>", "Example: item.minecraft:diamond_sword=DIAMOND_GEAR");
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            config.store(writer, "Leave or Steal item pools. Chest classes: " +
                String.join(", ", Arrays.stream(ChestClass.values()).map(Enum::name).toList()));
        }
    }
}
