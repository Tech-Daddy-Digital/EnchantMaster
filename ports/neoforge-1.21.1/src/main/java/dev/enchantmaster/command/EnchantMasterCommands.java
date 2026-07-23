package dev.enchantmaster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.config.EnchantMasterConfig;
import dev.enchantmaster.network.ClientModSupport;
import dev.enchantmaster.network.OpenForgeScreenPayload;
import dev.enchantmaster.util.PermissionHelper;
import dev.enchantmaster.web.EnchantMasterHttpServer;
import dev.enchantmaster.web.WebAccessControl;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class EnchantMasterCommands {
    private EnchantMasterCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("enchantmaster")
                        .then(Commands.literal("web")
                                .requires(source -> {
                                    // Console always; in-game requires OP
                                    if (source.getEntity() == null) return true;
                                    return source.getEntity() instanceof ServerPlayer player
                                            && PermissionHelper.canControlWebServer(player);
                                })
                                .then(Commands.literal("start").executes(EnchantMasterCommands::startWeb))
                                .then(Commands.literal("stop").executes(EnchantMasterCommands::stopWeb))
                                .then(Commands.literal("status").executes(EnchantMasterCommands::statusWeb))
                        )
                        .then(Commands.literal("open")
                                // Only listed for OPs whose client negotiated Enchant Master channels
                                // (mod installed on that client). Players without the client mod never see it.
                                .requires(EnchantMasterCommands::canSeeOpenCommand)
                                .executes(EnchantMasterCommands::openUi)
                        )
        );
    }

    /**
     * {@code open} is hidden unless the player is OP and their client has Enchant Master
     * (optional network channel present). Uses the same check as payload send safety.
     */
    private static boolean canSeeOpenCommand(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return false;
        }
        return PermissionHelper.canUseInGameForge(player) && ClientModSupport.hasClientMod(player);
    }

    private static int openUi(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command can only be used by a player."));
            return 0;
        }
        // Defense in depth — requires() already gates visibility/execution
        if (!canSeeOpenCommand(source)) {
            source.sendFailure(Component.literal(
                    "In-game UI requires operator permission and Enchant Master installed on your client. "
                            + "Use /enchantmaster web start for the web UI if available."));
            return 0;
        }
        boolean forOthers = PermissionHelper.canForgeForOthers(player);
        if (!ClientModSupport.sendIfSupported(player, new OpenForgeScreenPayload(forOthers))) {
            source.sendFailure(Component.literal(
                    "Could not open the in-game UI (client channel unavailable)."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Opening Enchant Master…"), false);
        return 1;
    }

    private static int startWeb(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (source.getEntity() instanceof ServerPlayer player && !PermissionHelper.canControlWebServer(player)) {
            source.sendFailure(Component.literal("Only operators can start the Enchant Master web UI."));
            return 0;
        }

        String host = EnchantMasterConfig.WEB_HOST.get();
        int port = EnchantMasterConfig.WEB_PORT.getAsInt();
        boolean alreadyRunning = EnchantMasterHttpServer.isRunning();

        try {
            if (!alreadyRunning) {
                boolean started = EnchantMasterHttpServer.start(host, port);
                if (!started) {
                    source.sendFailure(Component.literal("Failed to start web UI (unknown state)."));
                    return 0;
                }
            }

            String allowance = null;
            AuditActor actor;
            if (source.getEntity() instanceof ServerPlayer player) {
                String ip = WebAccessControl.resolvePlayerIp(player);
                actor = AuditActor.command(player, ip);
                allowance = WebAccessControl.registerPlayer(player);
                if (allowance == null && EnchantMasterConfig.accessControlEnabled()) {
                    source.sendSuccess(() -> Component.literal(
                            "Web UI is " + (alreadyRunning ? "already running" : "started")
                                    + " but your connection IP could not be determined "
                                    + "(integrated/local client?). " + WebAccessControl.statusSummary()), true);
                }
            } else {
                actor = AuditActor.console();
            }

            String publicUrl = EnchantMasterConfig.publicWebUrl(host, port);
            String bindInfo = host + ":" + port;
            AuditLog.webStart(actor, alreadyRunning, publicUrl, allowance);
            final String allowMsg = allowance;
            final boolean wasRunning = alreadyRunning;
            source.sendSuccess(() -> {
                StringBuilder sb = new StringBuilder();
                if (wasRunning) {
                    sb.append("Web UI already running at ").append(publicUrl);
                    if (allowMsg != null) {
                        sb.append(" — added to whitelist: ").append(allowMsg);
                    } else if (!(source.getEntity() instanceof ServerPlayer)) {
                        sb.append(" (console; no player IP added)");
                    }
                } else {
                    sb.append("Enchant Master web UI started at ").append(publicUrl)
                            .append(" (listening on ").append(bindInfo).append(")");
                    if (allowMsg != null) {
                        sb.append(". Allowed: ").append(allowMsg);
                    } else if (!(source.getEntity() instanceof ServerPlayer)) {
                        sb.append(". Started from console — player IPs are added when OPs run start in-game.");
                    }
                }
                sb.append(". ").append(WebAccessControl.statusSummary())
                        .append(". Stop with /enchantmaster web stop.");
                return Component.literal(sb.toString());
            }, true);
            return 1;
        } catch (Exception e) {
            source.sendFailure(Component.literal("Failed to start web UI: " + e.getMessage()));
            return 0;
        }
    }

    private static int stopWeb(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (source.getEntity() instanceof ServerPlayer player && !PermissionHelper.canControlWebServer(player)) {
            source.sendFailure(Component.literal("Only operators can stop the Enchant Master web UI."));
            return 0;
        }

        AuditActor actor;
        if (source.getEntity() instanceof ServerPlayer player) {
            actor = AuditActor.command(player, WebAccessControl.resolvePlayerIp(player));
        } else {
            actor = AuditActor.console();
        }

        boolean stopped = EnchantMasterHttpServer.stopIfRunning();
        if (stopped) {
            AuditLog.webStop(actor);
            source.sendSuccess(() -> Component.literal(
                    "Enchant Master web UI stopped (IP whitelist cleared)."), true);
            return 1;
        }
        source.sendFailure(Component.literal("Web UI is not running."));
        return 0;
    }

    private static int statusWeb(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        if (EnchantMasterHttpServer.isRunning()) {
            String host = EnchantMasterHttpServer.boundHost();
            int port = EnchantMasterHttpServer.boundPort();
            String publicUrl = EnchantMasterConfig.publicWebUrl(host, port);
            source.sendSuccess(() -> Component.literal(
                    "Web UI running at " + publicUrl
                            + " (listening on " + host + ":" + port + "). "
                            + WebAccessControl.statusSummary()
                            + ". Stop with /enchantmaster web stop"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "Web UI is stopped. Would be available at "
                            + EnchantMasterConfig.publicWebUrl()
                            + " (bind " + EnchantMasterConfig.WEB_HOST.get()
                            + ":" + EnchantMasterConfig.WEB_PORT.getAsInt()
                            + "). " + WebAccessControl.statusSummary()
                            + ". Start with /enchantmaster web start"), false);
        }
        return 1;
    }
}
