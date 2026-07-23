package dev.enchantmaster.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Helpers for optional client networking. Enchant Master payloads are registered as
 * optional so players without the mod can join a server that has it. Sending a
 * clientbound payload when the client did not negotiate the channel throws and can
 * break the connection — always check first.
 */
public final class ClientModSupport {
    private ClientModSupport() {
    }

    /** True if this player’s client negotiated Enchant Master play channels. */
    public static boolean hasClientMod(ServerPlayer player) {
        if (player == null || player.connection == null) {
            return false;
        }
        // Any of our clientbound channels is enough; they are registered together as optional.
        return NetworkRegistry.hasChannel(player.connection, OpenForgeScreenPayload.TYPE.id());
    }

    /**
     * Send a clientbound payload only if the client supports it.
     *
     * @return true if sent, false if the client lacks the channel
     */
    public static boolean sendIfSupported(ServerPlayer player, CustomPacketPayload payload) {
        if (!hasClientMod(player)) {
            return false;
        }
        PacketDistributor.sendToPlayer(player, payload);
        return true;
    }
}
