package dev.enchantmaster.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Server config for Enchant Master ({@code config/enchantmaster-server.toml}).
 */
public final class EnchantMasterConfig {
    public static final int CONFIG_SCHEMA_VERSION = 3;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> WEB_HOST = BUILDER
            .comment(
                    "Bind address for the embedded web UI process.",
                    "0.0.0.0 listens on all interfaces; 127.0.0.1 only accepts local/proxy connections."
            )
            .define("web.host", "0.0.0.0");

    public static final ForgeConfigSpec.IntValue WEB_PORT = BUILDER
            .comment(
                    "Port the embedded web UI binds to on this machine.",
                    "When using a reverse proxy, this is the backend port (proxy target)."
            )
            .defineInRange("web.port", 25570, 1, 65535);

    public static final ForgeConfigSpec.ConfigValue<String> WEB_PUBLIC_URL = BUILDER
            .comment(
                    "URL shown to operators when the web UI starts.",
                    "Leave empty to derive http://<host>:<port> from web.host and web.port."
            )
            .define("web.publicUrl", "");

    public static final ForgeConfigSpec.BooleanValue WEB_ACCESS_CONTROL = BUILDER
            .comment(
                    "If true, only allowed IPs can use the web UI.",
                    "Players who run /enchantmaster web start are added to a temporary whitelist."
            )
            .define("web.accessControl", true);

    public static final ForgeConfigSpec.BooleanValue WEB_ALLOW_PLAYER_LAN = BUILDER
            .comment(
                    "If true (and accessControl is on), also allow other hosts on the same LAN as a whitelisted player."
            )
            .define("web.allowPlayerLan", true);

    public static final ForgeConfigSpec.ConfigValue<String> WEB_ALLOWED_SUBNETS = BUILDER
            .comment(
                    "Extra CIDR subnets always allowed when accessControl is on (comma-separated)."
            )
            .define("web.allowedSubnets", "");

    public static final ForgeConfigSpec.BooleanValue WEB_ALLOW_LOCALHOST = BUILDER
            .comment("If true (and accessControl is on), always allow loopback (127.0.0.1 / ::1).")
            .define("web.allowLocalhost", true);

    public static final ForgeConfigSpec.BooleanValue WEB_TRUST_PROXY_HEADERS = BUILDER
            .comment(
                    "If true, use X-Forwarded-For / X-Real-IP as the client IP for access control."
            )
            .define("web.trustProxyHeaders", false);

    public static final ForgeConfigSpec.IntValue MAX_OVERRIDE_LEVEL = BUILDER
            .comment("Maximum enchantment level allowed when override limits is enabled.")
            .defineInRange("web.maxOverrideLevel", 255, 1, 255);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

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
        List<String> out = new ArrayList<String>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        String[] parts = raw.split("[,;\\s]+");
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (!p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    public static String publicWebUrl() {
        return publicWebUrl(WEB_HOST.get(), WEB_PORT.get());
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
        if (host == null || host.trim().isEmpty() || "0.0.0.0".equals(host) || "::".equals(host)) {
            host = "localhost";
        }
        return "http://" + host + ":" + bindPort;
    }
}
