package ru.souliliki.leaveorsteal;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.*;

public final class MatchManager {
    public enum Phase { CLAIM, DECISION, FINISHED }
    private static final MatchManager INSTANCE = new MatchManager();
    private final Deque<UUID> queue = new ArrayDeque<>();
    private final Map<UUID, Match> matches = new HashMap<>();

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
            first.sendMessage(Text.literal("§6Матч начался! Раунд 1 из 9. Ты рассказываешь о своём предмете."), false);
            second.sendMessage(Text.literal("§6Матч начался! Раунд 1 из 9. Слушай заявление соперника."), false);
        }
    }

    public synchronized String status(ServerPlayerEntity player) {
        Match match = matches.get(player.getUuid());
        if (match == null) return queue.contains(player.getUuid()) ? "Ожидание соперника" : "Не в матче";
        return "Раунд " + match.round + "/9, этап: " + match.phase + ", твоя роль: " +
            (match.owner.equals(player.getUuid()) ? "владелец предмета" : "принимаешь решение");
    }

    public synchronized void claim(ServerPlayerEntity player, String statement) {
        Match match = matches.get(player.getUuid());
        if (match == null || match.phase != Phase.CLAIM || !match.owner.equals(player.getUuid())) {
            player.sendMessage(Text.literal("§cСейчас ты не можешь заявлять о предмете."), false);
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
        if (match == null || match.phase != Phase.DECISION || match.owner.equals(player.getUuid())) {
            player.sendMessage(Text.literal("§cСейчас решение недоступно."), false);
            return;
        }
        UUID previousOwner = match.owner;
        UUID winner = steal ? player.getUuid() : previousOwner;
        ServerPlayerEntity owner = player.getServer().getPlayerManager().getPlayer(previousOwner);
        if (owner != null) owner.sendMessage(Text.literal(steal ? "§cСоперник решил УКРАСТЬ (пока без выдачи предметов)." :
            "§aСоперник решил ОСТАВИТЬ предмет тебе."), false);
        player.sendMessage(Text.literal(steal ? "§6Ты выбрал УКРАСТЬ." : "§6Ты выбрал ОСТАВИТЬ."), false);
        match.owner = winner;
        match.round++;
        match.claim = null;
        if (match.round > 9) {
            match.phase = Phase.FINISHED;
            notifyBoth(player.getServer(), match, "§d9 раундов завершены. Следующий этап — экипировка и дуэль (ещё не реализованы).");
            matches.remove(match.first);
            matches.remove(match.second);
        } else {
            match.phase = Phase.CLAIM;
            notifyBoth(player.getServer(), match, "§6Раунд " + match.round + "/9. Новый владелец предмета делает заявление.");
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
        UUID owner;
        int round = 1;
        Phase phase = Phase.CLAIM;
        String claim;
        Match(UUID first, UUID second) { this.first = first; this.second = second; this.owner = first; }
        UUID other(UUID id) { return first.equals(id) ? second : first; }
    }
}
