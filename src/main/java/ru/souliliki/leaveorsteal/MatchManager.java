package ru.souliliki.leaveorsteal;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ArmorItem;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.SpawnGroup;
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
    private final Map<UUID, Lobby> lobbies = new HashMap<>();
    private final Map<UUID, UUID> invitations = new HashMap<>();
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
    private int roundsPerMatch = 9;

    public static MatchManager get() { return INSTANCE; }

    public synchronized void loadSettings(MinecraftServer server) {
        settingsFile = server.getSavePath(WorldSavePath.ROOT).resolve("data/leave-or-steal-settings.json");
        if (!Files.exists(settingsFile)) return;
        try (var reader = Files.newBufferedReader(settingsFile)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            arenaTeleportEnabled = !root.has("arenaTeleport") || root.get("arenaTeleport").getAsBoolean();
            restoreHealthEnabled = !root.has("restoreHealth") || root.get("restoreHealth").getAsBoolean();
            restoreHungerEnabled = !root.has("restoreHunger") || root.get("restoreHunger").getAsBoolean();
            roundsPerMatch = root.has("roundsPerMatch") ? Math.max(1, Math.min(30, root.get("roundsPerMatch").getAsInt())) : 9;
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
        root.addProperty("roundsPerMatch", roundsPerMatch);
        try {
            Files.createDirectories(settingsFile.getParent());
            try (var writer = Files.newBufferedWriter(settingsFile)) { GSON.toJson(root, writer); }
        } catch (IOException exception) {
            LeaveOrStealMod.LOGGER.error("Не удалось сохранить настройки мода", exception);
        }
    }

    public synchronized void showLobbyMenu(ServerPlayerEntity player) {
        List<String> online = player.getServer().getPlayerManager().getPlayerList().stream()
            .map(p -> p.getName().getString()).filter(name -> !name.equals(player.getName().getString())).toList();
        if (ServerPlayNetworking.canSend(player, LobbyMenuPayload.ID)) {
            ServerPlayNetworking.send(player, new LobbyMenuPayload(online, player.hasPermissionLevel(2)));
        } else {
            player.sendMessage(Text.literal("§eДля графического меню установи мод на клиент. Команды: /leaveorsteal lobby create, /leaveorsteal invite <игрок>, /leaveorsteal bot."), false);
        }
    }

    public synchronized void createLobby(ServerPlayerEntity host) {
        UUID id = host.getUuid();
        if (matches.containsKey(id) || queue.contains(id)) {
            host.sendMessage(Text.literal("§cСначала выйди из очереди или текущего матча."), false);
            return;
        }
        lobbies.put(id, new Lobby(id));
        host.sendMessage(Text.literal("§aЛобби создано. Открой меню и нажми «Пригласить» рядом с именем игрока."), false);
    }

    public synchronized void invite(ServerPlayerEntity host, String targetName) {
        Lobby lobby = lobbies.get(host.getUuid());
        if (lobby == null) {
            host.sendMessage(Text.literal("§cСначала создай лобби в меню."), false);
            return;
        }
        ServerPlayerEntity target = host.getServer().getPlayerManager().getPlayer(targetName);
        if (target == null || target.getUuid().equals(host.getUuid())) {
            host.sendMessage(Text.literal("§cИгрок не найден или указан ты сам."), false);
            return;
        }
        if (matches.containsKey(target.getUuid()) || queue.contains(target.getUuid()) || lobbies.containsKey(target.getUuid())) {
            host.sendMessage(Text.literal("§eЭтот игрок уже занят или создал своё лобби."), false);
            return;
        }
        invitations.put(target.getUuid(), host.getUuid());
        lobby.invited = target.getUuid();
        MutableText invitation = Text.literal("§6" + host.getName().getString() + " приглашает тебя в «Обман и доверие». ");
        invitation.append(Text.literal("§a[Принять]").styled(style -> style.withClickEvent(
            new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/leaveorsteal accept " + host.getName().getString()))));
        invitation.append(Text.literal(" §c[Отклонить]").styled(style -> style.withClickEvent(
            new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/leaveorsteal decline " + host.getName().getString()))));
        target.sendMessage(invitation, false);
        host.sendMessage(Text.literal("§aПриглашение отправлено игроку " + target.getName().getString() + "."), false);
    }

    public synchronized void acceptInvite(ServerPlayerEntity target, String hostName) {
        ServerPlayerEntity host = target.getServer().getPlayerManager().getPlayer(hostName);
        UUID invitedHost = invitations.get(target.getUuid());
        if (host == null || invitedHost == null || !host.getUuid().equals(invitedHost)) {
            target.sendMessage(Text.literal("§cПриглашение не найдено или уже истекло."), false);
            return;
        }
        if (matches.containsKey(target.getUuid()) || matches.containsKey(host.getUuid())) {
            target.sendMessage(Text.literal("§cОдин из игроков уже находится в матче."), false);
            return;
        }
        invitations.remove(target.getUuid());
        lobbies.remove(host.getUuid());
        Match match = new Match(host.getUuid(), target.getUuid(), roundsPerMatch);
        matches.put(host.getUuid(), match);
        matches.put(target.getUuid(), match);
        beginRound(target.getServer(), match);
        notifyBoth(target.getServer(), match, "§6Лобби заполнено! Матч начался на " + match.totalRounds + " раундов.");
    }

    public synchronized void declineInvite(ServerPlayerEntity target, String hostName) {
        ServerPlayerEntity host = target.getServer().getPlayerManager().getPlayer(hostName);
        UUID invitedHost = invitations.get(target.getUuid());
        if (host == null || invitedHost == null || !host.getUuid().equals(invitedHost)) {
            target.sendMessage(Text.literal("§cПриглашение не найдено или уже истекло."), false);
            return;
        }
        invitations.remove(target.getUuid());
        host.sendMessage(Text.literal("§e" + target.getName().getString() + " отклонил приглашение."), false);
        target.sendMessage(Text.literal("§7Приглашение отклонено."), false);
    }

    public synchronized void startBotMatch(ServerPlayerEntity player) {
        if (matches.containsKey(player.getUuid()) || queue.contains(player.getUuid())) {
            player.sendMessage(Text.literal("§cТы уже в очереди или матче."), false);
            return;
        }
        lobbies.remove(player.getUuid());
        Match match = new Match(player.getUuid(), UUID.randomUUID(), roundsPerMatch);
        match.botOpponent = true;
        matches.put(player.getUuid(), match);
        player.sendMessage(Text.literal("§6Ты начал матч против ИИ-бота. Бот будет принимать решения и вести свои раунды."), false);
        beginRound(player.getServer(), match);
    }

    public synchronized void showSettings(ServerPlayerEntity player) {
        List<Integer> classWeights = Arrays.stream(ChestClass.values())
            .map(chestClass -> ItemPoolRegistry.get().getClassWeight(chestClass)).toList();
        List<Boolean> enabledClasses = Arrays.stream(ChestClass.values())
            .map(chestClass -> ItemPoolRegistry.get().isClassEnabled(chestClass)).toList();
        if (ServerPlayNetworking.canSend(player, SettingsSyncPayload.ID)) {
            ServerPlayNetworking.send(player, new SettingsSyncPayload(
                arenaTeleportEnabled, restoreHealthEnabled, restoreHungerEnabled, roundsPerMatch, classWeights, enabledClasses));
        }
        player.sendMessage(Text.literal("§6§lОбман и доверие — настройки"), false);
        sendSetting(player, "Телепортация на арену", "arena", arenaTeleportEnabled);
        sendSetting(player, "Восстанавливать здоровье", "health", restoreHealthEnabled);
        sendSetting(player, "Восстанавливать голод", "hunger", restoreHungerEnabled);
        MutableText roundsLine = Text.literal("§fКоличество раундов: §e" + roundsPerMatch + " ");
        Text decreaseRounds = Text.literal("§c[-]").styled(style ->
            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/leaveorsteal rounds " + Math.max(1, roundsPerMatch - 1))));
        Text increaseRounds = Text.literal(" §a[+]").styled(style ->
            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                "/leaveorsteal rounds " + Math.min(30, roundsPerMatch + 1))));
        player.sendMessage(roundsLine.append(decreaseRounds).append(increaseRounds), false);
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

    public synchronized void setRoundsPerMatch(ServerPlayerEntity player, int rounds) {
        roundsPerMatch = Math.max(1, Math.min(30, rounds));
        saveSettings();
        player.sendMessage(Text.literal("§aКоличество раундов установлено: " + roundsPerMatch), false);
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
            for (ItemStack ammo : match.roundBonusItems) match.items.get(match.owner).add(ammo.copy());
            match.roundBonusItems.clear();
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
            Match match = new Match(a, b, roundsPerMatch);
            matches.put(a, match);
            matches.put(b, match);
            beginRound(server, match);
            first.sendMessage(Text.literal("§6Матч начался! Всего " + match.totalRounds + " раундов."), false);
            second.sendMessage(Text.literal("§6Матч начался! Всего " + match.totalRounds + " раундов."), false);
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
        match.roundBonusItems.clear();
        match.roundBonusItems.addAll(ItemPoolRegistry.get().getAmmunitionFor(id));
        match.roundItemId = id;
        match.roundClass = ItemPoolRegistry.get().getClassFor(id);
        match.claim = null;
        match.phase = Phase.CLAIM;

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(match.owner);
        ServerPlayerEntity chooser = server.getPlayerManager().getPlayer(match.other(match.owner));
        if (match.botOpponent && match.owner.equals(match.second)) {
            match.claim = "Я нашёл кое-что интересное. Думаю, тебе стоит решить, доверяешь ли ты мне.";
            match.phase = Phase.DECISION;
            if (chooser != null) chooser.sendMessage(Text.literal("§eРаунд " + match.round + "/" + match.totalRounds +
                ". Бот выбрал предмет и говорит: §f" + match.claim + " §6Реши: /leaveorsteal keep или /leaveorsteal steal."), false);
            return;
        }
        if (owner != null) {
            String ammoNote = match.roundBonusItems.isEmpty() ? "" : " §7Дополнительно получишь боеприпасы: " +
                match.roundBonusItems.stream().map(stack -> stack.getCount() + "x " + stack.getName().getString())
                    .reduce((a, b) -> a + ", " + b).orElse("") + ".";
            owner.sendMessage(Text.literal("§aРаунд " + match.round + "/" + match.totalRounds + ". Твой тайный предмет: §f" +
                match.roundItem.getName().getString() + "§a (§f" + (match.roundClass == null ? "без класса" : match.roundClass.displayName()) + "§a)." +
                ammoNote + " Расскажи о нём сопернику командой §e/leaveorsteal claim <текст>§a."), false);
        }
        if (chooser != null) chooser.sendMessage(Text.literal("§eРаунд " + match.round + "/" + match.totalRounds + ". Соперник выбирает предмет и готовит заявление. Сам предмет пока скрыт."), false);
    }

    public synchronized String status(ServerPlayerEntity player) {
        Match match = matches.get(player.getUuid());
        if (match == null) return queue.contains(player.getUuid()) ? "Ожидание соперника" : "Не в матче";
        if (match.phase == Phase.DUEL) return "Финальная PvP-дуэль";
        return "Раунд " + match.round + "/" + match.totalRounds + ", этап: " + match.phase + ", твоя роль: " +
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
        if (match.botOpponent && match.other(player.getUuid()).equals(match.second)) {
            boolean botSteals = random.nextInt(100) < 32;
            player.sendMessage(Text.literal(botSteals ? "§cБот решил украсть предмет." : "§aБот поверил тебе и оставил предмет."), false);
            resolveDecision(player.getServer(), match, match.second, botSteals);
            return;
        }
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
        resolveDecision(player.getServer(), match, player.getUuid(), steal);
    }

    private void resolveDecision(MinecraftServer server, Match match, UUID chooser, boolean steal) {
        UUID previousOwner = match.owner;
        UUID winner = steal ? chooser : previousOwner;
        match.items.get(winner).add(match.roundItem.copy());
        for (ItemStack ammo : match.roundBonusItems) match.items.get(winner).add(ammo.copy());
        match.roundBonusItems.clear();

        ServerPlayerEntity owner = server.getPlayerManager().getPlayer(previousOwner);
        if (owner != null) owner.sendMessage(Text.literal(steal ? "§cСоперник украл предмет этого раунда." :
            "§aСоперник оставил предмет тебе."), false);
        ServerPlayerEntity chooserPlayer = server.getPlayerManager().getPlayer(chooser);
        if (chooserPlayer != null && !chooser.equals(previousOwner)) {
            chooserPlayer.sendMessage(Text.literal(steal ? "§6Ты забрал предмет этого раунда." : "§6Ты оставил предмет сопернику."), false);
        }

        match.round++;
        match.roundItem = ItemStack.EMPTY;
        if (match.round > match.totalRounds) {
            match.phase = Phase.DUEL;
            teleportToArena(server, match);
            if (match.botOpponent) {
                spawnBotOpponent(server, match);
                distributeItems(server, match);
                if (match.duelMob != null && match.duelMob.isAlive()) {
                    notifyBoth(server, match, "§dРаунды завершены! Против тебя вышел моб-противник: §f" +
                        match.duelMob.getName().getString() +
                        "§d. Он экипирован добытыми предметами — победи его в бою!");
                }
            } else {
                distributeItems(server, match);
                notifyBoth(server, match,
                    "§d" + match.totalRounds + " раундов завершены! Награды у вас в инвентарях. Начинается финальная PvP-дуэль. Команда /leave означает сдачу.");
            }
        } else {
            match.owner = match.other(previousOwner);
            beginRound(server, match);
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
    private void spawnBotOpponent(MinecraftServer server, Match match) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(match.first);
        if (player == null) {
            finishDuel(server, match, match.second, "§cИгрок покинул сервер до начала битвы.");
            return;
        }

        ServerWorld world = player.getServerWorld();
        double x = player.getX() + 3.0;
        double y = player.getY();
        double z = player.getZ();
        if (arenaTeleportEnabled && arenaFirst != null && arenaSecond != null) {
            Identifier dimensionId = Identifier.tryParse(arenaSecond.dimension);
            if (dimensionId != null) {
                ServerWorld arenaWorld = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, dimensionId));
                if (arenaWorld != null) {
                    world = arenaWorld;
                    x = arenaSecond.x;
                    y = arenaSecond.y;
                    z = arenaSecond.z;
                }
            }
        }

        List<MobEntity> candidates = new ArrayList<>();
        for (var type : Registries.ENTITY_TYPE) {
            if (type.getSpawnGroup() != SpawnGroup.MONSTER) continue;
            var entity = type.create(world);
            if (entity instanceof MobEntity mob) candidates.add(mob);
            else if (entity != null) entity.discard();
        }
        if (candidates.isEmpty()) {
            player.sendMessage(Text.literal("§cНа сервере не найдено враждебных мобов для финальной битвы."), false);
            finishDuel(server, match, match.first, "§aПротивник не появился — победа засчитана.");
            return;
        }

        MobEntity mob = candidates.get(random.nextInt(candidates.size()));
        for (MobEntity candidate : candidates) if (candidate != mob) candidate.discard();
        mob.refreshPositionAndAngles(x, y, z, player.getYaw() + 180.0F, 0.0F);
        mob.setPersistent();
        mob.setCanPickUpLoot(false);
        mob.setCustomName(Text.literal("Соперник: " + mob.getName().getString()));
        mob.setCustomNameVisible(true);
        mob.setAiDisabled(true);
        mob.setTarget(null);

        List<ItemStack> earned = match.items.get(match.second);
        ItemStack bestWeapon = ItemStack.EMPTY;
        int bestWeaponScore = -1;
        Map<EquipmentSlot, ItemStack> bestArmor = new EnumMap<>(EquipmentSlot.class);
        Map<EquipmentSlot, Integer> armorScores = new EnumMap<>(EquipmentSlot.class);
        for (ItemStack stack : earned) {
            if (stack == null || stack.isEmpty()) continue;
            if (stack.getItem() instanceof ArmorItem armor) {
                EquipmentSlot slot = armor.getSlotType();
                String armorPath = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
                int score = weaponScore(armorPath);
                if (score > armorScores.getOrDefault(slot, -1)) {
                    bestArmor.put(slot, stack.copy());
                    armorScores.put(slot, score);
                }
                continue;
            }
            String path = Registries.ITEM.getId(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
            if (isCombatWeapon(path)) {
                int score = weaponScore(path);
                if (score > bestWeaponScore) {
                    bestWeapon = stack;
                    bestWeaponScore = score;
                }
            }
        }
        bestArmor.forEach(mob::equipStack);
        if (!bestWeapon.isEmpty()) mob.equipStack(EquipmentSlot.MAINHAND, bestWeapon.copy());
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!mob.getEquippedStack(slot).isEmpty()) mob.setEquipmentDropChance(slot, 1.0F);
        }

        if (!world.spawnEntity(mob)) {
            mob.discard();
            finishDuel(server, match, match.first, "§aПротивник не смог появиться на арене — победа засчитана.");
            return;
        }
        match.duelMob = mob;
        player.sendMessage(Text.literal("§eПодойди к сопернику и нажми ПКМ, чтобы выбрать: уйти или сразиться."), false);
    }

    public synchronized boolean showMobMenu(ServerPlayerEntity player, MobEntity mob) {
        Match match = matches.get(player.getUuid());
        if (match == null || !match.botOpponent || match.phase != Phase.DUEL ||
            match.duelMob == null || !match.duelMob.getUuid().equals(mob.getUuid()) || !mob.isAlive()) {
            return false;
        }
        MutableText menu = Text.literal("§6§lСоперник: §f" + mob.getName().getString() + " §7— выбери действие: ");
        menu.append(Text.literal("§c[Уйти]").styled(style ->
            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/leaveorsteal mob leave"))));
        menu.append(Text.literal(" §a[Сразиться]").styled(style ->
            style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/leaveorsteal mob fight"))));
        player.sendMessage(menu, false);
        return true;
    }

    public synchronized void chooseMobAction(ServerPlayerEntity player, boolean fight) {
        Match match = matches.get(player.getUuid());
        if (match == null || !match.botOpponent || match.phase != Phase.DUEL ||
            match.duelMob == null || !match.duelMob.isAlive()) {
            player.sendMessage(Text.literal("§cСейчас нет активного моба-соперника."), false);
            return;
        }
        if (!fight) {
            player.sendMessage(Text.literal("§eТы решил уйти от боя. Матч завершён поражением."), false);
            finishDuel(player.getServer(), match, match.second, "§7Игрок отказался от сражения.");
            return;
        }
        match.duelMob.setAiDisabled(false);
        match.duelMob.setTarget(player);
        player.sendMessage(Text.literal("§cБой начался! Победи моба, чтобы завершить матч."), false);
    }

    private boolean isCombatWeapon(String path) {
        return path.contains("sword") || path.contains("axe") || path.contains("bow") ||
            path.contains("crossbow") || path.contains("trident") || path.contains("mace") ||
            path.contains("dagger") || path.contains("spear") || path.contains("staff") ||
            path.contains("wand") || path.contains("katana") || path.contains("scythe") ||
            path.contains("hammer") || path.contains("greatsword") || path.contains("battleaxe");
    }

    private int weaponScore(String path) {
        if (path.contains("netherite")) return 6;
        if (path.contains("diamond")) return 5;
        if (path.contains("iron")) return 4;
        return 3;
    }

    public synchronized void onMobDeath(MobEntity mob) {
        for (Match match : new HashSet<>(matches.values())) {
            if (match.phase == Phase.DUEL && match.botOpponent && match.duelMob != null &&
                match.duelMob.getUuid().equals(mob.getUuid())) {
                finishDuel(((ServerWorld) mob.getWorld()).getServer(), match, match.first,
                    "§aТы победил моба-противника в финальной битве!");
                return;
            }
        }
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
            if (match.botOpponent && id.equals(match.second)) {
                match.items.get(id).clear();
                continue;
            }
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
        lobbies.remove(player.getUuid());
        invitations.remove(player.getUuid());
        invitations.values().removeIf(host -> host.equals(player.getUuid()));
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
        if (match.botOpponent) {
            finishDuel(player.getServer(), match, match.second, "§cМоб-противник победил в финальной битве.");
        } else {
            finishDuel(player.getServer(), match, match.other(player.getUuid()),
                "§aТы победил в финальной PvP-дуэли!");
        }
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
                match.botOpponent ? "§cТы сдался — моб-противник победил." : "§aСоперник сдался. Ты победил в финальной PvP-дуэли.");
            player.sendMessage(Text.literal("§cТы сдался и проиграл дуэль."), false);
        } else {
            finishMatch(player.getServer(), match, "§cСоперник покинул матч. Матч завершён, накопленные награды выданы.");
        }
    }

    private void finishDuel(MinecraftServer server, Match match, UUID winner, String winnerMessage) {
        if (match.phase != Phase.DUEL) return;
        match.phase = Phase.FINISHED;
        if (match.duelMob != null && match.duelMob.isAlive()) match.duelMob.discard();
        matches.remove(match.first);
        matches.remove(match.second);
        for (UUID id : List.of(match.first, match.second)) {
            ServerPlayerEntity participant = server.getPlayerManager().getPlayer(id);
            if (participant == null) continue;
            if (id.equals(winner)) {
                participant.sendMessage(Text.literal(winnerMessage), false);
            } else if (match.botOpponent && id.equals(match.first) && winner.equals(match.second)) {
                participant.sendMessage(Text.literal("§cТы отказался от сражения и покинул арену."), false);
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
        for (ItemStack ammo : match.roundBonusItems) match.items.get(match.owner).add(ammo.copy());
        match.roundBonusItems.clear();
        match.phase = Phase.FINISHED;
        distributeItems(server, match);
        matches.remove(match.first);
        matches.remove(match.second);
        notifyBoth(server, match, message);
        saveRewards(server);
    }

    private void notifyBoth(MinecraftServer server, Match match, String message) {
        for (UUID id : List.of(match.first, match.second)) {
            if (match.botOpponent && id.equals(match.second)) continue;
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
    private static final class Lobby {
        final UUID host;
        UUID invited;
        Lobby(UUID host) { this.host = host; }
    }

    private static final class Match {
        final UUID first, second;
        boolean botOpponent;
        final Map<UUID, List<ItemStack>> items = new HashMap<>();
        final int totalRounds;
        UUID owner;
        int round = 1;
        Phase phase = Phase.CLAIM;
        String claim;
        ItemStack roundItem = ItemStack.EMPTY;
        final List<ItemStack> roundBonusItems = new ArrayList<>();
        Identifier roundItemId;
        ChestClass roundClass;
        MobEntity duelMob;

        Match(UUID first, UUID second, int totalRounds) {
            this.totalRounds = totalRounds;
            this.first = first;
            this.second = second;
            this.owner = first;
            items.put(first, new ArrayList<>());
            items.put(second, new ArrayList<>());
        }
        UUID other(UUID id) { return first.equals(id) ? second : first; }
    }
}
