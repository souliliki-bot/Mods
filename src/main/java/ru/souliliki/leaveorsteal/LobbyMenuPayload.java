package ru.souliliki.leaveorsteal;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record LobbyMenuPayload(List<String> onlinePlayers, boolean canManageSettings) implements CustomPayload {
    public static final Id<LobbyMenuPayload> ID =
        new Id<>(Identifier.of(LeaveOrStealMod.MOD_ID, "lobby_menu"));

    public static final PacketCodec<RegistryByteBuf, LobbyMenuPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.collection(ArrayList::new, PacketCodecs.STRING), LobbyMenuPayload::onlinePlayers,
            PacketCodecs.BOOL, LobbyMenuPayload::canManageSettings,
            LobbyMenuPayload::new
        );

    public LobbyMenuPayload {
        onlinePlayers = List.copyOf(onlinePlayers);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
