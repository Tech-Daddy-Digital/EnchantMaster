package dev.enchantmaster.network;

import dev.enchantmaster.EnchantMaster;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: open the forge UI. */
public record OpenForgeScreenPayload(boolean canForgeForOthers) implements CustomPacketPayload {
    public static final Type<OpenForgeScreenPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EnchantMaster.MODID, "open_forge_screen"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenForgeScreenPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    OpenForgeScreenPayload::canForgeForOthers,
                    OpenForgeScreenPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
