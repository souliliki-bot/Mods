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
        boolean addedDefaults = false;
        addedDefaults |= config.putIfAbsent("excludedPathKeywords",
            "spawn_egg,command_block,barrier,structure_block,jigsaw,debug_stick,knowledge_book") == null;
        addedDefaults |= config.putIfAbsent("weight.COMMON", "60") == null;
        addedDefaults |= config.putIfAbsent("weight.IRON_GEAR", "20") == null;
        addedDefaults |= config.putIfAbsent("weight.DIAMOND_GEAR", "8") == null;
        addedDefaults |= config.putIfAbsent("weight.NETHERITE_GEAR", "2") == null;
        addedDefaults |= config.putIfAbsent("weight.POTIONS_FOOD", "5") == null;
        addedDefaults |= config.putIfAbsent("weight.RARE", "4") == null;
        addedDefaults |= config.putIfAbsent("weight.LEGENDARY", "1") == null;
        addedDefaults |= config.putIfAbsent("enabledClasses",
            String.join(",", Arrays.stream(ChestClass.values()).map(Enum::name).toList())) == null;
        addedDefaults |= config.putIfAbsent("# namespace.<modid>=<CHEST_CLASS>",
            "Example: namespace.magicmod=RARE") == null;
        addedDefaults |= config.putIfAbsent("# path.<CHEST_CLASS>",
            "Example: path.LEGENDARY=ruby,ancient,artifact") == null;
        if (addedDefaults) {
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                config.store(writer, "Leave or Steal item pools. Chest classes: " +
                    String.join(", ", Arrays.stream(ChestClass.values()).map(Enum::name).toList()));
            }
        }
        ItemClassificationRegistry.get().reloadProviders();
        rebuildPools();
    }

    public synchronized int count(ChestClass chestClass) { return pools.get(chestClass).size(); }

    public synchronized boolean isClassEnabled(ChestClass chestClass) {
        return csv(config.getProperty("enabledClasses",
            String.join(",", Arrays.stream(ChestClass.values()).map(Enum::name).toList())))
            .contains(chestClass.name());
    }

    public synchronized void toggleClass(ChestClass chestClass) throws IOException {
        Set<String> enabled = csv(config.getProperty("enabledClasses",
            String.join(",", Arrays.stream(ChestClass.values()).map(Enum::name).toList())));
        if (!enabled.remove(chestClass.name())) enabled.add(chestClass.name());
        config.setProperty("enabledClasses", String.join(",", enabled));
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            config.store(writer, "Leave or Steal item pools. Chest classes: " +
                String.join(", ", Arrays.stream(ChestClass.values()).map(Enum::name).toList()));
        }
        rebuildPools();
    }

    public synchronized List<Identifier> getItems(ChestClass chestClass) {
        return List.copyOf(pools.get(chestClass));
    }

    public synchronized void setClassWeight(ChestClass chestClass, int weight) throws IOException {
        config.setProperty("weight." + chestClass.name(), Integer.toString(Math.max(0, weight)));
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            config.store(writer, "Leave or Steal item pools. Chest classes: " +
                String.join(", ", Arrays.stream(ChestClass.values()).map(Enum::name).toList()));
        }
    }

    public synchronized int getClassWeight(ChestClass chestClass) {
        int fallback = defaultWeight(chestClass);
        try {
            return Math.max(0, Integer.parseInt(config.getProperty("weight." + chestClass.name(),
                Integer.toString(fallback)).trim()));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public synchronized Identifier getRandomItem(Random random) {
        long totalWeight = 0L;
        for (ChestClass chestClass : ChestClass.values()) {
            if (isClassEnabled(chestClass) && !pools.get(chestClass).isEmpty()) {
                totalWeight += getClassWeight(chestClass);
            }
        }
        if (totalWeight <= 0L) return null;

        long roll = (long) (random.nextDouble() * totalWeight);
        for (ChestClass chestClass : ChestClass.values()) {
            List<Identifier> items = pools.get(chestClass);
            int weight = isClassEnabled(chestClass) && !items.isEmpty() ? getClassWeight(chestClass) : 0;
            if (weight <= 0) continue;
            if (roll < weight) return items.get(random.nextInt(items.size()));
            roll -= weight;
        }
        return null;
    }

    private int defaultWeight(ChestClass chestClass) {
        return switch (chestClass) {
            case COMMON -> 60;
            case IRON_GEAR -> 20;
            case DIAMOND_GEAR -> 8;
            case NETHERITE_GEAR -> 2;
            case POTIONS_FOOD -> 5;
            case RARE -> 4;
            case LEGENDARY -> 1;
        };
    }

    public synchronized ChestClass getClassFor(Identifier id) {
        String override = config.getProperty("item." + id, "").trim();
        if (!override.isEmpty()) {
            ChestClass manualClass = parseClass(override);
            if (manualClass != null) return manualClass;
        }
        Item item = Registries.ITEM.get(id);
        if (item == null) return null;

        ChestClass integrationClass = ItemClassificationRegistry.get().classify(id);
        if (integrationClass != null) return integrationClass;

        String namespaceClass = config.getProperty("namespace." + id.getNamespace(), "").trim();
        ChestClass configuredNamespaceClass = parseClass(namespaceClass);
        if (configuredNamespaceClass != null) return configuredNamespaceClass;

        ChestClass configuredPathClass = classifyByConfiguredPath(id);
        if (configuredPathClass != null) return configuredPathClass;
        return classifyByDefault(id);
    }

    public synchronized String describeClassification(Identifier id) {
        if (id == null || !Registries.ITEM.containsId(id)) {
            return "Предмет не найден в реестре сервера.";
        }
        String override = config.getProperty("item." + id, "").trim();
        if (!override.isEmpty()) {
            ChestClass manual = parseClass(override);
            if (manual != null) return formatClassification(id, manual, "ручное правило item." + id);
        }
        ChestClass integration = ItemClassificationRegistry.get().classify(id);
        if (integration != null) return formatClassification(id, integration, "интеграция стороннего мода");
        ChestClass namespace = parseClass(config.getProperty("namespace." + id.getNamespace(), ""));
        if (namespace != null) return formatClassification(id, namespace, "правило namespace." + id.getNamespace());
        ChestClass pathClass = classifyByConfiguredPath(id);
        if (pathClass != null) return formatClassification(id, pathClass, "правило path.<КЛАСС>");
        return formatClassification(id, classifyByDefault(id), "встроенная классификация по ID");
    }

    private String formatClassification(Identifier id, ChestClass chestClass, String source) {
        StringBuilder result = new StringBuilder("Класс: ")
            .append(chestClass.name()).append(" — ").append(chestClass.displayName())
            .append("\\nИсточник: ").append(source);
        String exclusion = getPoolExclusionReason(chestClass, id);
        result.append("\\nКласс включён: ").append(isClassEnabled(chestClass));
        if (exclusion == null) {
            result.append("\\nВ пуле: ").append(pools.get(chestClass).contains(id) ? "да" : "нет");
        } else {
            result.append("\\nВ пуле: нет (").append(exclusion).append(")");
        }
        return result.toString();
    }

    private String getPoolExclusionReason(ChestClass chestClass, Identifier id) {
        Set<String> enabledNamespaces = csv(config.getProperty("enabledNamespaces", "*"));
        if (!enabledNamespaces.contains("*") && !enabledNamespaces.contains(id.getNamespace()))
            return "namespace отключён в enabledNamespaces";
        if (csv(config.getProperty("excludedNamespaces", "")).contains(id.getNamespace()))
            return "namespace указан в excludedNamespaces";
        if (csv(config.getProperty("excludedItems", "")).contains(id.toString()))
            return "ID указан в excludedItems";
        if (matchesExcludedPath(id, csv(config.getProperty("excludedPathKeywords", ""))))
            return "ID совпал с excludedPathKeywords";
        if (!isClassEnabled(chestClass)) return "класс отключён";
        return null;
    }

    private void rebuildPools() {
        pools.values().forEach(List::clear);
        Set<String> enabled = csv(config.getProperty("enabledNamespaces", "*"));
        Set<String> excludedNamespaces = csv(config.getProperty("excludedNamespaces", ""));
        Set<String> excludedItems = csv(config.getProperty("excludedItems", ""));
        Set<String> excludedPathKeywords = csv(config.getProperty("excludedPathKeywords", ""));
        Set<String> enabledClasses = csv(config.getProperty("enabledClasses",
            String.join(",", Arrays.stream(ChestClass.values()).map(Enum::name).toList())));
        for (Identifier id : Registries.ITEM.getIds()) {
            if (!enabled.contains("*") && !enabled.contains(id.getNamespace())) continue;
            if (excludedNamespaces.contains(id.getNamespace()) || excludedItems.contains(id.toString()) ||
                matchesExcludedPath(id, excludedPathKeywords)) continue;
            ChestClass chestClass = getClassFor(id);
            if (chestClass != null && enabledClasses.contains(chestClass.name())) pools.get(chestClass).add(id);
        }
    }

    private boolean matchesExcludedPath(Identifier id, Set<String> excludedPathKeywords) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (String keyword : excludedPathKeywords) {
            if (!keyword.isBlank() && path.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private ChestClass classifyByConfiguredPath(Identifier id) {
        String path = id.getPath().toLowerCase(Locale.ROOT);
        for (ChestClass chestClass : ChestClass.values()) {
            for (String fragment : csv(config.getProperty("path." + chestClass.name(), ""))) {
                if (!fragment.isBlank() && path.contains(fragment.toLowerCase(Locale.ROOT))) {
                    return chestClass;
                }
            }
        }
        return null;
    }

    private ChestClass parseClass(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return ChestClass.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private ChestClass classifyByDefault(Identifier id) {
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
        config.setProperty("enabledClasses", String.join(",", Arrays.stream(ChestClass.values()).map(Enum::name).toList()));
        config.setProperty("# namespace.<modid>=<CHEST_CLASS>", "Example: namespace.magicmod=RARE");
        config.setProperty("# path.<CHEST_CLASS>", "Example: path.LEGENDARY=ruby,ancient,artifact");
        config.setProperty("excludedPathKeywords",
            "spawn_egg,command_block,barrier,structure_block,jigsaw,debug_stick,knowledge_book");
        config.setProperty("# item.<namespace:item>=<CHEST_CLASS>", "Example: item.minecraft:diamond_sword=DIAMOND_GEAR");
        try (Writer writer = Files.newBufferedWriter(configPath)) {
            config.store(writer, "Leave or Steal item pools. Chest classes: " +
                String.join(", ", Arrays.stream(ChestClass.values()).map(Enum::name).toList()));
        }
    }
}
