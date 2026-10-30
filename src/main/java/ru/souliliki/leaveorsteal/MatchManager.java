package ru.souliliki.leaveorsteal;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.MutableText;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.text.ClickEvent;
import net.minecraft.util.Identifier;
import net.minecraft.registry.RegistryOps;
import net.minecraft.nbt.NbtOps;
import net.minecraft.util.WorldSavePath;
import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.io.IOException;

import java.util.*;

public final class MatchManager {
    public enum Phase { CLAIM, DECISION, DUEL, FINISHED }
    private static final MatchManager INSTANCE = new MatchManager();
    private final Deque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    private final Map<UUID, List<ItemStack>> pendingRewards = new HashMap<>();
    private final Random random = new Random();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private Path rewardsFile;
    private Path arenaFile;
    private ArenaPoint arenaFirst;
    private ArenaPoint arenaSecond;
    private Path settingsFile;
    private boolean arenaTeleportEnabled = true;
    private boolean restoreHealthEnabled = true;
    private boolean restoreHungerEnabled = true;

    public static MatchManager get() { return INSTANCE; }

    public synchronized void loadSettings(MinecraftServer server) {
        settingsFile = server.getSavePath(WorldSavePath.ROOT).resolve("data/leave-or-steal-settings.json");
        if (!Files.exists(settingsFile)) return;
        try (var reader = Files.newBufferedReader(settingsFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            arenaTeleportEnabled = !root.has("arenaTeleport") || root.get("arenaTeleport").getAsBoolean();
            restoreHealthEnabled = !root.has("restoreHealth") || root.get("restoreHealth").getAsBoolean();
            restoreHungerEnabled = !root.has("restoreHunger") || root.get("restoreHunger").getAsBoolean();
        } catch (Exception exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось загрузить настройки мода", exception);
        }
    }

    private synchronized void saveSettings() {
        if (settingsFile == null) return;
        JsonObject root = new JsonObject();
        root.addProperty("arenaTeleport", arenaTeleportEnabled);
        root.addProperty("restoreHealth", restoreHealthEnabled);
        root.addProperty("restoreHunger", restoreHungerEnabled);
        try {
            Files.createDirectories(settingsFile.getParent());
            try (var writer = Files.newBufferedWriter(settingsFile)) { GSON.toJson(root, writer); }
        } catch (IOException exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось сохранить настройки мода", exception);
        }
    }

    public synchronized void showSettings(ServerPlayerEntity player) {
        List<Integer> classWeights = Arrays.stream(ChestClass.values())
            .map(chestClass -> ItemPoolRegistry.get().getClassWeight(chestClass)).toList();
        ServerPlayNetworking.send(player, new SettingsSyncPayload(
            arenaTeleportEnabled, restoreHealthEnabled, restoreHungerEnabled, classWeights));
        player.sendMessage(Text.literal("§6§lОбман и доверие — настройки"), false);
        sendSetting(player, "Телепортация на арену", "arena", arenaTeleportEnabled);
        sendSetting(player, "Восстанавливать здоровье", "health", restoreHealthEnabled);
        sendSetting(player, "Восстанавливать голод", "hunger", restoreHungerEnabled);
        player.sendMessage(Text.literal("§6§lКлассы предметов в матчах"), false);
        for (ChestClass chestClass : ChestClass.values()) {
            sendClassSetting(player, chestClass);
        }
        player.sendMessage(Text.literal("§7Нажми на [ВКЛ]/[ВЫКЛ], чтобы изменить параметр."), false);
    }

    private void sendSetting(ServerPlayerEntity player, String label, String key, boolean enabled) {
        MutableText line = Text.literal("§f" + label + ": ");
        Text button = Text.literal(enabled ? "[ВКЛ]" : "[ВЫКЛ]").styled(style ->
            style.withColor(enabled ? 0x55FF55 : 0xFF5555)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/leaveorsteal setting " + key)));
        player.sendMessage(line.append(button), false);
    }

    private void sendClassSetting(ServerPlayerEntity player, ChestClass chestClass) {
        boolean enabled = ItemPoolRegistry.get().isClassEnabled(chestClass);
        int weight = ItemPoolRegistry.get().getClassWeight(chestClass);
        String className = chestClass.name().toLowerCase(Locale.ROOT);
        MutableText line = Text.literal("§f" + chestClass.displayName() + ": ");
        Text button = Text.literal(enabled ? "[ВКЛ]" : "[ВЫКЛ]").styled(style ->
            style.withColor(enabled ? 0x55FF55 : 0xFF5555)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                    "/leaveorsteal class " + className)));
        Text decrease = Text.literal(" §c[-]").styled(style ->
            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/leaveorsteal weight " + className + " " + Math.max(0, weight - 1))));
        Text increase = Text.literal(" §a[+]").styled(style ->
            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/leaveorsteal weight " + className + " " + Math.min(100000, weight + 1))));
        line.append(button).append(Text.literal(" §7Вес: §f" + weight)).append(decrease).append(increase);
        player.sendMessage(line, false);
    }

    public synchronized void setClassWeight(ServerPlayerEntity player, String className, int weight) {
        ChestClass chestClass;
        try {
            chestClass = ChestClass.valueOf(className.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Text.literal("§cНеизвестный класс предметов."), false);
            return;
        }
        try {
            ItemPoolRegistry.get().setClassWeight(chestClass, weight);
            player.sendMessage(Text.literal("§aВес класса " + chestClass.name() + " установлен: " + weight), false);
        } catch (IOException exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось сохранить вес класса предметов", exception);
            player.sendMessage(Text.literal("§cНе удалось сохранить вес класса."), false);
        }
        showSettings(player);
    }

    public synchronized void toggleClass(ServerPlayerEntity player, String className) {
        ChestClass chestClass;
        try {
            chestClass = ChestClass.valueOf(className.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            player.sendMessage(Text.literal("§cНеизвестный класс предметов."), false);
            return;
        }
        try {
            ItemPoolRegistry.get().toggleClass(chestClass);
            player.sendMessage(Text.literal("§aНастройка класса обновлена. Предметов в пуле: " +
                ItemPoolRegistry.get().count(chestClass)), false);
        } catch (IOException exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось сохранить настройки классов предметов", exception);
            player.sendMessage(Text.literal("§cНе удалось сохранить конфигурацию предметов."), false);
        }
        showSettings(player);
    }

    public synchronized void toggleSetting(ServerPlayerEntity player, String key) {
        switch (key) {
            case "arena" -> arenaTeleportEnabled = !arenaTeleportEnabled;
            case "health" -> restoreHealthEnabled = !restoreHealthEnabled;
            case "hunger" -> restoreHungerEnabled = !restoreHungerEnabled;
            default -> { player.sendMessage(Text.literal("§cНеизвестная настройка."), false); return; }
        }
        saveSettings();
        showSettings(player);
    }
    public synchronized void loadArena(MinecraftServer server) {
        arenaFile = server.getSavePath(WorldSavePath.ROOT).resolve("data/leave-or-steal-arena.json");
        if (!Files.exists(arenaFile)) return;
        try (var reader = Files.newBufferedReader(arenaFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            arenaFirst = readArenaPoint(root, "first");
            arenaSecond = readArenaPoint(root, "second");
        } catch (Exception exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось загрузить точки PvP-арены", exception);
        }
    }

    private ArenaPoint readArenaPoint(JsonObject root, String key) {
        if (!root.has(key) || !root.get(key).isJsonObject()) return null;
        JsonObject point = root.getAsJsonObject(key);
        return new ArenaPoint(point.get("dimension").getAsString(), point.get("x").getAsDouble(),
            point.get("y").getAsDouble(), point.get("z").getAsDouble(),
            point.get("yaw").getAsFloat(), point.get("pitch").getAsFloat());
    }

    private synchronized void saveArena() {
        if (arenaFile == null) return;
        JsonObject root = new JsonObject();
        if (arenaFirst != null) root.add("first", arenaFirst.toJson());
        if (arenaSecond != null) root.add("second", arenaSecond.toJson());
        try {
            Files.createDirectories(arenaFile.getParent());
            try (var writer = Files.newBufferedWriter(arenaFile)) { GSON.toJson(root, writer); }
        } catch (IOException exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось сохранить точки PvP-арены", exception);
        }
    }

    public synchronized void setArenaPoint(ServerPlayerEntity player, boolean first) {
        ArenaPoint point = new ArenaPoint(player.getWorld().getRegistryKey().getValue().toString(),
            player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
        if (first) arenaFirst = point; else arenaSecond = point;
        arenaFile = player.getServer().getSavePath(WorldSavePath.ROOT).resolve("data/leave-or-steal-arena.json");
        saveArena();
        player.sendMessage(Text.literal(first ? "§aТочка арены для первого игрока сохранена." :
            "§aТочка арены для второго игрока сохранена."), false);
    }
    public synchronized void loadRewards(MinecraftServer server) {
        pendingRewards.clear();
        rewardsFile = server.getSavePath(WorldSavePath.ROOT).resolve("data/leave-or-steal-rewards.json");
        if (!Files.exists(rewardsFile)) return;
        try (var reader = Files.newBufferedReader(rewardsFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            var ops = RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager());
            for (var entry : RewardDataParser.parse(root).entrySet()) {
                List<ItemStack> stacks = new ArrayList<>();
                for (JsonElement element : entry.getValue()) {
                    var decoded = ItemStack.CODEC.parse(ops, element);
                    decoded.resultOrPartial(error ->
                        LeaveOrStealMod.LOGGER.warn("Не удалось прочитать одну из наград игрока {}: {}", entry.getKey(), error)
                    ).ifPresent(stack -> {
                        if (!stack.isEmpty()) stacks.add(stack);
                    });
                }
                if (!stacks.isEmpty()) pendingRewards.put(entry.getKey(), stacks);
            }
        } catch (Exception exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось загрузить отложенные награды", exception);
        }
    }

    public synchronized void saveOnShutdown(MinecraftServer server) {
        Set<Match> activeMatches = new HashSet<>(matches.values());
        for (Match match : activeMatches) {
            if (match.roundItem != null && !match.roundItem.isEmpty()) {
                match.items.get(match.owner).add(match.roundItem.copy());
                match.roundItem = ItemStack.EMPTY;
            }
            for (UUID id : List.of(match.first, match.second)) {
                List<ItemStack> pending = pendingRewards.computeIfAbsent(id, ignored -> new ArrayList<>());
                for (ItemStack stack : match.items.get(id)) pending.add(stack.copy());
                match.items.get(id).clear();
            }
            match.phase = Phase.FINISHED;
        }
        matches.clear();
        queue.clear();
        saveRewards(server);
    }

    public synchronized void onJoin(ServerPlayerEntity player) {
        deliverPendingRewards(player);
    }

    private synchronized void saveRewards(MinecraftServer server) {
        if (rewardsFile == null) rewardsFile = server.getSavePath(WorldSavePath.ROOT).resolve("data/leave-or-steal-rewards.json");
        JsonObject root = new JsonObject();
        var ops = RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager());
        try {
            for (var entry : pendingRewards.entrySet()) {
                JsonArray array = new JsonArray();
                for (ItemStack stack : entry.getValue()) {
                    if (stack == null || stack.isEmpty()) continue;

                    var encoded = ItemStack.CODEC.encodeStart(ops, stack).result();
                    if (encoded.isEmpty()) {
                        throw new IOException("Не удалось сериализовать награду " +
                            stack.getItem() + " игрока " + entry.getKey() +
                            ". Старый файл наград оставлен без изменений.");
                    }
                    array.add(encoded.get());
                }
                if (!array.isEmpty()) root.add(entry.getKey().toString(), array);
            }

            Files.createDirectories(rewardsFile.getParent());
            Path temp = rewardsFile.resolveSibling(rewardsFile.getFileName() + ".tmp");
            try (var writer = Files.newBufferedWriter(temp)) { GSON.toJson(root, writer); }
            try { Files.move(temp, rewardsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, rewardsFile, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось сохранить отложенные награды", exception);
        }
    }

    public synchronized void joinQueue(ServerPlayerEntity player) {
        deliverPendingRewards(player);
        if (matches.containsKey(player.getUuid()) || queue.contains(player.getUuid())) {
            player.sendMessage(Text.literal("§eТы уже в очереди или матче."), false);
            return;
        }
        queue.addLast(player.getUuid());
        player.sendMessage(Text.literal("§aТы добавлен в очередь. Ожидаем второго игрока."), false);
        pairPlayers(player.getServer());
    }

    private void pairPlayers(MinecraftServer server) {
        while (queue.size() >= 2) {
            UUID a = queue.removeFirst();
            UUID b = queue.removeFirst();
            ServerPlayerEntity first = server.getPlayerManager().getPlayer(a);
            ServerPlayerEntity second = server.getPlayerManager().getPlayer(b);
            if (first == null || second == null) {
                if (first != null) queue.addFirst(a);
                if (second != null) queue.addFirst(b);
                continue;
            }
            Match match = new Match(a, b);
            matches.put(a, match);
            matches.put(b, match);
            beginRound(server, match);
            first.sendMessage(Text.literal("§6Матч начался! Всего 9 раундов."), false);
            second.sendMessage(Text.literal("§6Матч начался! Всего 9 раундов."), false);
        }
    }

    private void beginRound(MinecraftServer server, Match match) {
        Identifier id = ItemPoolRegistry.get().getRandomItem(random);
        if (id == null) {
            notifyBoth(server, match, "§cНет доступных предметов с положительным весом. Проверь enabledClasses и weight.<КЛАСС> в config/leave-or-steal.properties.");
            finishMatch(server, match, "§cМатч завершён: нет доступных предметов с положительным весом.");
            return;
        }
        Item item = Registries.ITEM.get(id);
        if (item == null) {
            notifyBoth(server, match, "§cНе удалось создать предмет раунда. Матч завершён.");
            finishMatch(server, match, "§cМатч завершён: предмет раунда недоступен.");
            return;
        }
        match.roundItem = new ItemStack(item);
        match.roundItemId = id;
        match.roundClass = ItemPoolRegistry.get().getClassFor(id);
        match.claim = null;
        match.phase = Phase.CLAIM;

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(match.owner);
        ServerPlayerEntity chooser = server.getPlayerManager().getPlayer(match.other(match.owner));
        if (owner != null) owner.sendMessage(Text.literal("§aРаунд " + match.round + "/9. Твой тайный предмет: §f" +
            match.roundItem.getName().getString() + "§a (§f" + (match.roundClass == null ? "без класса" : match.roundClass.displayName()) + "§a). Расскажи о нём сопернику командой §e/leaveorsteal claim <текст>§a."), false);
        if (chooser != null) chooser.sendMessage(Text.literal("§eРаунд " + match.round + "/9. Соперник выбирает предмет и готовит заявление. Сам предмет пока скрыт."), false);
    }

    public synchronized String status(ServerPlayerEntity player) {
        Match match = matches.get(player.getUuid());
        if (match == null) return queue.contains(player.getUuid()) ? "Ожидание соперника" : "Не в матче";
        if (match.phase == Phase.DUEL) return "Финальная PvP-дуэль";
        return "Раунд " + match.round + "/9, этап: " + match.phase + ", твоя роль: " +
            (match.owner.equals(player.getUuid()) ? "владелец предмета" : "принимаешь решение");
    }

    public synchronized void showLoot(ServerPlayerEntity player) {
        Match match = matches.get(player.getUuid());
        if (match == null) {
            player.sendMessage(Text.literal("§eСейчас у тебя нет активного матча с наградами."), false);
            return;
        }
        List<ItemStack> loot = match.items.get(player.getUuid());
        if (loot == null || loot.isEmpty()) {
            player.sendMessage(Text.literal("§eТы пока не выиграл предметов."), false);
            return;
        }
        String summary = loot.stream()
            .map(stack -> stack.getCount() + "x " + stack.getName().getString())
            .reduce((a, b) -> a + ", " + b).orElse("");
        player.sendMessage(Text.literal("§6Твои выигранные предметы (пока хранятся на сервере): §f" + summary), false);
    }

    public synchronized void claim(ServerPlayerEntity player, String statement) {
        Match match = matches.get(player.getUuid());
        if (match == null || match.phase != Phase.CLAIM || !match.owner.equals(player.getUuid())) {
            player.sendMessage(Text.literal("§cСейчас ты не можешь заявлять о предмете."), false);
            return;
        }
        if (statement.isBlank()) {
            player.sendMessage(Text.literal("§cЗаявление не должно быть пустым."), false);
            return;
        }
        match.claim = statement;
        match.phase = Phase.DECISION;
        ServerPlayerEntity opponent = player.getServer().getPlayerManager().getPlayer(match.other(player.getUuid()));
        if (opponent != null) opponent.sendMessage(Text.literal("§eСоперник говорит: §f" + statement +
            "\n§6Выбери: /leaveorsteal keep или /leaveorsteal steal"), false);
        player.sendMessage(Text.literal("§aЗаявление отправлено. Ждём решения соперника."), false);
    }

    public synchronized void decide(ServerPlayerEntity player, boolean steal) {
        Match match = matches.get(player.getUuid());
        if (match == null || match.phase != Phase.DECISION || match.owner.equals(player.getUuid()) ||
            match.roundItem == null) {
            player.sendMessage(Text.literal("§cСейчас решение недоступно."), false);
            return;
        }

        UUID previousOwner = match.owner;
        UUID winner = steal ? player.getUuid() : previousOwner;
        match.items.get(winner).add(match.roundItem.copy());

        ServerPlayerEntity owner = player.getServer().getPlayerManager().getPlayer(previousOwner);
        if (owner != null) owner.sendMessage(Text.literal(steal ? "§cСоперник украл предмет этого раунда." :
            "§aСоперник оставил предмет тебе."), false);
        player.sendMessage(Text.literal(steal ? "§6Ты забрал предмет этого раунда." : "§6Ты оставил предмет сопернику."), false);

        match.round++;
        match.roundItem = ItemStack.EMPTY;
        if (match.round > 9) {
            match.phase = Phase.DUEL;
            distributeItems(player.getServer(), match);
            teleportToArena(player.getServer(), match);
            notifyBoth(player.getServer(), match,
                "§d9 раундов завершены! Награды у вас в инвентарях. Начинается финальная PvP-дуэль. Команда /leave означает сдачу.");
        } else {
            match.owner = match.other(previousOwner);
            beginRound(player.getServer(), match);
        }
    }

    private void prepareForDuel(ServerPlayerEntity player) {
        if (restoreHealthEnabled) player.setHealth(player.getMaxHealth());
        if (restoreHungerEnabled) {
            player.getHungerManager().setFoodLevel(20);
            player.getHungerManager().setSaturationLevel(20.0f);
        }
        if (restoreHealthEnabled || restoreHungerEnabled) player.sendMessage(Text.literal("§aПеред дуэлью параметры игрока восстановлены. Экипировка и эффекты не изменены."), false);
    }
    private void teleportToArena(MinecraftServer server, Match match) {
        ServerPlayerEntity first = server.getPlayerManager().getPlayer(match.first);
        ServerPlayerEntity second = server.getPlayerManager().getPlayer(match.second);
        if (!arenaTeleportEnabled) {
            notifyBoth(server, match, "§7Телепортация на арену отключена в настройках.");
        } else if (arenaFirst == null || arenaSecond == null) {
            notifyBoth(server, match, "§eТочки арены не настроены: бой начнётся на текущем месте. Используй /leaveorsteal setarena1 и setarena2.");
        } else {
            if (first != null) teleport(first, arenaFirst);
            if (second != null) teleport(second, arenaSecond);
        }
        if (first != null) prepareForDuel(first);
        if (second != null) prepareForDuel(second);
    }
    private void teleport(ServerPlayerEntity player, ArenaPoint point) {
        Identifier dimensionId = Identifier.tryParse(point.dimension);
        if (dimensionId == null) return;
        RegistryKey<World> dimensionKey = RegistryKey.of(RegistryKeys.WORLD, dimensionId);
        ServerWorld world = player.getServer().getWorld(dimensionKey);
        if (world == null) {
            player.sendMessage(Text.literal("§cМир арены не найден: " + point.dimension), false);
            return;
        }
        player.teleport(world, point.x, point.y, point.z, point.yaw, point.pitch);
    }
    private void distributeItems(MinecraftServer server, Match match) {
        for (UUID id : List.of(match.first, match.second)) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player == null) {
                List<ItemStack> pending = pendingRewards.computeIfAbsent(id, ignored -> new ArrayList<>());
                for (ItemStack stack : match.items.get(id)) pending.add(stack.copy());
                match.items.get(id).clear();
                saveRewards(server);
                continue;
            }
            List<ItemStack> loot = match.items.get(id);
            Iterator<ItemStack> iterator = loot.iterator();
            while (iterator.hasNext()) {
                ItemStack stack = iterator.next();
                ItemStack remainder = stack.copy();
                player.getInventory().insertStack(remainder);
                if (!remainder.isEmpty()) player.dropItem(remainder, false);
                iterator.remove();
            }
        }
    }

    public synchronized void onDisconnect(ServerPlayerEntity player) {
        queue.remove(player.getUuid());
        Match match = matches.get(player.getUuid());
        if (match == null) return;
        if (match.phase == Phase.DUEL) {
            finishDuel(player.getServer(), match, match.other(player.getUuid()),
                "§aСоперник отключился во время финальной дуэли. Тебе присуждена победа.");
        } else {
            finishMatch(player.getServer(), match, "§cСоперник отключился. Матч завершён; награды будут выданы при следующем входе.");
        }
    }

    public synchronized void onDeath(ServerPlayerEntity player) {
        Match match = matches.get(player.getUuid());
        if (match == null || match.phase != Phase.DUEL) return;
        finishDuel(player.getServer(), match, match.other(player.getUuid()),
            "§aТы победил в финальной PvP-дуэли!");
    }

    private void deliverPendingRewards(ServerPlayerEntity player) {
        List<ItemStack> pending = pendingRewards.remove(player.getUuid());
        if (pending == null || pending.isEmpty()) return;
        for (ItemStack stack : pending) {
            ItemStack remainder = stack.copy();
            player.getInventory().insertStack(remainder);
            if (!remainder.isEmpty()) player.dropItem(remainder, false);
        }
        saveRewards(player.getServer());
        player.sendMessage(Text.literal("§aТвои награды за прерванный матч выданы."), false);
    }

    public synchronized void leave(ServerPlayerEntity player) {
        queue.remove(player.getUuid());
        Match match = matches.get(player.getUuid());
        if (match == null) return;
        if (match.phase == Phase.DUEL) {
            finishDuel(player.getServer(), match, match.other(player.getUuid()),
                "§aСоперник сдался. Ты победил в финальной PvP-дуэли.");
            player.sendMessage(Text.literal("§cТы сдался и проиграл дуэль."), false);
        } else {
            finishMatch(player.getServer(), match, "§cСоперник покинул матч. Матч завершён, накопленные награды выданы.");
        }
    }

    private void finishDuel(MinecraftServer server, Match match, UUID winner, String winnerMessage) {
        if (match.phase != Phase.DUEL) return;
        match.phase = Phase.FINISHED;
        matches.remove(match.first);
        matches.remove(match.second);
        for (UUID id : List.of(match.first, match.second)) {
            ServerPlayerEntity participant = server.getPlayerManager().getPlayer(id);
            if (participant == null) continue;
            if (id.equals(winner)) {
                participant.sendMessage(Text.literal(winnerMessage), false);
            } else {
                participant.sendMessage(Text.literal("§cТы проиграл финальную PvP-дуэль."), false);
            }
        }
    }

    private void finishMatch(MinecraftServer server, Match match, String message) {
        if (match.roundItem != null && !match.roundItem.isEmpty()) {
            match.items.get(match.owner).add(match.roundItem.copy());
            match.roundItem = ItemStack.EMPTY;
        }
        match.phase = Phase.FINISHED;
        distributeItems(server, match);
        matches.remove(match.first);
        matches.remove(match.second);
        notifyBoth(server, match, message);
        saveRewards(server);
    }

    private void notifyBoth(MinecraftServer server, Match match, String message) {
        for (UUID id : List.of(match.first, match.second)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) p.sendMessage(Text.literal(message), false);
        }
    }

    private static final class ArenaPoint {
        final String dimension;
        final double x, y, z;
        final float yaw, pitch;
        ArenaPoint(String dimension, double x, double y, double z, float yaw, float pitch) {
            this.dimension = dimension; this.x = x; this.y = y; this.z = z; this.yaw = yaw; this.pitch = pitch;
        }
        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("dimension", dimension); json.addProperty("x", x); json.addProperty("y", y);
            json.addProperty("z", z); json.addProperty("yaw", yaw); json.addProperty("pitch", pitch);
            return json;
        }
    }
    private static final class Match {
        final UUID first, second;
        final Map<UUID, List<ItemStack>> items = new HashMap<>();
        UUID owner;
        int round = 1;
        Phase phase = Phase.CLAIM;
        String claim;
        ItemStack roundItem = ItemStack.EMPTY;
        Identifier roundItemId;
        ChestClass roundClass;

        Match(UUID first, UUID second) {
            this.first = first;
            this.second = second;
            this.owner = first;
            items.put(first, new ArrayList<>());
            items.put(second, new ArrayList<>());
        }
        UUID other(UUID id) { return first.equals(id) ? second : first; }
    }
}
