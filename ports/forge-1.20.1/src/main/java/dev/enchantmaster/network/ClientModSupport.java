package dev.enchantmaster.network;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;

/**
 * Helpers for optional client networking on Forge 1.20.1 SimpleChannel.
 */
public final class ClientModSupport {
    private ClientModSupport() {
    }

    /**
     * Best-effort: on Forge SimpleChannel we cannot perfectly know if the client
     * installed this mod without handshake tracking. For this port we treat
     * {@code /enchantmaster open} as available only when the connection has the
     * channel registered on both sides — which is true for clients with the mod.
     * <p>
     * Vanilla clients still join fine (optional channel acceptance).
     */
    public static boolean hasClientMod(ServerPlayer player) {
        if (player == null || player.connection == null || !ModNetwork.isReady()) {
            return false;
        }
        try {
            // Forge tracks presence via NetworkRegistry; if client didn't register
            // our channel name it won't be in the connection's channels map.
            var channels = net.minecraftforge.network.NetworkHooks.getConnectionData(player.connection.connection);
            if (channels == null) return false;
            return channels.getChannels().containsKey(ModNetwork.CHANNEL_NAME);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean sendOpenScreen(ServerPlayer player, boolean canForgeForOthers) {
        if (!hasClientMod(player) || !ModNetwork.isReady()) {
            return false;
        }
        ModNetwork.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new OpenForgeScreenPayload(canForgeForOthers)
        );
        return true;
    }

    public static boolean sendResult(ServerPlayer player, boolean success, String message) {
        if (!hasClientMod(player) || !ModNetwork.isReady()) {
            return false;
        }
        ModNetwork.channel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new ForgeResultPayload(success, message)
        );
        return true;
    }
}
