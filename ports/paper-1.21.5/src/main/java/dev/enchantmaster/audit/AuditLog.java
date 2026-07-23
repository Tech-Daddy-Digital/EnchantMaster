package dev.enchantmaster.audit;

import dev.enchantmaster.EnchantMasterPlugin;
import dev.enchantmaster.forge.ForgeRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

public final class AuditLog {
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(ZoneOffset.UTC);
    private static final ReentrantLock FILE_LOCK = new ReentrantLock();
    private static volatile Path auditFile;

    private AuditLog() {
    }

    public static void bindServerDirectory(Path serverDir) {
        if (serverDir == null) {
            auditFile = null;
            return;
        }
        try {
            Path logs = serverDir.resolve("logs");
            Files.createDirectories(logs);
            auditFile = logs.resolve("enchantmaster-audit.log");
            if (!Files.isRegularFile(auditFile)) {
                Files.writeString(auditFile,
                        "# Enchant Master audit log (UTC). One event per line.\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            EnchantMasterPlugin.log().warning("Could not open audit log: " + e);
            auditFile = null;
        }
    }

    public static void webStart(AuditActor actor, boolean alreadyRunning, String publicUrl, String allowance) {
        event("WEB_START", actor, "alreadyRunning=" + alreadyRunning
                + " url=" + safe(publicUrl) + " allowance=" + safe(allowance));
    }

    public static void webStop(AuditActor actor) {
        event("WEB_STOP", actor, "web UI stopped; whitelist cleared");
    }

    public static void webAccessDenied(String ip, String path) {
        event("WEB_DENIED", AuditActor.web("?", null, ip), "path=" + safe(path));
    }

    public static void forge(AuditActor actor, ForgeRequest request, boolean success, String message,
                             String targetName, String targetUuid) {
        String enchants = request == null || request.enchantments == null ? ""
                : request.enchantments.stream()
                .map(e -> e.id() + ":" + e.level())
                .collect(Collectors.joining(","));
        event("FORGE", actor,
                "ok=" + success
                        + " item=" + safe(request == null ? null : request.itemId)
                        + " dryRun=" + (request != null && request.dryRun)
                        + " target=" + safe(targetName)
                        + " targetUuid=" + safe(targetUuid)
                        + " enchants=[" + enchants + "]"
                        + " msg=" + safe(message));
    }

    public static void inventoryModify(AuditActor actor, boolean success, String path,
                                       String targetName, String targetUuid, String message) {
        event("INVENTORY_MODIFY", actor,
                "ok=" + success
                        + " path=" + safe(path)
                        + " target=" + safe(targetName)
                        + " targetUuid=" + safe(targetUuid)
                        + " msg=" + safe(message));
    }

    private static void event(String type, AuditActor actor, String detail) {
        String line = TS.format(Instant.now())
                + " type=" + type
                + " actor=" + (actor == null ? "?" : safe(actor.name()))
                + " actorUuid=" + (actor == null || actor.uuid() == null ? "-" : actor.uuid())
                + " actorIp=" + (actor == null ? "?" : safe(actor.ip()))
                + " " + detail;
        EnchantMasterPlugin.log().info("[AUDIT] " + line);
        Path file = auditFile;
        if (file == null) return;
        FILE_LOCK.lock();
        try {
            Files.writeString(file, line + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            EnchantMasterPlugin.log().warning("Audit write failed: " + e);
        } finally {
            FILE_LOCK.unlock();
        }
    }

    private static String safe(String s) {
        if (s == null) return "-";
        return s.replace('\n', ' ').replace('\r', ' ');
    }
}
