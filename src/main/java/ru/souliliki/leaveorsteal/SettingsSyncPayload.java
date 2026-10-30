package ru.souliliki.leaveorsteal;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

public record SettingsSyncPayload(boolean arenaTeleport, boolean restoreHealth, boolean restoreHunger,
                                  List<Integer> classWeights, List<Boolean> enabledClasses) implements CustomPayload {
    public static final Id<SettingsSyncPayload> ID =
        new Id<>(Identifier.of(LeaveOrStealMod.MOD_ID, "settings_sync"));

    public static final PacketCodec<RegistryByteBuf, SettingsSyncPayload> CODEC =
        PacketCodec.tuple(
            PacketCodecs.BOOL, SettingsSyncPayload::arenaTeleport,
            PacketCodecs.BOOL, SettingsSyncPayload::restoreHealth,
            PacketCodecs.BOOL, SettingsSyncPayload::restoreHunger,
            PacketCodecs.collection(ArrayList::new, PacketCodecs.VAR_INT), SettingsSyncPayload::classWeights,
            PacketCodecs.collection(ArrayList::new, PacketCodecs.BOOL), SettingsSyncPayload::enabledClasses,
            SettingsSyncPayload::new
        );

    public SettingsSyncPayload {
        classWeights = List.copyOf(classWeights);
        enabledClasses = List.copyOf(enabledClasses);
    }

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}
