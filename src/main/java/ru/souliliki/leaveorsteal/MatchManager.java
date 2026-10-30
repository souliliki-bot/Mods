package ru.souliliki.leaveorsteal;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

public final class MatchManager {
    public enum Phase { CLAIM, DECISION, FINISHED }
    private static final MatchManager INSTANCE = new MatchManager();
    private final Deque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, Match> matches = new HashMap<>();
    private final Random random = new Random();

    public static MatchManager get() { return INSTANCE; }

    public synchronized void joinQueue(ServerPlayerEntity player) {
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
        List<Identifier> candidates = new ArrayList<>();
        for (ChestClass chestClass : ChestClass.values()) {
            candidates.addAll(ItemPoolRegistry.get().getItems(chestClass));
        }
        if (candidates.isEmpty()) {
            notifyBoth(server, match, "§cПул предметов пуст. Проверь config/leave-or-steal.properties.");
            matches.remove(match.first);
            matches.remove(match.second);
            return;
        }

        Identifier id = candidates.get(random.nextInt(candidates.size()));
        Item item = Registries.ITEM.get(id);
        if (item == null) {
            notifyBoth(server, match, "§cНе удалось создать предмет раунда. Матч завершён.");
            matches.remove(match.first);
            matches.remove(match.second);
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
            match.phase = Phase.FINISHED;
            distributeItems(player.getServer(), match);
            notifyBoth(player.getServer(), match, "§d9 раундов завершены. Предметы выданы в инвентари. Следующий этап — арена и PvP-дуэль (ещё не реализованы).");
            matches.remove(match.first);
            matches.remove(match.second);
        } else {
            match.owner = match.other(previousOwner);
            beginRound(player.getServer(), match);
        }
    }

    private void distributeItems(MinecraftServer server, Match match) {
        for (UUID id : List.of(match.first, match.second)) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(id);
            if (player == null) continue;
            for (ItemStack stack : match.items.get(id)) {
                ItemStack remainder = stack.copy();
                if (!player.getInventory().insertStack(remainder) && !remainder.isEmpty()) {
                    player.dropItem(remainder, false);
                }
            }
            match.items.get(id).clear();
        }
    }

    public synchronized void leave(ServerPlayerEntity player) {
        queue.remove(player.getUuid());
        Match match = matches.remove(player.getUuid());
        if (match != null) {
            matches.remove(match.other(player.getUuid()));
            ServerPlayerEntity opponent = player.getServer().getPlayerManager().getPlayer(match.other(player.getUuid()));
            if (opponent != null) opponent.sendMessage(Text.literal("§cСоперник покинул матч. Матч завершён."), false);
        }
    }

    private void notifyBoth(MinecraftServer server, Match match, String message) {
        for (UUID id : List.of(match.first, match.second)) {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
            if (p != null) p.sendMessage(Text.literal(message), false);
        }
    }

    private static final class Match {
        final UUID first, second;
        final Map<UUID, List<ItemStack>> items = new HashMap<>();
        UUID owner;
        int round = 1;
        Phase phase = Phase.CLAIM;
        String claim;
        ItemStack roundItem = ItemStack.EMPTY;\n        Identifier roundItemId;\n        ChestClass roundClass;

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
