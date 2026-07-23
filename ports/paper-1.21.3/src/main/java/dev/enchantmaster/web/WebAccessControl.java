package dev.enchantmaster.web;

import com.sun.net.httpserver.HttpExchange;
import dev.enchantmaster.EnchantMasterPlugin;
import org.bukkit.entity.Player;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class WebAccessControl {
    public record WhitelistEntry(UUID uuid, String name, String ip) {
    }

    private static final Map<UUID, WhitelistEntry> PLAYER_ENTRIES = new ConcurrentHashMap<>();

    private WebAccessControl() {
    }

    public static void clearPlayerEntries() {
        PLAYER_ENTRIES.clear();
        EnchantMasterPlugin.log().info("Web UI access whitelist cleared");
    }

    public static void removePlayer(UUID uuid) {
        if (uuid == null) return;
        PLAYER_ENTRIES.remove(uuid);
    }

    public static String registerPlayer(Player player) {
        if (player == null) return null;
        String ip = resolvePlayerIp(player);
        if (ip == null || ip.isBlank()) return null;
        PLAYER_ENTRIES.put(player.getUniqueId(), new WhitelistEntry(player.getUniqueId(), player.getName(), ip));
        EnchantMasterPlugin.log().info("Web UI whitelist: " + player.getName() + " → " + ip);
        return describeAllowance(ip);
    }

    public static String resolvePlayerIp(Player player) {
        try {
            InetSocketAddress addr = player.getAddress();
            if (addr == null || addr.getAddress() == null) return null;
            return addr.getAddress().getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isAllowed(HttpExchange exchange) {
        var cfg = EnchantMasterPlugin.get().config();
        if (!cfg.accessControl()) return true;
        String ip = clientIpString(exchange);
        if (ip == null) return false;
        if (cfg.allowLocalhost() && isLoopback(ip)) return true;
        for (WhitelistEntry e : PLAYER_ENTRIES.values()) {
            if (ipEquals(e.ip(), ip)) return true;
            if (cfg.allowPlayerLan() && sameLan24(e.ip(), ip)) return true;
        }
        for (String subnet : cfg.allowedSubnets()) {
            if (matchesCidr(ip, subnet)) return true;
        }
        return false;
    }

    public static String clientIpString(HttpExchange exchange) {
        var cfg = EnchantMasterPlugin.get().config();
        if (cfg.trustProxyHeaders()) {
            String xff = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
            String xri = exchange.getRequestHeaders().getFirst("X-Real-IP");
            if (xri != null && !xri.isBlank()) return xri.trim();
        }
        InetSocketAddress remote = exchange.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return null;
        return remote.getAddress().getHostAddress();
    }

    public static String statusSummary() {
        var cfg = EnchantMasterPlugin.get().config();
        if (!cfg.accessControl()) return "access control OFF (any IP)";
        StringBuilder sb = new StringBuilder("access control ON; players=").append(PLAYER_ENTRIES.size());
        if (cfg.allowPlayerLan()) sb.append("; same-LAN allowed");
        if (cfg.allowLocalhost()) sb.append("; localhost allowed");
        return sb.toString();
    }

    public static int playerEntryCount() {
        return PLAYER_ENTRIES.size();
    }

    private static boolean isLoopback(String ip) {
        try {
            return InetAddress.getByName(ip).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return "127.0.0.1".equals(ip) || "::1".equals(ip);
        }
    }

    private static boolean ipEquals(String a, String b) {
        if (a == null || b == null) return false;
        return a.equals(b) || a.equals(stripZone(b)) || stripZone(a).equals(b);
    }

    private static String stripZone(String ip) {
        int i = ip.indexOf('%');
        return i >= 0 ? ip.substring(0, i) : ip;
    }

    private static boolean sameLan24(String a, String b) {
        try {
            InetAddress ia = InetAddress.getByName(stripZone(a));
            InetAddress ib = InetAddress.getByName(stripZone(b));
            if (!(ia instanceof Inet4Address) || !(ib instanceof Inet4Address)) return false;
            byte[] aa = ia.getAddress();
            byte[] bb = ib.getAddress();
            return aa[0] == bb[0] && aa[1] == bb[1] && aa[2] == bb[2];
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean matchesCidr(String ip, String cidr) {
        if (cidr == null || cidr.isBlank()) return false;
        try {
            String[] parts = cidr.trim().split("/");
            InetAddress net = InetAddress.getByName(parts[0]);
            InetAddress addr = InetAddress.getByName(stripZone(ip));
            int prefix = parts.length > 1 ? Integer.parseInt(parts[1]) : (net instanceof Inet6Address ? 128 : 32);
            byte[] n = net.getAddress();
            byte[] a = addr.getAddress();
            if (n.length != a.length) return false;
            int full = prefix / 8;
            int rem = prefix % 8;
            for (int i = 0; i < full; i++) if (n[i] != a[i]) return false;
            if (rem == 0) return true;
            int mask = 0xFF << (8 - rem);
            return (n[full] & mask) == (a[full] & mask);
        } catch (Exception e) {
            return false;
        }
    }

    private static String describeAllowance(String ip) {
        return "whitelisted IP " + ip + (EnchantMasterPlugin.get().config().allowPlayerLan() ? " (+ same /24 LAN)" : "");
    }
}
