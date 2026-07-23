package dev.enchantmaster;

import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.command.EnchantMasterCommand;
import dev.enchantmaster.config.PluginConfig;
import dev.enchantmaster.network.ClientBridge;
import dev.enchantmaster.web.EnchantMasterHttpServer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Paper server plugin for Enchant Master.
 * Web UI + forge/inventory APIs work without any client mod.
 * Optional Fabric client enables {@code /enchantmaster open}.
 */
public final class EnchantMasterPlugin extends JavaPlugin {
    public static final String CHANNEL_HELLO = "enchantmaster:hello";
    public static final String CHANNEL_OPEN = "enchantmaster:open";
    public static final String CHANNEL_FORGE = "enchantmaster:forge";
    public static final String CHANNEL_FORGE_RESULT = "enchantmaster:forge_result";

    private static EnchantMasterPlugin instance;
    private PluginConfig pluginConfig;
    private ClientBridge clientBridge;

    public static EnchantMasterPlugin get() {
        return instance;
    }

    public static Logger log() {
        return instance.getLogger();
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        pluginConfig = new PluginConfig(this);
        AuditLog.bindServerDirectory(getDataFolder().getParentFile().toPath().getParent());
        clientBridge = new ClientBridge(this);
        clientBridge.register();

        Objects.requireNonNull(getCommand("enchantmaster")).setExecutor(new EnchantMasterCommand(this));
        Objects.requireNonNull(getCommand("enchantmaster")).setTabCompleter(new EnchantMasterCommand(this));

        getLogger().info("Enchant Master " + getDescription().getVersion()
                + " enabled (Paper). Use /enchantmaster web start as OP/console.");
    }

    @Override
    public void onDisable() {
        EnchantMasterHttpServer.stopIfRunning();
        if (clientBridge != null) {
            clientBridge.unregister();
        }
        instance = null;
    }

    public PluginConfig config() {
        return pluginConfig;
    }

    public ClientBridge clientBridge() {
        return clientBridge;
    }

    public void reloadPluginConfig() {
        reloadConfig();
        pluginConfig = new PluginConfig(this);
    }
}
