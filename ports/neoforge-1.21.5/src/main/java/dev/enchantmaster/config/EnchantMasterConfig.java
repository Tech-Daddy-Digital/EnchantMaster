package dev.enchantmaster.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Server config for Enchant Master ({@code config/enchantmaster-server.toml}).
 * <p>
 * <b>Upgrades:</b> NeoForge merges by key. When you install a newer JAR, existing
 * values in the file are kept; only keys that are missing receive their defaults.
 * Nothing in this class rewrites the whole file to stock defaults on upgrade.
 */
public final class EnchantMasterConfig {
    /**
     * Bump when adding/renaming config keys that need a migration note.
     * Tracked in {@code config/enchantmaster-install.properties} (not user-facing).
     */
    public static final int CONFIG_SCHEMA_VERSION = 3;

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> WEB_HOST = BUILDER
            .comment(
                    "Bind address for the embedded web UI process.",
                    "0.0.0.0 listens on all interfaces; 127.0.0.1 only accepts local/proxy connections."
            )
            .define("web.host", "0.0.0.0");

    public static final ModConfigSpec.IntValue WEB_PORT = BUILDER
            .comment(
                    "Port the embedded web UI binds to on this machine.",
                    "When using a reverse proxy, this is the backend port (proxy target), not necessarily the public port."
            )
            .defineInRange("web.port", 25570, 1, 65535);

    public static final ModConfigSpec.ConfigValue<String> WEB_PUBLIC_URL = BUILDER
            .comment(
                    "URL shown to operators when the web UI starts or when they run /enchantmaster web status.",
                    "Use this when the UI is reached via reverse proxy / different host / HTTPS / path prefix.",
                    "Examples: https://mc.example.com/enchantmaster  or  https://admin.example.com:8443",
                    "Leave empty to derive http://<host>:<port> from web.host and web.port",
                    "(0.0.0.0 / :: are shown as localhost)."
            )
            .define("web.publicUrl", "");

    public static final ModConfigSpec.BooleanValue WEB_ACCESS_CONTROL = BUILDER
            .comment(
                    "If true, only allowed IPs can use the web UI.",
                    "Players who run /enchantmaster web start are added to a temporary whitelist (their connection IP).",
                    "A second OP running start while the UI is already up adds their IP too.",
                    "Whitelist entries are removed when that player logs out, and cleared when the web UI is stopped."
            )
            .define("web.accessControl", true);

    public static final ModConfigSpec.BooleanValue WEB_ALLOW_PLAYER_LAN = BUILDER
            .comment(
                    "If true (and accessControl is on), also allow other hosts on the same LAN as a whitelisted player.",
                    "Only applies to private/link-local player IPs (e.g. 192.168.x.x → that /24).",
                    "Public internet IPs are never expanded to a subnet."
            )
            .define("web.allowPlayerLan", true);

    public static final ModConfigSpec.ConfigValue<String> WEB_ALLOWED_SUBNETS = BUILDER
            .comment(
                    "Extra CIDR subnets always allowed when accessControl is on (comma-separated).",
                    "Examples: 10.0.0.0/8, 192.168.1.0/24, 2001:db8::/32",
                    "Leave empty for none. Useful for a fixed admin office network or reverse-proxy network."
            )
            .define("web.allowedSubnets", "");

    public static final ModConfigSpec.BooleanValue WEB_ALLOW_LOCALHOST = BUILDER
            .comment("If true (and accessControl is on), always allow loopback (127.0.0.1 / ::1).")
            .define("web.allowLocalhost", true);

    public static final ModConfigSpec.BooleanValue WEB_TRUST_PROXY_HEADERS = BUILDER
            .comment(
                    "If true, use X-Forwarded-For / X-Real-IP as the client IP for access control.",
                    "Only enable when a trusted reverse proxy is the only way to reach the web UI;",
                    "otherwise clients can spoof these headers."
            )
            .define("web.trustProxyHeaders", false);

    public static final ModConfigSpec.IntValue MAX_OVERRIDE_LEVEL = BUILDER
            .comment("Maximum enchantment level allowed when override limits is enabled.")
            .defineInRange("web.maxOverrideLevel", 255, 1, 255);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private EnchantMasterConfig() {
    }

    public static boolean accessControlEnabled() {
        return WEB_ACCESS_CONTROL.get();
    }

    public static boolean allowPlayerLan() {
        return WEB_ALLOW_PLAYER_LAN.get();
    }

    public static boolean allowLocalhost() {
        return WEB_ALLOW_LOCALHOST.get();
    }

    public static boolean trustProxyHeaders() {
        return WEB_TRUST_PROXY_HEADERS.get();
    }

    public static List<String> allowedSubnets() {
        String raw = WEB_ALLOWED_SUBNETS.get();
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split("[,;\\s]+")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /**
     * URL for humans (chat/status). Prefer {@link #WEB_PUBLIC_URL} when set (proxy-friendly);
     * otherwise build from bind host/port.
     */
    public static String publicWebUrl() {
        return publicWebUrl(WEB_HOST.get(), WEB_PORT.getAsInt());
    }

    public static String publicWebUrl(String bindHost, int bindPort) {
        String configured = WEB_PUBLIC_URL.get();
        if (configured != null) {
            String trimmed = configured.trim();
            if (!trimmed.isEmpty()) {
                while (trimmed.endsWith("/") && trimmed.length() > 1) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1);
                }
                return trimmed;
            }
        }
        String host = bindHost;
        if (host == null || host.isBlank() || "0.0.0.0".equals(host) || "::".equals(host)) {
            host = "localhost";
        }
        return "http://" + host + ":" + bindPort;
    }
}
