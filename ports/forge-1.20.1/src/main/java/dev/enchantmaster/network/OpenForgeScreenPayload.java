package dev.enchantmaster.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Server → client: open in-game forge screen (client-side handler stubbed). */
public record OpenForgeScreenPayload(boolean canForgeForOthers) {
    public static void encode(OpenForgeScreenPayload msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.canForgeForOthers);
    }

    public static OpenForgeScreenPayload decode(FriendlyByteBuf buf) {
        return new OpenForgeScreenPayload(buf.readBoolean());
    }

    public static void handle(OpenForgeScreenPayload msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            // Client GUI is optional for this port; if client classes are present they can hook here.
            try {
                Class<?> screens = Class.forName("dev.enchantmaster.client.ClientForgeScreens");
                screens.getMethod("open", boolean.class).invoke(null, msg.canForgeForOthers);
            } catch (ReflectiveOperationException ignored) {
                // No client GUI on dedicated server / server-only installs
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
