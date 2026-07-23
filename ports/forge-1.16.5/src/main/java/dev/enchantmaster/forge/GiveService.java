package dev.enchantmaster.forge;

import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.UUID;

public final class GiveService {
    private GiveService() {
    }

    public static Result forgeAndGive(MinecraftServer server, ForgeRequest request) {
        return forgeAndGive(server, request, null);
    }

    public static Result forgeAndGive(MinecraftServer server, ForgeRequest request, AuditActor actor) {
        ItemStackBuilder.Result built = ItemStackBuilder.build(server, request);
        if (!built.success) {
            if (actor != null) {
                AuditLog.forge(actor, request, false, built.error,
                        request != null ? request.targetPlayerName : null,
                        request != null && request.targetPlayerUuid != null
                                ? request.targetPlayerUuid.toString() : null);
            }
            return Result.error(built.error);
        }

        if (request.dryRun) {
            ItemStack stack = built.stack;
            String summary = stack.getHoverName().getString() + " x" + stack.getCount();
            EnchantMaster.LOGGER.info("Dry-run forge ok: {} -> {}", request.itemId, summary);
            if (actor != null) {
                AuditLog.forge(actor, request, true, "Dry-run OK: " + summary, "(dry-run)", "-");
            }
            return Result.ok("Dry-run OK: built " + summary);
        }

        ServerPlayerEntity target = resolvePlayer(server, request);
        if (target == null) {
            if (actor != null) {
                AuditLog.forge(actor, request, false, "Target player is not online",
                        request.targetPlayerName,
                        request.targetPlayerUuid != null ? request.targetPlayerUuid.toString() : null);
            }
            return Result.error("Target player is not online");
        }

        ItemStack stack = built.stack.copy();
        boolean added = target.inventory.add(stack);
        if (!added || !stack.isEmpty()) {
            target.drop(stack, false);
        }

        target.sendMessage(new StringTextComponent("You received a forged item from Enchant Master."),
                target.getUUID());
        String targetName = target.getGameProfile().getName();
        String targetUuid = target.getUUID().toString();
        EnchantMaster.LOGGER.info("Forged {} for {} (uuid={})", request.itemId, targetName, targetUuid);

        if (actor != null) {
            AuditLog.forge(actor, request, true, "Gave forged item to " + targetName, targetName, targetUuid);
        }

        return Result.ok("Gave forged item to " + targetName);
    }

    private static ServerPlayerEntity resolvePlayer(MinecraftServer server, ForgeRequest request) {
        if (request.targetPlayerUuid != null) {
            ServerPlayerEntity byUuid = server.getPlayerList().getPlayer(request.targetPlayerUuid);
            if (byUuid != null) return byUuid;
        }
        if (request.targetPlayerName != null && !request.targetPlayerName.trim().isEmpty()) {
            return server.getPlayerList().getPlayerByName(request.targetPlayerName);
        }
        return null;
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result error(String message) {
            return new Result(false, message);
        }

        public boolean success() {
            return success;
        }

        public String message() {
            return message;
        }
    }
}
