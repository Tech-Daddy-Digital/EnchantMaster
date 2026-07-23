package dev.enchantmaster.forge;

import dev.enchantmaster.EnchantMasterPlugin;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.UUID;

public final class GiveService {
    private GiveService() {
    }

    public static Result forgeAndGive(ForgeRequest request, AuditActor actor) {
        ItemStackBuilder.Result built = ItemStackBuilder.build(request);
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
            String summary = stack.getType().getKey() + " x" + stack.getAmount();
            EnchantMasterPlugin.log().info("Dry-run forge ok: " + request.itemId + " -> " + summary);
            if (actor != null) {
                AuditLog.forge(actor, request, true, "Dry-run OK: " + summary, "(dry-run)", "-");
            }
            return Result.ok("Dry-run OK: built " + summary);
        }

        Player target = resolvePlayer(request);
        if (target == null) {
            if (actor != null) {
                AuditLog.forge(actor, request, false, "Target player is not online",
                        request.targetPlayerName,
                        request.targetPlayerUuid != null ? request.targetPlayerUuid.toString() : null);
            }
            return Result.error("Target player is not online");
        }

        ItemStack stack = built.stack().clone();
        HashMap<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
        if (!leftover.isEmpty()) {
            leftover.values().forEach(left ->
                    target.getWorld().dropItemNaturally(target.getLocation(), left));
        }
        target.sendMessage(Component.text("You received a forged item from Enchant Master."));
        if (actor != null) {
            AuditLog.forge(actor, request, true, "Gave forged item to " + target.getName(),
                    target.getName(), target.getUniqueId().toString());
        }
        return Result.ok("Gave forged item to " + target.getName());
    }

    private static Player resolvePlayer(ForgeRequest request) {
        if (request.targetPlayerUuid != null) {
            Player byUuid = Bukkit.getPlayer(request.targetPlayerUuid);
            if (byUuid != null) return byUuid;
        }
        if (request.targetPlayerName != null && !request.targetPlayerName.isBlank()) {
            return Bukkit.getPlayerExact(request.targetPlayerName);
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
