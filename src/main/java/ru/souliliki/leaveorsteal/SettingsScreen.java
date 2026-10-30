package ru.souliliki.leaveorsteal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

public final class SettingsScreen extends Screen {
    private final boolean arenaTeleport;
    private final boolean restoreHealth;
    private final boolean restoreHunger;

    public SettingsScreen(boolean arenaTeleport, boolean restoreHealth, boolean restoreHunger) {
        super(Text.literal("Обман и доверие — настройки"));
        this.arenaTeleport = arenaTeleport;
        this.restoreHealth = restoreHealth;
        this.restoreHunger = restoreHunger;
    }

    @Override
    protected void init() {
        int center = width / 2;
        int top = height / 2 - 55;
        addDrawableChild(toggleButton(center, top, "Телепортация на арену", "arena", arenaTeleport));
        addDrawableChild(toggleButton(center, top + 28, "Восстановление здоровья", "health", restoreHealth));
        addDrawableChild(toggleButton(center, top + 56, "Восстановление голода", "hunger", restoreHunger));
        addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), button -> close())
            .dimensions(center - 100, top + 96, 200, 20).build());
    }

    private ButtonWidget toggleButton(int center, int y, String label, String key, boolean enabled) {
        String state = enabled ? "ВКЛ" : "ВЫКЛ";
        return ButtonWidget.builder(Text.literal(label + ": " + state), button -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendChatCommand("leaveorsteal setting " + key);
            }
        }).dimensions(center - 125, y, 250, 20).build();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 85, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("Изменения сохраняются на сервере автоматически."),
            width / 2, height / 2 + 70, 0xA0A0A0);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
