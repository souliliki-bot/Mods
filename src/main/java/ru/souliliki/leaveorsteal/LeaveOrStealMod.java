package ru.souliliki.leaveorsteal;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.util.ActionResult;
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
        PayloadTypeRegistry.playS2C().register(SettingsSyncPayload.ID, SettingsSyncPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(LobbyMenuPayload.ID, LobbyMenuPayload.CODEC);
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
        ServerTickEvents.END_SERVER_TICK.register(server -> MatchManager.get().tick(server));

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer &&
                MatchManager.get().openTrapChest(serverPlayer, hitResult.getBlockPos())) {
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) ->
            !MatchManager.get().isTrapChest(world, pos));

        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!world.isClient && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer &&
                entity instanceof net.minecraft.entity.mob.MobEntity mob &&
                MatchManager.get().showMobMenu(serverPlayer, mob)) {
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
            MatchManager.get().onJoin(handler.getPlayer())
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            MatchManager.get().onDisconnect(handler.getPlayer())
        );
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (entity instanceof net.minecraft.server.network.ServerPlayerEntity player) {
                MatchManager.get().onDeath(player);
            } else if (entity instanceof net.minecraft.entity.mob.MobEntity mob) {
                MatchManager.get().onMobDeath(mob);
            }
        });


        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("leaveorsteal")
                .then(CommandManager.literal("help").executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal(
                        "Команды: menu, lobby create, invite <игрок>, accept <игрок>, decline <игрок>, bot, quick, chaos, highstakes, ability <shield|insight>, wager, stats, settings (настройки, оператор), setting <arena|health|hunger>, rounds <1..30>, join, claim <текст>, keep, steal, loot, status, leave, setarena1, setarena2, classes, pool, weight <класс> <0..100000>, inspect <namespace:item>, items <namespace>, reloadconfig."
                    ), false);
                    return 1;
                }))
                .then(CommandManager.literal("menu").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().showLobbyMenu(context.getSource().getPlayer());
                    return 1;
                }))
                .then(CommandManager.literal("lobby").then(CommandManager.literal("create").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().createLobby(context.getSource().getPlayer());
                    return 1;
                })))
                .then(CommandManager.literal("invite").then(CommandManager.argument("target", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        context.getSource().getServer().getPlayerManager().getPlayerList().forEach(p -> builder.suggest(p.getName().getString()));
                        return builder.buildFuture();
                    }).executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().invite(context.getSource().getPlayer(), StringArgumentType.getString(context, "target"));
                        return 1;
                    })))
                .then(CommandManager.literal("accept").then(CommandManager.argument("host", StringArgumentType.word())
                    .executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().acceptInvite(context.getSource().getPlayer(), StringArgumentType.getString(context, "host"));
                        return 1;
                    })))
                .then(CommandManager.literal("decline").then(CommandManager.argument("host", StringArgumentType.word())
                    .executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().declineInvite(context.getSource().getPlayer(), StringArgumentType.getString(context, "host"));
                        return 1;
                    })))
                .then(CommandManager.literal("bot").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().startBotMatch(context.getSource().getPlayer());
                    return 1;
                }))
                .then(CommandManager.literal("quick").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().startBotMatch(context.getSource().getPlayer(), 3, false, false);
                    return 1;
                }))
                .then(CommandManager.literal("chaos").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().startBotMatch(context.getSource().getPlayer(), 9, true, false);
                    return 1;
                }))
                .then(CommandManager.literal("highstakes").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().startBotMatch(context.getSource().getPlayer(), 9, false, true);
                    return 1;
                }))
                .then(CommandManager.literal("ability").then(CommandManager.argument("name", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("shield"); builder.suggest("insight"); return builder.buildFuture();
                    }).executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().useAbility(context.getSource().getPlayer(), StringArgumentType.getString(context, "name"));
                        return 1;
                    })))
                .then(CommandManager.literal("wager").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().wager(context.getSource().getPlayer());
                    return 1;
                }))
                .then(CommandManager.literal("stats").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    MatchManager.get().showStats(context.getSource().getPlayer());
                    return 1;
                }))
                .then(CommandManager.literal("mob")
                    .then(CommandManager.literal("leave").executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().chooseMobAction(context.getSource().getPlayer(), false);
                        return 1;
                    }))
                    .then(CommandManager.literal("fight").executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().chooseMobAction(context.getSource().getPlayer(), true);
                        return 1;
                    })))
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
                .then(CommandManager.literal("rounds").requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 30))
                        .executes(context -> {
                            if (context.getSource().getPlayer() == null) return 0;
                            MatchManager.get().setRoundsPerMatch(context.getSource().getPlayer(),
                                IntegerArgumentType.getInteger(context, "count"));
                            return 1;
                        })))
                .then(CommandManager.literal("weight").requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests((context, builder) -> {
                            for (ChestClass chestClass : ChestClass.values()) builder.suggest(chestClass.name().toLowerCase(Locale.ROOT));
                            return builder.buildFuture();
                        })
                        .then(CommandManager.argument("value", IntegerArgumentType.integer(0, 100000))
                            .executes(context -> {
                                if (context.getSource().getPlayer() == null) return 0;
                                MatchManager.get().setClassWeight(context.getSource().getPlayer(),
                                    StringArgumentType.getString(context, "name"),
                                    IntegerArgumentType.getInteger(context, "value"));
                                return 1;
                            }))))
                .then(CommandManager.literal("class").requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("name", StringArgumentType.word()).suggests((context, builder) -> {
                        for (ChestClass chestClass : ChestClass.values()) builder.suggest(chestClass.name().toLowerCase(Locale.ROOT));
                        return builder.buildFuture();
                    }).executes(context -> {
                        if (context.getSource().getPlayer() == null) return 0;
                        MatchManager.get().toggleClass(context.getSource().getPlayer(),
                            StringArgumentType.getString(context, "name"));
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
                .then(CommandManager.literal("inspect").requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("item", StringArgumentType.word()).executes(context -> {
                        String rawId = StringArgumentType.getString(context, "item");
                        Identifier id = Identifier.tryParse(rawId);
                        if (id == null) {
                            context.getSource().sendError(Text.literal("Неверный ID предмета. Пример: magicmod:ruby_sword"));
                            return 0;
                        }
                        String details = ItemPoolRegistry.get().describeClassification(id);
                        context.getSource().sendFeedback(() -> Text.literal(id + "\\n" + details), false);
                        return 1;
                    })))
                .then(CommandManager.literal("pool").executes(context -> {
                    String summary = Arrays.stream(ChestClass.values())
                        .map(c -> c.name() + ": " + ItemPoolRegistry.get().count(c) +
                            " предметов, вес " + ItemPoolRegistry.get().getClassWeight(c))
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                    context.getSource().sendFeedback(() -> Text.literal("Пулы предметов (количество и относительный вес):\n" + summary +
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
