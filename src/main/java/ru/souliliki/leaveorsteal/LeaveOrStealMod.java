package ru.souliliki.leaveorsteal;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;

public class LeaveOrStealMod implements ModInitializer {
    public static final String MOD_ID = "leave_or_steal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("leaveorsteal")
                .then(CommandManager.literal("help").executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal(
                        "Обман и доверие: /leaveorsteal join — очередь; /leaveorsteal claim <текст> — заявление; " +
                        "/leaveorsteal keep или steal — решение; /leaveorsteal status; /leaveorsteal leave."
                    ), false);
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
                        MatchManager.get().claim(context.getSource().getPlayer(),
                            StringArgumentType.getString(context, "statement"));
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
                .then(CommandManager.literal("status").executes(context -> {
                    if (context.getSource().getPlayer() == null) return 0;
                    String status = MatchManager.get().status(context.getSource().getPlayer());
                    context.getSource().sendFeedback(() -> Text.literal(status), false);
                    return 1;
                }))
                .then(CommandManager.literal("classes").executes(context -> {
                    String classes = Arrays.stream(ChestClass.values())
                        .map(c -> c.name() + " — " + c.displayName())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                    context.getSource().sendFeedback(() -> Text.literal("Классы сундуков:\n" + classes), false);
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
