package dev.enchantmaster.network;

import net.minecraft.server.level.ServerPlayer;

public final class ClientModSupport {
    private ClientModSupport() {}
    public static boolean hasClientMod(ServerPlayer player) { return false; }
    public static boolean sendOpenScreen(ServerPlayer player, boolean canForgeForOthers) { return false; }
    public static boolean sendResult(ServerPlayer player, boolean success, String message) { return false; }
}
