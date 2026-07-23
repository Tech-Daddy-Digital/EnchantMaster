package dev.enchantmaster.audit;

import net.minecraft.entity.player.ServerPlayerEntity;

import java.util.UUID;

public final class AuditActor {
    public static final String SOURCE_CONSOLE = "console";
    public static final String SOURCE_COMMAND = "command";
    public static final String SOURCE_WEB = "web";
    public static final String SOURCE_INGAME = "ingame";
    public static final String SOURCE_UNKNOWN = "unknown";

    private final String source;
    private final String name;
    private final UUID uuid;
    private final String ip;

    public AuditActor(String source, String name, UUID uuid, String ip) {
        this.source = source;
        this.name = name;
        this.uuid = uuid;
        this.ip = ip;
    }

    public static AuditActor console() {
        return new AuditActor(SOURCE_CONSOLE, "console", null, "local");
    }

    public static AuditActor command(ServerPlayerEntity player, String ip) {
        return new AuditActor(
                SOURCE_COMMAND,
                player.getGameProfile().getName(),
                player.getUUID(),
                ip != null ? ip : "?"
        );
    }

    public static AuditActor ingame(ServerPlayerEntity player, String ip) {
        return new AuditActor(
                SOURCE_INGAME,
                player.getGameProfile().getName(),
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

    public String source() {
        return source;
    }

    public String name() {
        return name;
    }

    public UUID uuid() {
        return uuid;
    }

    public String ip() {
        return ip;
    }

    public String label() {
        String id = uuid != null ? uuid.toString() : "-";
        return source + " op=" + name + " uuid=" + id + " ip=" + ip;
    }
}
