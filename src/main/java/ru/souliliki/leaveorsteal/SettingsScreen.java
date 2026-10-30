package ru.souliliki.leaveorsteal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Locale;

public final class SettingsScreen extends Screen {
    private final boolean arenaTeleport;
    private final boolean restoreHealth;
    private final boolean restoreHunger;
    private final int roundsPerMatch;
    private final List<Integer> classWeights;
    private final List<Boolean> enabledClasses;

    public SettingsScreen(boolean arenaTeleport, boolean restoreHealth, boolean restoreHunger,
                          int roundsPerMatch, List<Integer> classWeights, List<Boolean> enabledClasses) {
        super(Text.literal("Обман и доверие — настройки"));
        this.arenaTeleport = arenaTeleport;
        this.restoreHealth = restoreHealth;
        this.restoreHunger = restoreHunger;
        this.roundsPerMatch = roundsPerMatch;
        this.classWeights = List.copyOf(classWeights);
        this.enabledClasses = List.copyOf(enabledClasses);
    }

    @Override
    protected void init() {
        int center = width / 2;
        int top = Math.max(8, height / 2 - 135);
        addDrawableChild(toggleButton(center, top, "Телепортация на арену", "arena", arenaTeleport));
        addDrawableChild(toggleButton(center, top + 23, "Восстановление здоровья", "health", restoreHealth));
        addDrawableChild(toggleButton(center, top + 46, "Восстановление голода", "hunger", restoreHunger));
        addDrawableChild(ButtonWidget.builder(Text.literal("−"), button ->
            sendCommand("leaveorsteal rounds " + Math.max(1, roundsPerMatch - 1)))
            .dimensions(center - 125, top + 69, 28, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("+"), button ->
            sendCommand("leaveorsteal rounds " + Math.min(30, roundsPerMatch + 1)))
            .dimensions(center + 97, top + 69, 28, 20).build());

        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("Количество раундов: " + roundsPerMatch + " (1–30)"),
            width / 2, top + 75, 0xFFFFFF);
        for (ChestClass chestClass : ChestClass.values()) {
            int index = chestClass.ordinal();
            int weight = index < classWeights.size() ? Math.max(0, classWeights.get(index)) : 0;
            boolean enabled = index < enabledClasses.size() && enabledClasses.get(index);
            int y = top + 106 + index * 22;
            String name = chestClass.name().toLowerCase(Locale.ROOT);

            addDrawableChild(ButtonWidget.builder(Text.literal(enabled ? "ВКЛ" : "ВЫКЛ"), button ->
                sendCommand("leaveorsteal class " + name))
                .dimensions(center - 220, y, 88, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("−"), button ->
                sendWeight(name, Math.max(0, weight - 1)))
                .dimensions(center - 125, y, 28, 20).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), button ->
                sendWeight(name, Math.min(100000, weight + 1)))
                .dimensions(center + 97, y, 28, 20).build());
        }

        int closeY = top + 106 + ChestClass.values().length * 22 + 7;
        addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), button -> close())
            .dimensions(center - 100, closeY, 200, 20).build());
    }

    private void sendWeight(String className, int weight) {
        sendCommand("leaveorsteal weight " + className + " " + weight);
    }

    private void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    private ButtonWidget toggleButton(int center, int y, String label, String key, boolean enabled) {
        String state = enabled ? "ВКЛ" : "ВЫКЛ";
        return ButtonWidget.builder(Text.literal(label + ": " + state), button ->
            sendCommand("leaveorsteal setting " + key)
        ).dimensions(center - 125, y, 250, 20).build();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, Math.max(0, height / 2 - 155), 0xFFFFFF);
        int top = Math.max(8, height / 2 - 135);
        for (ChestClass chestClass : ChestClass.values()) {
            int index = chestClass.ordinal();
            int weight = index < classWeights.size() ? Math.max(0, classWeights.get(index)) : 0;
            context.drawCenteredTextWithShadow(textRenderer,
                Text.literal(chestClass.displayName() + ": " + weight),
                width / 2, top + 112 + index * 22 + 6, 0xFFFFFF);
        }
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("Классы: ВКЛ/ВЫКЛ • Вес: −/+ • Сохранение автоматическое"),
            width / 2, height - 12, 0xA0A0A0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
