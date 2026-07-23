package dev.enchantmaster.audit;

import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public record AuditActor(String name, UUID uuid, String ip, String kind) {
    public static AuditActor console() {
        return new AuditActor("CONSOLE", null, "console", "console");
    }

    public static AuditActor player(Player player, String ip) {
        return new AuditActor(
                player.getName(),
                player.getUniqueId(),
                ip == null ? "?" : ip,
                "player"
        );
    }

    public static AuditActor web(String name, UUID uuid, String ip) {
        return new AuditActor(name == null ? "?" : name, uuid, ip == null ? "?" : ip, "web");
    }

    public static AuditActor from(CommandSender sender, String ip) {
        if (sender instanceof ConsoleCommandSender) {
            return console();
        }
        if (sender instanceof Player p) {
            return player(p, ip);
        }
        return new AuditActor(sender.getName(), null, ip == null ? "?" : ip, "other");
    }
}
