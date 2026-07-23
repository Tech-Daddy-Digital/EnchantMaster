package dev.enchantmaster.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.enchantmaster.EnchantMasterPlugin;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.forge.ForgeRequest;
import dev.enchantmaster.forge.GiveService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional Fabric client bridge via plugin messaging channels.
 */
public final class ClientBridge implements PluginMessageListener {
    private final EnchantMasterPlugin plugin;
    private final Set<UUID> clients = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastHello = new ConcurrentHashMap<>();

    public ClientBridge(EnchantMasterPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerOutgoingPluginChannel(plugin, EnchantMasterPlugin.CHANNEL_OPEN);
        messenger.registerOutgoingPluginChannel(plugin, EnchantMasterPlugin.CHANNEL_FORGE_RESULT);
        messenger.registerIncomingPluginChannel(plugin, EnchantMasterPlugin.CHANNEL_HELLO, this);
        messenger.registerIncomingPluginChannel(plugin, EnchantMasterPlugin.CHANNEL_FORGE, this);
    }

    public void unregister() {
        var messenger = plugin.getServer().getMessenger();
        messenger.unregisterOutgoingPluginChannel(plugin);
        messenger.unregisterIncomingPluginChannel(plugin);
        clients.clear();
    }

    public boolean hasClientMod(Player player) {
        return player != null && clients.contains(player.getUniqueId());
    }

    public boolean sendOpen(Player player, boolean canForgeForOthers) {
        if (!hasClientMod(player)) return false;
        JsonObject o = new JsonObject();
        o.addProperty("canForgeForOthers", canForgeForOthers);
        byte[] data = o.toString().getBytes(StandardCharsets.UTF_8);
        player.sendPluginMessage(plugin, EnchantMasterPlugin.CHANNEL_OPEN, data);
        return true;
    }

    public void onQuit(UUID uuid) {
        clients.remove(uuid);
        lastHello.remove(uuid);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] message) {
        if (channel.equals(EnchantMasterPlugin.CHANNEL_HELLO)) {
            clients.add(player.getUniqueId());
            lastHello.put(player.getUniqueId(), System.currentTimeMillis());
            plugin.getLogger().info("Fabric client hello from " + player.getName());
            return;
        }
        if (channel.equals(EnchantMasterPlugin.CHANNEL_FORGE)) {
            if (!player.isOp() && !player.hasPermission("enchantmaster.admin")) {
                sendResult(player, false, "No permission");
                return;
            }
            try {
                String json = new String(message, StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                ForgeRequest request = ForgeRequest.fromJson(obj);
                String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                        ? player.getAddress().getAddress().getHostAddress() : "?";
                GiveService.Result result = GiveService.forgeAndGive(request, AuditActor.player(player, ip));
                sendResult(player, result.success(), result.message());
            } catch (Exception e) {
                sendResult(player, false, "Bad forge payload: " + e.getMessage());
            }
        }
    }

    private void sendResult(Player player, boolean ok, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("message", message == null ? "" : message);
        player.sendPluginMessage(plugin, EnchantMasterPlugin.CHANNEL_FORGE_RESULT,
                o.toString().getBytes(StandardCharsets.UTF_8));
    }
}
