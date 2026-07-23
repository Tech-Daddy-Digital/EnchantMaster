package dev.enchantmaster.network;

import dev.enchantmaster.EnchantMaster;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server → client: forge result message. */
public record ForgeResultPayload(boolean success, String message) implements CustomPacketPayload {
    public static final Type<ForgeResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(EnchantMaster.MODID, "forge_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ForgeResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL,
                    ForgeResultPayload::success,
                    ByteBufCodecs.STRING_UTF8,
                    ForgeResultPayload::message,
                    ForgeResultPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
