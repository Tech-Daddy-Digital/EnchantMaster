package dev.enchantmaster.util;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Permission checks that work across Minecraft 1.21.x (legacy permission levels)
 * and 26.x (PermissionSet API).
 */
public final class PermissionHelper {
    private PermissionHelper() {
    }

    /** Treats gamemaster+ / permission level 2+ as operator. */
    public static boolean isOp(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }
        // 26.x / late 1.21.11+: permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
        try {
            Object set = ServerPlayer.class.getMethod("permissions").invoke(serverPlayer);
            Class<?> permissionsCl = Class.forName("net.minecraft.server.permissions.Permissions");
            Object gamemaster = permissionsCl.getField("COMMANDS_GAMEMASTER").get(null);
            Object result = set.getClass().getMethod("hasPermission",
                    Class.forName("net.minecraft.server.permissions.Permission")).invoke(set, gamemaster);
            if (result instanceof Boolean b) {
                return b;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        // Classic: hasPermissions(2) == gamemaster (OP level 2+)
        try {
            Object result = ServerPlayer.class.getMethod("hasPermissions", int.class)
                    .invoke(serverPlayer, 2);
            if (result instanceof Boolean b) {
                return b;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        // Fallback: permission level via getPermissionLevel if present
        try {
            Object level = ServerPlayer.class.getMethod("getPermissionLevel").invoke(serverPlayer);
            if (level instanceof Integer i) {
                return i >= 2;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }

    public static boolean canUseInGameForge(Player player) {
        return isOp(player);
    }

    public static boolean canForgeForOthers(Player player) {
        return isOp(player);
    }

    public static boolean canControlWebServer(Player player) {
        return isOp(player);
    }
}
