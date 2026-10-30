package ru.souliliki.leaveorsteal;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
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
                        "§6Обман и доверие§r: /leaveorsteal classes — список классов сундуков; " +
                        "/leaveorsteal items <namespace> — предметы мода; /leaveorsteal status — статус модуля."
                    ), false);
                    return 1;
                }))
                .then(CommandManager.literal("status").executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal(
                        "§aМод загружен. Игровые матчи и арена ещё находятся в разработке."
                    ), false);
                    return 1;
                }))
                .then(CommandManager.literal("classes").executes(context -> {
                    String classes = Arrays.stream(ChestClass.values())
                        .map(c -> "§e" + c.name() + "§7 — " + c.displayName())
                        .reduce((a, b) -> a + "\n" + b).orElse("");
                    context.getSource().sendFeedback(() -> Text.literal("§6Классы сундуков:\n" + classes), false);
                    return 1;
                }))
                .then(CommandManager.literal("items")
                    .then(CommandManager.argument("namespace", StringArgumentType.word())
                        .executes(context -> {
                            String namespace = StringArgumentType.getString(context, "namespace")
                                .toLowerCase(Locale.ROOT);
                            int count = 0;
                            for (Identifier id : Registries.ITEM.getIds()) {
                                if (id.getNamespace().equals(namespace)) count++;
                            }
                            final int result = count;
                            context.getSource().sendFeedback(() -> Text.literal(
                                "§eНайдено предметов namespace '" + namespace + "': §f" + result
                            ), false);
                            return result;
                        })))
            )
        );
        LOGGER.info("Обман и доверие загружен: {} классов сундуков", ChestClass.values().length);
    }
}
