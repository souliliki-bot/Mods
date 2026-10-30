package ru.souliliki.leaveorsteal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardDataParserTest {
    @Test
    void keepsValidPlayersAndSkipsMalformedEntries() {
        UUID validPlayer = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        JsonObject root = JsonParser.parseString("""
            {
              "123e4567-e89b-12d3-a456-426614174000": [{"id":"minecraft:stone"}],
              "not-a-uuid": [{"id":"minecraft:dirt"}],
              "123e4567-e89b-12d3-a456-426614174001": "not-an-array",
              "123e4567-e89b-12d3-a456-426614174002": []
            }
            """).getAsJsonObject();

        var parsed = RewardDataParser.parse(root);

        assertEquals(1, parsed.size());
        assertTrue(parsed.containsKey(validPlayer));
        assertEquals(1, parsed.get(validPlayer).size());
    }

    @Test
    void preservesAllElementsInAValidRewardArray() {
        UUID player = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        JsonObject root = JsonParser.parseString("""
            {
              "123e4567-e89b-12d3-a456-426614174000": [
                {"id":"minecraft:stone"},
                {"id":"minecraft:dirt"}
              ]
            }
            """).getAsJsonObject();

        List<?> rewards = RewardDataParser.parse(root).get(player);

        assertFalse(rewards.isEmpty());
        assertEquals(2, rewards.size());
    }
}
