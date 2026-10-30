package ru.souliliki.leaveorsteal;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
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
    private final Map<String, Function<Identifier, ChestClass>> namespaceRules = new LinkedHashMap<>();

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
        itemRules.put(itemId, chestClass);
    }

    /**
     * Регистрирует функцию классификации для namespace другого мода.
     * Верните null, если правило не подходит и нужно перейти к следующему.
     */
    public synchronized void registerNamespace(String namespace,
                                                Function<Identifier, ChestClass> classifier) {
        if (namespace == null || namespace.isBlank() || classifier == null) {
            throw new IllegalArgumentException("namespace and classifier must not be blank/null");
        }
        namespaceRules.put(namespace.toLowerCase(Locale.ROOT), classifier);
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

        Function<Identifier, ChestClass> namespaceRule =
            namespaceRules.get(itemId.getNamespace().toLowerCase(Locale.ROOT));
        if (namespaceRule == null) return null;

        try {
            return namespaceRule.apply(itemId);
        } catch (RuntimeException exception) {
            LOGGER.error("Ошибка классификатора для предмета {}", itemId, exception);
            return null;
        }
    }
}
