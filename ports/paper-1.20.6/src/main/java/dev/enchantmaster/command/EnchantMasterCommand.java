package dev.enchantmaster.command;

import dev.enchantmaster.EnchantMasterPlugin;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.forge.PlayerInventoryService;
import dev.enchantmaster.web.EnchantMasterHttpServer;
import dev.enchantmaster.web.WebAccessControl;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EnchantMasterCommand implements CommandExecutor, TabCompleter, Listener {
    private final EnchantMasterPlugin plugin;

    public EnchantMasterCommand(EnchantMasterPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /enchantmaster <web|open>", NamedTextColor.YELLOW));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("web")) {
            return handleWeb(sender, args);
        }
        if (sub.equals("open")) {
            return handleOpen(sender);
        }
        sender.sendMessage(Component.text("Unknown subcommand. Use web or open.", NamedTextColor.RED));
        return true;
    }

    private boolean handleWeb(CommandSender sender, String[] args) {
        if (!canWeb(sender)) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /enchantmaster web <start|stop|status>", NamedTextColor.YELLOW));
            return true;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String ip = sender instanceof Player p ? WebAccessControl.resolvePlayerIp(p) : "console";
        AuditActor actor = AuditActor.from(sender, ip);

        switch (action) {
            case "start" -> {
                if (EnchantMasterHttpServer.isRunning()) {
                    sender.sendMessage(Component.text("Web UI already running at "
                            + publicUrl(), NamedTextColor.YELLOW));
                    AuditLog.webStart(actor, true, publicUrl(), WebAccessControl.statusSummary());
                    return true;
                }
                try {
                    var cfg = plugin.config();
                    EnchantMasterHttpServer.start(cfg.host(), cfg.port());
                    String allowance = "console";
                    if (sender instanceof Player p) {
                        allowance = WebAccessControl.registerPlayer(p);
                        if (allowance == null) allowance = "no IP resolved";
                    }
                    sender.sendMessage(Component.text("Enchant Master web UI started at "
                            + publicUrl() + " (" + WebAccessControl.statusSummary() + ")", NamedTextColor.GREEN));
                    AuditLog.webStart(actor, false, publicUrl(), allowance);
                } catch (Exception e) {
                    sender.sendMessage(Component.text("Failed to start web UI: " + e.getMessage(), NamedTextColor.RED));
                }
            }
            case "stop" -> {
                boolean stopped = EnchantMasterHttpServer.stopIfRunning();
                sender.sendMessage(Component.text(stopped ? "Web UI stopped." : "Web UI was not running.",
                        stopped ? NamedTextColor.GREEN : NamedTextColor.YELLOW));
                if (stopped) AuditLog.webStop(actor);
            }
            case "status" -> {
                if (EnchantMasterHttpServer.isRunning()) {
                    sender.sendMessage(Component.text("Web UI running at " + publicUrl()
                            + " — " + WebAccessControl.statusSummary(), NamedTextColor.GREEN));
                } else {
                    sender.sendMessage(Component.text("Web UI is stopped.", NamedTextColor.YELLOW));
                }
            }
            default -> sender.sendMessage(Component.text("Usage: /enchantmaster web <start|stop|status>", NamedTextColor.YELLOW));
        }
        return true;
    }

    private boolean handleOpen(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
            return true;
        }
        if (!canOpen(player)) {
            // hide availability when no client — match Forge behavior messaging
            if (!plugin.clientBridge().hasClientMod(player)) {
                player.sendMessage(Component.text(
                        "In-game UI requires the Enchant Master Fabric client mod.", NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            }
            return true;
        }
        boolean forOthers = player.isOp() || player.hasPermission("enchantmaster.admin");
        if (!plugin.clientBridge().sendOpen(player, forOthers)) {
            player.sendMessage(Component.text(
                    "Could not open the in-game UI (client channel unavailable).", NamedTextColor.RED));
            return true;
        }
        player.sendMessage(Component.text("Opening Enchant Master forge UI…", NamedTextColor.GREEN));
        return true;
    }

    private boolean canWeb(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) return true;
        return sender.hasPermission("enchantmaster.web")
                || sender.hasPermission("enchantmaster.admin")
                || sender.isOp();
    }

    private boolean canOpen(Player player) {
        boolean perm = player.hasPermission("enchantmaster.open")
                || player.hasPermission("enchantmaster.admin")
                || player.isOp();
        return perm && plugin.clientBridge().hasClientMod(player);
    }

    private String publicUrl() {
        var cfg = plugin.config();
        return cfg.publicWebUrl(EnchantMasterHttpServer.boundHost(), EnchantMasterHttpServer.boundPort());
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, @NotNull String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("web", "open")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("web")) {
            for (String s : List.of("start", "stop", "status")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
        }
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        WebAccessControl.removePlayer(event.getPlayer().getUniqueId());
        plugin.clientBridge().onQuit(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerInventoryService.applyCacheOnJoin(event.getPlayer());
    }
}
