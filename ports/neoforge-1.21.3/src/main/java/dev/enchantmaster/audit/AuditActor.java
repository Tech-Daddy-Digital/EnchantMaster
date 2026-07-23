package dev.enchantmaster.audit;

import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Who performed an auditable action, and from where.
 */
public record AuditActor(
        String source,
        String name,
        UUID uuid,
        String ip
) {
    public static final String SOURCE_CONSOLE = "console";
    public static final String SOURCE_COMMAND = "command";
    public static final String SOURCE_WEB = "web";
    public static final String SOURCE_INGAME = "ingame";
    public static final String SOURCE_UNKNOWN = "unknown";

    public static AuditActor console() {
        return new AuditActor(SOURCE_CONSOLE, "console", null, "local");
    }

    public static AuditActor command(ServerPlayer player, String ip) {
        return new AuditActor(
                SOURCE_COMMAND,
                player.getGameProfile().name(),
                player.getUUID(),
                ip != null ? ip : "?"
        );
    }

    public static AuditActor ingame(ServerPlayer player, String ip) {
        return new AuditActor(
                SOURCE_INGAME,
                player.getGameProfile().name(),
                player.getUUID(),
                ip != null ? ip : "?"
        );
    }

    public static AuditActor web(String name, UUID uuid, String ip) {
        return new AuditActor(
                SOURCE_WEB,
                name != null ? name : "unknown",
                uuid,
                ip != null ? ip : "?"
        );
    }

    public String label() {
        String id = uuid != null ? uuid.toString() : "-";
        return source + " op=" + name + " uuid=" + id + " ip=" + ip;
    }
}
