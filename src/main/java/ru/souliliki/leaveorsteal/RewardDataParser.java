package ru.souliliki.leaveorsteal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Validates the per-player structure of the persisted reward file.
 * ItemStack decoding is deliberately left to Minecraft's registry-aware codec.
 */
final class RewardDataParser {
    private RewardDataParser() {
    }

    static Map<UUID, List<JsonElement>> parse(JsonObject root) {
        Map<UUID, List<JsonElement>> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            final UUID playerId;
            try {
                playerId = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException ignored) {
                continue;
            }

            if (!entry.getValue().isJsonArray()) {
                continue;
            }

            JsonArray array = entry.getValue().getAsJsonArray();
            List<JsonElement> elements = new ArrayList<>();
            for (JsonElement element : array) {
                elements.add(element);
            }
            if (!elements.isEmpty()) {
                result.put(playerId, elements);
            }
        }
        return result;
    }
}
