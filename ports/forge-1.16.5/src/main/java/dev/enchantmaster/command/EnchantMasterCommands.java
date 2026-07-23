package dev.enchantmaster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.config.EnchantMasterConfig;
import dev.enchantmaster.util.PermissionHelper;
import dev.enchantmaster.web.EnchantMasterHttpServer;
import dev.enchantmaster.web.WebAccessControl;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.Entity;

public final class EnchantMasterCommands {
    private EnchantMasterCommands() {
    }

    public static void register(CommandDispatcher<CommandSource> dispatcher) {
        dispatcher.register(
                Commands.literal("enchantmaster")
                        .then(Commands.literal("web")
                                .requires(source -> {
                                    Entity entity = source.getEntity();
                                    if (entity == null) return true;
                                    return entity instanceof ServerPlayerEntity
                                            && PermissionHelper.canControlWebServer((ServerPlayerEntity) entity);
                                })
                                .then(Commands.literal("start").executes(EnchantMasterCommands::startWeb))
                                .then(Commands.literal("stop").executes(EnchantMasterCommands::stopWeb))
                                .then(Commands.literal("status").executes(EnchantMasterCommands::statusWeb))
                        )
        );
    }

    private static int startWeb(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayerEntity
                && !PermissionHelper.canControlWebServer((ServerPlayerEntity) entity)) {
            source.sendFailure(new StringTextComponent("Only operators can start the Enchant Master web UI."));
            return 0;
        }

        String host = EnchantMasterConfig.WEB_HOST.get();
        int port = EnchantMasterConfig.WEB_PORT.get();
        boolean alreadyRunning = EnchantMasterHttpServer.isRunning();

        try {
            if (!alreadyRunning) {
                boolean started = EnchantMasterHttpServer.start(host, port);
                if (!started) {
                    source.sendFailure(new StringTextComponent("Failed to start web UI (unknown state)."));
                    return 0;
                }
            }

            String allowance = null;
            AuditActor actor;
            if (entity instanceof ServerPlayerEntity) {
                ServerPlayerEntity player = (ServerPlayerEntity) entity;
                String ip = WebAccessControl.resolvePlayerIp(player);
                actor = AuditActor.command(player, ip);
                allowance = WebAccessControl.registerPlayer(player);
                if (allowance == null && EnchantMasterConfig.accessControlEnabled()) {
                    source.sendSuccess(new StringTextComponent(
                            "Web UI is " + (alreadyRunning ? "already running" : "started")
                                    + " but your connection IP could not be determined. "
                                    + WebAccessControl.statusSummary()), true);
                }
            } else {
                actor = AuditActor.console();
            }

            String publicUrl = EnchantMasterConfig.publicWebUrl(host, port);
            String bindInfo = host + ":" + port;
            AuditLog.webStart(actor, alreadyRunning, publicUrl, allowance);

            StringBuilder sb = new StringBuilder();
            if (alreadyRunning) {
                sb.append("Web UI already running at ").append(publicUrl);
                if (allowance != null) {
                    sb.append(" — added to whitelist: ").append(allowance);
                } else if (!(entity instanceof ServerPlayerEntity)) {
                    sb.append(" (console; no player IP added)");
                }
            } else {
                sb.append("Enchant Master web UI started at ").append(publicUrl)
                        .append(" (listening on ").append(bindInfo).append(")");
                if (allowance != null) {
                    sb.append(". Allowed: ").append(allowance);
                } else if (!(entity instanceof ServerPlayerEntity)) {
                    sb.append(". Started from console — player IPs are added when OPs run start in-game.");
                }
            }
            sb.append(". ").append(WebAccessControl.statusSummary())
                    .append(". Stop with /enchantmaster web stop.");
            source.sendSuccess(new StringTextComponent(sb.toString()), true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(new StringTextComponent("Failed to start web UI: " + e.getMessage()));
            return 0;
        }
    }

    private static int stopWeb(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        Entity entity = source.getEntity();
        if (entity instanceof ServerPlayerEntity
                && !PermissionHelper.canControlWebServer((ServerPlayerEntity) entity)) {
            source.sendFailure(new StringTextComponent("Only operators can stop the Enchant Master web UI."));
            return 0;
        }

        AuditActor actor;
        if (entity instanceof ServerPlayerEntity) {
            ServerPlayerEntity player = (ServerPlayerEntity) entity;
            actor = AuditActor.command(player, WebAccessControl.resolvePlayerIp(player));
        } else {
            actor = AuditActor.console();
        }

        boolean stopped = EnchantMasterHttpServer.stopIfRunning();
        if (stopped) {
            AuditLog.webStop(actor);
            source.sendSuccess(new StringTextComponent(
                    "Enchant Master web UI stopped (IP whitelist cleared)."), true);
            return 1;
        }
        source.sendFailure(new StringTextComponent("Web UI is not running."));
        return 0;
    }

    private static int statusWeb(CommandContext<CommandSource> ctx) {
        CommandSource source = ctx.getSource();
        if (EnchantMasterHttpServer.isRunning()) {
            String host = EnchantMasterHttpServer.boundHost();
            int port = EnchantMasterHttpServer.boundPort();
            String publicUrl = EnchantMasterConfig.publicWebUrl(host, port);
            source.sendSuccess(new StringTextComponent(
                    "Web UI running at " + publicUrl
                            + " (listening on " + host + ":" + port + "). "
                            + WebAccessControl.statusSummary()
                            + ". Stop with /enchantmaster web stop"), false);
        } else {
            source.sendSuccess(new StringTextComponent(
                    "Web UI is stopped. Would be available at "
                            + EnchantMasterConfig.publicWebUrl()
                            + " (bind " + EnchantMasterConfig.WEB_HOST.get()
                            + ":" + EnchantMasterConfig.WEB_PORT.get()
                            + "). " + WebAccessControl.statusSummary()
                            + ". Start with /enchantmaster web start"), false);
        }
        return 1;
    }
}
