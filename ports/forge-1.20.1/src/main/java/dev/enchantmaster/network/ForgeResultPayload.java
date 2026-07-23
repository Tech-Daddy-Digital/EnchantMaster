package dev.enchantmaster.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server → client: forge result for in-game UI. */
public record ForgeResultPayload(boolean success, String message) {
    public static void encode(ForgeResultPayload msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.success);
        buf.writeUtf(msg.message == null ? "" : msg.message, 32767);
    }

    public static ForgeResultPayload decode(FriendlyByteBuf buf) {
        return new ForgeResultPayload(buf.readBoolean(), buf.readUtf(32767));
    }

    public static void handle(ForgeResultPayload msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class<?> screens = Class.forName("dev.enchantmaster.client.ClientForgeScreens");
                screens.getMethod("onForgeResult", boolean.class, String.class)
                        .invoke(null, msg.success, msg.message);
            } catch (ReflectiveOperationException ignored) {
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
