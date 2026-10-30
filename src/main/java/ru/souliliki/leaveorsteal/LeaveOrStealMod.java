package ru.souliliki.leaveorsteal;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

public class LeaveOrStealMod implements ModInitializer {
    public static final String MOD_ID = "leave_or_steal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                ItemPoolRegistry.get().reload();
                LOGGER.info("Пулы предметов загружены после запуска сервера: {} классов сундуков",
                    ChestClass.values().length);
            } catch (IOException exception) {
                LOGGER.error("Не удалось загрузить конфигурацию пулов предметов", exception);
            }
            MatchManager.get().loadRewards(server);
            MatchManager.get().loadArena(server);
            MatchManager.get().loadSettings(server);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> MatchManager.get().saveOnShutdown(server));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            MatchManager.get().onJoin(handler.getPlayer())
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            MatchManager.get().onDisconnect(handler.getPlayer())
        );
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof net.minecraft.server.network.ServerPlayerEntity player) {
                MatchManager.get().onDeath(player);
            }
        });


        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("leaveorsteal")
                .then(CommandManager.literal("help").executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal(
                        "Команды: settings (меню настроек, оператор), setting <arena|health|hunger>, join, claim <текст>, keep, steal, loot, status, leave, setarena1, setarena2, classes, pool, items <namespace>, reloadconfig."
                    ), false);
                    return 1;
                }))
                .then(CommandManager.literal("settings").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().showSettings(context.getSource().getPlayer());
                    return 1;
                }))
                .then(CommandManager.literal("setting").requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.literal("arena").executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().toggleSetting(context.getSource().getPlayer(), "arena");
                        return 1;
                    }))
                    .then(CommandManager.literal("health").executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().toggleSetting(context.getSource().getPlayer(), "health");
                        return 1;
                    }))
                    .then(CommandManager.literal("hunger").executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().toggleSetting(context.getSource().getPlayer(), "hunger");
                        return 1;
                    })))
                .then(CommandManager.literal("setarena1").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().setArenaPoint(context.getSource().getPlayer(), true);
                    return 1;
                }))
                .then(CommandManager.literal("setarena2").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().setArenaPoint(context.getSource().getPlayer(), false);
                    return 1;
                }))
                .then(CommandManager.literal("join").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().joinQueue(context.getSource().getPlayer());
                    return 1;
                }))
                .then(CommandManager.literal("claim").then(CommandManager.argument("statement", StringArgumentType.greedyString())
                    .executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().claim(context.getSource().getPlayer(), StringArgumentType.getString(context, "statement"));
                        return 1;
                    })))
                .then(CommandManager.literal("keep").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().decide(context.getSource().getPlayer(), false);
                    return 1;
                }))
                .then(CommandManager.literal("steal").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().decide(context.getSource().getPlayer(), true);
                    return 1;
                }))
                .then(CommandManager.literal("leave").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().leave(context.getSource().getPlayer());
                    context.getSource().sendFeedback(() -> Text.literal("Ты покинул очередь/матч."), false);
                    return 1;
                }))
                .then(CommandManager.literal("loot").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().showLoot(context.getSource().getPlayer());
                    return 1;
                }))
                .then(CommandManager.literal("status").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    context.getSource().sendFeedback(() -> Text.literal(MatchManager.get().status(context.getSource().getPlayer())), false);
                    return 1;
                }))
                .then(CommandManager.literal("classes").executes(context -> {
                    String classes = Arrays.stream(ChestClass.values())
                        .map(c -> c.name() + " — " + c.displayName())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                    context.getSource().sendFeedback(() -> Text.literal("Классы сундуков:\n" + classes), false);
                    return 1;
                }))
                .then(CommandManager.literal("pool").executes(context -> {
                    String summary = Arrays.stream(ChestClass.values())
                        .map(c -> c.name() + ": " + ItemPoolRegistry.get().count(c))
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                    context.getSource().sendFeedback(() -> Text.literal("Пулы предметов:\n" + summary +
                        "\nФайл: config/leave-or-steal.properties"), false);
                    return 1;
                }))
                .then(CommandManager.literal("reloadconfig").requires(source -> source.hasPermissionLevel(2)).executes(context -> {
                    try {
                        ItemPoolRegistry.get().reload();
                        context.getSource().sendFeedback(() -> Text.literal("§aКонфигурация предметов перезагружена."), true);
                    } catch (IOException exception) {
                        context.getSource().sendError(Text.literal("Не удалось прочитать конфигурацию: " + exception.getMessage()));
                    }
                    return 1;
                }))
                .then(CommandManager.literal("items").then(CommandManager.argument("namespace", StringArgumentType.word())
                    .executes(context -> {
                        String namespace = StringArgumentType.getString(context, "namespace").toLowerCase(Locale.ROOT);
                        int count = 0;
                        for (Identifier id : Registries.ITEM.getIds()) if (id.getNamespace().equals(namespace)) count++;
                        final int result = count;
                        context.getSource().sendFeedback(() -> Text.literal("Предметов namespace '" + namespace + "': " + result), false);
                        return result;
                    })))
            )
        );
        LOGGER.info("Обман и доверие загружен: {} классов сундуков", ChestClass.values().length);
    }
}
