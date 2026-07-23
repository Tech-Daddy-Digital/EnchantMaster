package dev.enchantmaster.util;

import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;

public final class PermissionHelper {
    private PermissionHelper() {
    }

    /** Treats permission level 2+ as operator. */
    public static boolean isOp(PlayerEntity player) {
        if (!(player instanceof ServerPlayerEntity)) {
            return false;
        }
        ServerPlayerEntity serverPlayer = (ServerPlayerEntity) player;
        return serverPlayer.hasPermissions(2);
    }

    public static boolean canUseInGameForge(PlayerEntity player) {
        return isOp(player);
    }

    public static boolean canForgeForOthers(PlayerEntity player) {
        return isOp(player);
    }

    public static boolean canControlWebServer(PlayerEntity player) {
        return isOp(player);
    }
}
