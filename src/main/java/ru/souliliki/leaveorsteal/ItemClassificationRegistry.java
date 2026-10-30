package ru.souliliki.leaveorsteal;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Публичная точка расширения классификации предметов для других Fabric-модов.
 * Регистрации провайдеров обновляются при перезагрузке конфигурации.
 */
public final class ItemClassificationRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger("leave_or_steal");
    private static final ItemClassificationRegistry INSTANCE = new ItemClassificationRegistry();

    private final Map<Identifier, ChestClass> itemRules = new LinkedHashMap<>();
    private final Map<String, List<Function<Identifier, ChestClass>>> namespaceRules = new LinkedHashMap<>();

    private ItemClassificationRegistry() {
    }

    public static ItemClassificationRegistry get() {
        return INSTANCE;
    }

    /**
     * Назначает класс конкретному предмету. Это правило имеет приоритет
     * над правилами namespace и встроенной классификацией.
     */
    public synchronized void registerItem(Identifier itemId, ChestClass chestClass) {
        if (itemId == null || chestClass == null) {
            throw new IllegalArgumentException("itemId and chestClass must not be null");
        }
        ChestClass previous = itemRules.put(itemId, chestClass);
        if (previous != null && previous != chestClass) {
            LOGGER.warn("Несколько интеграций назначили разные классы предмету {}: {} заменён на {}",
                itemId, previous, chestClass);
        }
    }

    /**
     * Регистрирует функцию классификации для namespace другого мода.
     * Верните null, если правило не подходит и нужно перейти к следующему.
     */
    public synchronized void registerNamespace(String namespace,
                                                Function<Identifier, ChestClass> classifier) {
        if (namespace == null || !namespace.matches("[a-z0-9_.-]+") || classifier == null) {
            throw new IllegalArgumentException("namespace must contain only lowercase letters, digits, '_', '.', '-' and classifier must not be null");
        }
        namespaceRules.computeIfAbsent(namespace, ignored -> new ArrayList<>()).add(classifier);
    }

    synchronized void reloadProviders() {
        itemRules.clear();
        namespaceRules.clear();

        try {
            for (ItemClassificationProvider provider :
                FabricLoader.getInstance().getEntrypoints(
                    "leave_or_steal:item_classifiers", ItemClassificationProvider.class)) {
                try {
                    provider.register(this);
                } catch (RuntimeException exception) {
                    LOGGER.error("Ошибка регистрации правил классификации от стороннего мода: {}",
                        provider.getClass().getName(), exception);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Не удалось загрузить entrypoints классификации сторонних модов", exception);
        }

        LOGGER.info("Загружено интеграционных правил классификации: предметов={}, namespace={}",
            itemRules.size(), namespaceRules.size());
    }

    synchronized ChestClass classify(Identifier itemId) {
        ChestClass exact = itemRules.get(itemId);
        if (exact != null) return exact;

        List<Function<Identifier, ChestClass>> rules =
            namespaceRules.get(itemId.getNamespace().toLowerCase(Locale.ROOT));
        if (rules == null) return null;

        return classifyWithNamespaceRules(rules, itemId);
    }

    static ChestClass classifyWithNamespaceRules(
        List<Function<Identifier, ChestClass>> rules, Identifier itemId
    ) {
        for (Function<Identifier, ChestClass> rule : rules) {
            try {
                ChestClass result = rule.apply(itemId);
                if (result != null) return result;
            } catch (RuntimeException exception) {
                LOGGER.error("Ошибка классификатора для предмета {}", itemId, exception);
            }
        }
        return null;
    }
}
