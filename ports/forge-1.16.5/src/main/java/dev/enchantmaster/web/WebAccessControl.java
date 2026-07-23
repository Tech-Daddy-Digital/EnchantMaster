package dev.enchantmaster.web;

import com.sun.net.httpserver.HttpExchange;
import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.config.EnchantMasterConfig;
import net.minecraft.entity.player.ServerPlayerEntity;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
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
    public static final class WhitelistEntry {
        public final UUID uuid;
        public final String name;
        public final String ip;

        public WhitelistEntry(UUID uuid, String name, String ip) {
            this.uuid = uuid;
            this.name = name;
            this.ip = ip;
        }

        public UUID uuid() { return uuid; }
        public String name() { return name; }
        public String ip() { return ip; }
    }

    private static final Map<UUID, WhitelistEntry> PLAYER_ENTRIES = new ConcurrentHashMap<UUID, WhitelistEntry>();

    private WebAccessControl() {
    }

    public static void clearPlayerEntries() {
        PLAYER_ENTRIES.clear();
        EnchantMaster.LOGGER.info("Web UI access whitelist cleared");
    }

    public static void removePlayer(UUID uuid) {
        if (uuid == null) return;
        WhitelistEntry removed = PLAYER_ENTRIES.remove(uuid);
        if (removed != null) {
            EnchantMaster.LOGGER.info(
                    "Removed {} ({}) from web UI whitelist (was {})",
                    removed.name, uuid, removed.ip
            );
        }
    }

    public static String registerPlayer(ServerPlayerEntity player) {
        if (player == null) return null;
        String ip = resolvePlayerIp(player);
        if (ip == null || ip.trim().isEmpty()) {
            return null;
        }
        WhitelistEntry entry = new WhitelistEntry(
                player.getUUID(),
                player.getGameProfile().getName(),
                ip
        );
        PLAYER_ENTRIES.put(player.getUUID(), entry);
        EnchantMaster.LOGGER.info(
                "Web UI whitelist: {} ({}) → {}",
                entry.name, entry.uuid, entry.ip
        );
        return describeAllowance(ip);
    }

    public static Set<String> listedPlayerIps() {
        LinkedHashSet<String> ips = new LinkedHashSet<String>();
        for (WhitelistEntry e : PLAYER_ENTRIES.values()) {
            ips.add(e.ip);
        }
        return ips;
    }

    public static int playerEntryCount() {
        return PLAYER_ENTRIES.size();
    }

    public static List<WhitelistEntry> snapshotEntries() {
        return new ArrayList<WhitelistEntry>(PLAYER_ENTRIES.values());
    }

    public static String statusSummary() {
        if (!EnchantMasterConfig.accessControlEnabled()) {
            return "access control OFF (any IP)";
        }
        StringBuilder sb = new StringBuilder("access control ON");
        sb.append("; players=").append(PLAYER_ENTRIES.size());
        if (!PLAYER_ENTRIES.isEmpty()) {
            List<String> parts = new ArrayList<String>();
            for (WhitelistEntry e : PLAYER_ENTRIES.values()) {
                parts.add(e.name + "@" + e.ip);
            }
            sb.append(" [").append(String.join(", ", parts)).append("]");
        }
        if (EnchantMasterConfig.allowPlayerLan()) {
            sb.append("; same-LAN allowed");
        }
        List<String> subnets = EnchantMasterConfig.allowedSubnets();
        if (!subnets.isEmpty()) {
            sb.append("; subnets=").append(subnets);
        }
        if (EnchantMasterConfig.allowLocalhost()) {
            sb.append("; localhost allowed");
        }
        return sb.toString();
    }

    public static boolean isAllowed(HttpExchange exchange) {
        if (!EnchantMasterConfig.accessControlEnabled()) {
            return true;
        }
        InetAddress client = resolveClientAddress(exchange);
        if (client == null) {
            return false;
        }
        if (EnchantMasterConfig.allowLocalhost() && isLoopback(client)) {
            return true;
        }
        for (String cidr : EnchantMasterConfig.allowedSubnets()) {
            if (matchesCidr(client, cidr)) {
                return true;
            }
        }
        for (WhitelistEntry entry : PLAYER_ENTRIES.values()) {
            if (sameHost(client, entry.ip)) {
                return true;
            }
            if (EnchantMasterConfig.allowPlayerLan() && sameLan(client, entry.ip)) {
                return true;
            }
        }
        return false;
    }

    public static List<AuditActor> resolveActors(HttpExchange exchange) {
        String ip = clientIpString(exchange);
        List<AuditActor> exact = new ArrayList<AuditActor>();
        List<AuditActor> lan = new ArrayList<AuditActor>();
        InetAddress client = parseHost(ip);

        for (WhitelistEntry entry : PLAYER_ENTRIES.values()) {
            if (client != null && sameHost(client, entry.ip)) {
                exact.add(AuditActor.web(entry.name, entry.uuid, ip));
            } else if (client != null
                    && EnchantMasterConfig.allowPlayerLan()
                    && sameLan(client, entry.ip)) {
                lan.add(AuditActor.web(entry.name, entry.uuid, ip));
            }
        }
        if (!exact.isEmpty()) return exact;
        if (!lan.isEmpty()) return lan;
        List<AuditActor> fallback = new ArrayList<AuditActor>();
        fallback.add(AuditActor.web("unattributed", null, ip != null ? ip : "?"));
        return fallback;
    }

    public static AuditActor primaryActor(HttpExchange exchange) {
        List<AuditActor> actors = resolveActors(exchange);
        if (actors.size() == 1) return actors.get(0);
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < actors.size(); i++) {
            if (i > 0) names.append('+');
            names.append(actors.get(i).name());
        }
        UUID uuid = actors.get(0).uuid();
        String ip = actors.get(0).ip();
        return AuditActor.web(names.toString(), uuid, ip);
    }

    public static String clientIpString(HttpExchange exchange) {
        InetAddress addr = resolveClientAddress(exchange);
        if (addr == null) return "?";
        return normalizeInet(addr);
    }

    public static String describeAllowance(String playerIp) {
        StringBuilder sb = new StringBuilder("your IP ").append(playerIp);
        if (EnchantMasterConfig.allowPlayerLan()) {
            String lan = lanCidrFor(playerIp);
            if (lan != null) {
                sb.append(" + LAN ").append(lan);
            }
        }
        List<String> subnets = EnchantMasterConfig.allowedSubnets();
        if (!subnets.isEmpty()) {
            sb.append(" + configured subnets ").append(subnets);
        }
        return sb.toString();
    }

    public static String resolvePlayerIp(ServerPlayerEntity player) {
        try {
            // Prefer getIpAddress when available
            try {
                String direct = player.getIpAddress();
                if (direct != null && !direct.trim().isEmpty()) {
                    return stripZone(direct);
                }
            } catch (Exception ignored) {
            }
            SocketAddress sa = player.connection.connection.getRemoteAddress();
            return normalizeAddress(sa);
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Could not resolve player IP: {}", e.toString());
            return null;
        }
    }

    private static InetAddress resolveClientAddress(HttpExchange exchange) {
        try {
            if (EnchantMasterConfig.trustProxyHeaders()) {
                String forwarded = firstHeader(exchange, "X-Forwarded-For");
                if (forwarded != null && !forwarded.trim().isEmpty()) {
                    String first = forwarded.split(",")[0].trim();
                    InetAddress parsed = parseHost(first);
                    if (parsed != null) return parsed;
                }
                String realIp = firstHeader(exchange, "X-Real-IP");
                if (realIp != null && !realIp.trim().isEmpty()) {
                    InetAddress parsed = parseHost(realIp.trim());
                    if (parsed != null) return parsed;
                }
            }
            InetSocketAddress remote = exchange.getRemoteAddress();
            if (remote != null && remote.getAddress() != null) {
                return remote.getAddress();
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Client address resolve failed: {}", e.toString());
        }
        return null;
    }

    private static String firstHeader(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
    }

    private static String normalizeAddress(SocketAddress sa) {
        if (!(sa instanceof InetSocketAddress)) {
            return sa != null ? sa.toString() : null;
        }
        InetSocketAddress isa = (InetSocketAddress) sa;
        InetAddress addr = isa.getAddress();
        if (addr == null) {
            String host = isa.getHostString();
            return host != null ? stripZone(host) : null;
        }
        return normalizeInet(addr);
    }

    private static String normalizeInet(InetAddress addr) {
        if (addr instanceof Inet6Address) {
            Inet6Address v6 = (Inet6Address) addr;
            if (v6.isIPv4CompatibleAddress()) {
                byte[] b = v6.getAddress();
                try {
                    return InetAddress.getByAddress(new byte[]{b[12], b[13], b[14], b[15]}).getHostAddress();
                } catch (UnknownHostException ignored) {
                }
            }
        }
        String host = addr.getHostAddress();
        if (host != null && host.startsWith("::ffff:")) {
            return host.substring("::ffff:".length());
        }
        return stripZone(host);
    }

    private static String stripZone(String host) {
        if (host == null) return null;
        int pct = host.indexOf('%');
        return pct >= 0 ? host.substring(0, pct) : host;
    }

    private static InetAddress parseHost(String host) {
        try {
            return InetAddress.getByName(stripZone(host));
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean sameHost(InetAddress client, String registeredIp) {
        InetAddress reg = parseHost(registeredIp);
        if (reg == null) return false;
        return client.equals(reg) || normalizeInet(client).equalsIgnoreCase(normalizeInet(reg));
    }

    private static boolean isLoopback(InetAddress addr) {
        return addr.isLoopbackAddress();
    }

    private static boolean sameLan(InetAddress client, String registeredIp) {
        InetAddress reg = parseHost(registeredIp);
        if (reg == null) return false;
        if (!isPrivateOrLinkLocal(reg) || !isPrivateOrLinkLocal(client)) {
            return false;
        }
        String cidr = lanCidrFor(normalizeInet(reg));
        return cidr != null && matchesCidr(client, cidr);
    }

    private static boolean isPrivateOrLinkLocal(InetAddress addr) {
        return addr.isSiteLocalAddress() || addr.isLinkLocalAddress() || addr.isLoopbackAddress();
    }

    private static String lanCidrFor(String ip) {
        InetAddress addr = parseHost(ip);
        if (addr == null || !isPrivateOrLinkLocal(addr)) return null;
        if (addr instanceof Inet4Address) {
            byte[] b = addr.getAddress();
            return String.format(Locale.ROOT, "%d.%d.%d.0/24",
                    Integer.valueOf(b[0] & 0xFF), Integer.valueOf(b[1] & 0xFF), Integer.valueOf(b[2] & 0xFF));
        }
        if (addr instanceof Inet6Address) {
            byte[] b = addr.getAddress();
            try {
                byte[] net = new byte[16];
                System.arraycopy(b, 0, net, 0, 8);
                return InetAddress.getByAddress(net).getHostAddress() + "/64";
            } catch (UnknownHostException e) {
                return null;
            }
        }
        return null;
    }

    static boolean matchesCidr(InetAddress address, String cidr) {
        if (cidr == null || cidr.trim().isEmpty() || address == null) return false;
        String raw = cidr.trim();
        try {
            int slash = raw.indexOf('/');
            if (slash < 0) {
                InetAddress single = InetAddress.getByName(stripZone(raw));
                return address.equals(single);
            }
            String ipPart = raw.substring(0, slash).trim();
            int prefix = Integer.parseInt(raw.substring(slash + 1).trim());
            InetAddress network = InetAddress.getByName(stripZone(ipPart));
            byte[] addrBytes = address.getAddress();
            byte[] netBytes = network.getAddress();
            if (addrBytes.length != netBytes.length) {
                addrBytes = canonicalBytes(address);
                netBytes = canonicalBytes(network);
                if (addrBytes.length != netBytes.length) return false;
            }
            int maxBits = addrBytes.length * 8;
            if (prefix < 0 || prefix > maxBits) return false;
            int fullBytes = prefix / 8;
            int remBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != netBytes[i]) return false;
            }
            if (remBits == 0) return true;
            int mask = 0xFF << (8 - remBits);
            return (addrBytes[fullBytes] & mask) == (netBytes[fullBytes] & mask);
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Bad CIDR {}: {}", cidr, e.toString());
            return false;
        }
    }

    private static byte[] canonicalBytes(InetAddress addr) {
        if (addr instanceof Inet6Address) {
            String host = normalizeInet(addr);
            try {
                return InetAddress.getByName(host).getAddress();
            } catch (UnknownHostException e) {
                return addr.getAddress();
            }
        }
        return addr.getAddress();
    }
}
