package dev.enchantmaster.config;

import dev.enchantmaster.EnchantMasterPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PluginConfig {
    private final EnchantMasterPlugin plugin;
    private final String host;
    private final int port;
    private final String publicUrl;
    private final boolean accessControl;
    private final boolean allowPlayerLan;
    private final boolean allowLocalhost;
    private final boolean trustProxyHeaders;
    private final int maxOverrideLevel;
    private final List<String> allowedSubnets;

    public PluginConfig(EnchantMasterPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration c = plugin.getConfig();
        c.addDefault("web.host", "0.0.0.0");
        c.addDefault("web.port", 25570);
        c.addDefault("web.publicUrl", "");
        c.addDefault("web.accessControl", true);
        c.addDefault("web.allowPlayerLan", true);
        c.addDefault("web.allowedSubnets", List.of());
        c.addDefault("web.allowLocalhost", true);
        c.addDefault("web.trustProxyHeaders", false);
        c.addDefault("web.maxOverrideLevel", 255);
        c.options().copyDefaults(true);
        plugin.saveConfig();

        this.host = c.getString("web.host", "0.0.0.0");
        this.port = c.getInt("web.port", 25570);
        this.publicUrl = c.getString("web.publicUrl", "");
        this.accessControl = c.getBoolean("web.accessControl", true);
        this.allowPlayerLan = c.getBoolean("web.allowPlayerLan", true);
        this.allowLocalhost = c.getBoolean("web.allowLocalhost", true);
        this.trustProxyHeaders = c.getBoolean("web.trustProxyHeaders", false);
        this.maxOverrideLevel = Math.max(1, c.getInt("web.maxOverrideLevel", 255));
        this.allowedSubnets = new ArrayList<>(c.getStringList("web.allowedSubnets"));
    }

    public String host() { return host; }
    public int port() { return port; }
    public String publicUrl() { return publicUrl; }
    public boolean accessControl() { return accessControl; }
    public boolean allowPlayerLan() { return allowPlayerLan; }
    public boolean allowLocalhost() { return allowLocalhost; }
    public boolean trustProxyHeaders() { return trustProxyHeaders; }
    public int maxOverrideLevel() { return maxOverrideLevel; }
    public List<String> allowedSubnets() { return allowedSubnets; }

    public String publicWebUrl(String bindHost, int bindPort) {
        if (publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl.trim();
        }
        String h = bindHost;
        if (h == null || h.isBlank() || "0.0.0.0".equals(h) || "::".equals(h)) {
            h = "127.0.0.1";
        }
        return "http://" + h + ":" + bindPort;
    }

    public static String normalizeColor(String color) {
        if (color == null || color.isBlank()) return null;
        String c = color.trim();
        if (!c.startsWith("#")) c = "#" + c;
        return c.toUpperCase(Locale.ROOT);
    }
}
