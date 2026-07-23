package dev.enchantmaster.forge;

import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

public final class GiveService {
    private GiveService() {
    }

    public static Result forgeAndGive(MinecraftServer server, ForgeRequest request) {
        return forgeAndGive(server, request, null);
    }

    public static Result forgeAndGive(MinecraftServer server, ForgeRequest request, AuditActor actor) {
        ItemStackBuilder.Result built = ItemStackBuilder.build(server, request);
        if (!built.success()) {
            if (actor != null) {
                AuditLog.forge(actor, request, false, built.error(),
                        request != null ? request.targetPlayerName : null,
                        request != null && request.targetPlayerUuid != null
                                ? request.targetPlayerUuid.toString() : null);
            }
            return Result.error(built.error());
        }

        if (request.dryRun) {
            ItemStack stack = built.stack();
            String summary = stack.getHoverName().getString()
                    + " x" + stack.getCount()
                    + " nbt=" + (stack.hasTag() ? stack.getTag().getAllKeys().size() : 0);
            EnchantMaster.LOGGER.info("Dry-run forge ok: {} -> {}", request.itemId, summary);
            if (actor != null) {
                AuditLog.forge(actor, request, true, "Dry-run OK: " + summary, "(dry-run)", "-");
            }
            return Result.ok("Dry-run OK: built " + summary);
        }

        ServerPlayer target = resolvePlayer(server, request);
        if (target == null) {
            if (actor != null) {
                AuditLog.forge(actor, request, false, "Target player is not online",
                        request.targetPlayerName,
                        request.targetPlayerUuid != null ? request.targetPlayerUuid.toString() : null);
            }
            return Result.error("Target player is not online");
        }

        ItemStack stack = built.stack().copy();
        boolean added = target.getInventory().add(stack);
        if (!added || !stack.isEmpty()) {
            target.drop(stack, false);
        }

        target.sendSystemMessage(Component.literal("You received a forged item from Enchant Master."));
        String targetName = target.getGameProfile().getName();
        String targetUuid = target.getUUID().toString();
        EnchantMaster.LOGGER.info("Forged {} for {} (uuid={})", request.itemId, targetName, targetUuid);

        if (actor != null) {
            AuditLog.forge(actor, request, true, "Gave forged item to " + targetName, targetName, targetUuid);
        }

        return Result.ok("Gave forged item to " + targetName);
    }

    private static ServerPlayer resolvePlayer(MinecraftServer server, ForgeRequest request) {
        if (request.targetPlayerUuid != null) {
            ServerPlayer byUuid = server.getPlayerList().getPlayer(request.targetPlayerUuid);
            if (byUuid != null) return byUuid;
        }
        if (request.targetPlayerName != null && !request.targetPlayerName.isBlank()) {
            return server.getPlayerList().getPlayerByName(request.targetPlayerName);
        }
        return null;
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result error(String message) {
            return new Result(false, message);
        }
    }
}
