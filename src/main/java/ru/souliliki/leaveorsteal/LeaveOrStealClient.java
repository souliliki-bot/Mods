package ru.souliliki.leaveorsteal;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class LeaveOrStealClient implements ClientModInitializer {
    private static KeyBinding openSettingsKey;

    @Override
    public void onInitializeClient() {
        openSettingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.leave_or_steal.settings",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            "category.leave_or_steal"
        ));

        ClientPlayNetworking.registerGlobalReceiver(SettingsSyncPayload.ID, (payload, context) ->
            context.client().execute(() -> context.client().setScreen(new SettingsScreen(
                payload.arenaTeleport(), payload.restoreHealth(), payload.restoreHunger()
            )))
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openSettingsKey.wasPressed()) {
                if (client.getNetworkHandler() != null) {
                    client.getNetworkHandler().sendChatCommand("leaveorsteal settings");
                }
            }
        });
    }
}
