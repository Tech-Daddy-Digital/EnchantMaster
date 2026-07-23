package dev.enchantmaster.audit;

import com.mojang.logging.LogUtils;
import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.forge.ForgeRequest;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Append-only audit trail for Enchant Master admin actions.
 * Writes to the main mod logger and {@code logs/enchantmaster-audit.log}.
 */
public final class AuditLog {
    private static final Logger LOG = LogUtils.getLogger();
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
            EnchantMaster.LOGGER.warn("Could not open audit log file: {}", e.toString());
            auditFile = null;
        }
    }

    public static void webStart(AuditActor actor, boolean alreadyRunning, String publicUrl, String allowance) {
        event("WEB_START",
                actor,
                "alreadyRunning=" + alreadyRunning
                        + " url=" + safe(publicUrl)
                        + " allowance=" + safe(allowance));
    }

    public static void webStop(AuditActor actor) {
        event("WEB_STOP", actor, "web UI stopped; whitelist cleared");
    }

    public static void webAccessDenied(String ip, String path) {
        event("WEB_DENIED",
                AuditActor.web("?", null, ip),
                "path=" + safe(path));
    }

    public static void forge(
            AuditActor actor,
            ForgeRequest request,
            boolean success,
            String message,
            String targetName,
            String targetUuid
    ) {
        event("FORGE",
                actor,
                "success=" + success
                        + " item=" + safe(request != null ? request.itemId : null)
                        + " target=" + safe(targetName)
                        + " targetUuid=" + safe(targetUuid)
                        + " dryRun=" + (request != null && request.dryRun)
                        + " override=" + (request != null && request.overrideLimits)
                        + " enchants=" + enchantSummary(request)
                        + " attrs=" + attrSummary(request)
                        + " name=" + nameSummary(request)
                        + " msg=" + safe(message));
    }

    public static void inventoryModify(
            AuditActor actor,
            String targetName,
            String targetUuid,
            String path,
            ForgeRequest request,
            boolean success,
            String message
    ) {
        event("INVENTORY_MODIFY",
                actor,
                "success=" + success
                        + " target=" + safe(targetName)
                        + " targetUuid=" + safe(targetUuid)
                        + " slotPath=" + safe(path)
                        + " item=" + safe(request != null ? request.itemId : null)
                        + " override=" + (request != null && request.overrideLimits)
                        + " enchants=" + enchantSummary(request)
                        + " attrs=" + attrSummary(request)
                        + " name=" + nameSummary(request)
                        + " msg=" + safe(message));
    }

    private static String enchantSummary(ForgeRequest request) {
        if (request == null || request.enchantments == null || request.enchantments.isEmpty()) {
            return "[]";
        }
        return request.enchantments.stream()
                .map(e -> e.id() + ":" + e.level())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String attrSummary(ForgeRequest request) {
        if (request == null || request.attributes == null || request.attributes.isEmpty()) {
            return "[]";
        }
        return request.attributes.stream()
                .map(a -> a.id() + "=" + a.amount())
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String nameSummary(ForgeRequest request) {
        if (request == null || request.name == null || request.name.text() == null) {
            return "-";
        }
        String t = request.name.text().trim();
        return t.isEmpty() ? "-" : quote(t);
    }

    private static void event(String type, AuditActor actor, String details) {
        String line = TS.format(Instant.now())
                + " [" + type + "] "
                + (actor != null ? actor.label() : "source=?")
                + " | " + details;
        // Dedicated logger name makes log4j/logback filtering easy
        LOG.info("[AUDIT] {}", line);
        EnchantMaster.LOGGER.info("[AUDIT] {}", line);
        appendFile(line);
    }

    private static void appendFile(String line) {
        Path file = auditFile;
        if (file == null) return;
        FILE_LOCK.lock();
        try {
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            EnchantMaster.LOGGER.debug("Audit file write failed: {}", e.toString());
        } finally {
            FILE_LOCK.unlock();
        }
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) return "-";
        return s.replace('\n', ' ').replace('\r', ' ');
    }

    private static String quote(String s) {
        return "\"" + safe(s).replace("\"", "'") + "\"";
    }

    /** Human-readable multi-op attribution for a web client IP. */
    public static String formatOperators(List<AuditActor> actors) {
        if (actors == null || actors.isEmpty()) return "unattributed";
        return actors.stream()
                .map(a -> a.name() + "/" + (a.uuid() != null ? a.uuid().toString() : "-"))
                .collect(Collectors.joining("+"));
    }
}
