package ru.souliliki.leaveorsteal;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

public final class LobbyScreen extends Screen {
    private final List<String> onlinePlayers;
    private final boolean canManageSettings;

    public LobbyScreen(List<String> onlinePlayers, boolean canManageSettings) {
        super(Text.literal("Обман и доверие — лобби"));
        this.onlinePlayers = List.copyOf(onlinePlayers);
        this.canManageSettings = canManageSettings;
    }

    @Override
    protected void init() {
        int center = width / 2;
        int top = Math.max(24, height / 2 - 105);

        addDrawableChild(ButtonWidget.builder(Text.literal("Создать лобби"), button ->
            sendCommand("leaveorsteal lobby create"))
            .dimensions(center - 150, top, 145, 24).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Играть с ботом"), button ->
            sendCommand("leaveorsteal bot"))
            .dimensions(center + 5, top, 145, 24).build());

        int y = top + 54;
        int maxRows = Math.min(onlinePlayers.size(), 7);
        for (int i = 0; i < maxRows; i++) {
            String name = onlinePlayers.get(i);
            if (name.equals(MinecraftClient.getInstance().getSession().getUsername())) continue;
            final String target = name;
            addDrawableChild(ButtonWidget.builder(Text.literal("Пригласить: " + name), button ->
                sendCommand("leaveorsteal invite " + target))
                .dimensions(center - 150, y, 300, 20).build());
            y += 23;
        }

        if (canManageSettings) {
            addDrawableChild(ButtonWidget.builder(Text.literal("Настройки сервера"), button ->
                sendCommand("leaveorsteal settings"))
                .dimensions(center - 150, Math.min(height - 48, y + 4), 145, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Закрыть"), button -> close())
            .dimensions(canManageSettings ? center + 5 : center - 75, Math.min(height - 48, y + 4), 145, 20).build());
    }

    private void sendCommand(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getNetworkHandler() != null) {
            client.getNetworkHandler().sendChatCommand(command);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, Math.max(8, height / 2 - 130), 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
            Text.literal("Создай приватное лобби, пригласи игрока или начни тренировочный матч с ИИ."),
            width / 2, Math.max(20, height / 2 - 116), 0xA0A0A0);
        if (onlinePlayers.size() <= 1) {
            context.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Сейчас на сервере нет других игроков для приглашения."),
                width / 2, height / 2 - 35, 0xAAAAAA);
        }
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
