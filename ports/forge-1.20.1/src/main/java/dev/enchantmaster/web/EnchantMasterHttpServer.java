package dev.enchantmaster.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.config.EnchantMasterConfig;
import dev.enchantmaster.forge.ForgeRequest;
import dev.enchantmaster.forge.GiveService;
import dev.enchantmaster.forge.ItemCatalog;
import dev.enchantmaster.forge.PlayerInventoryService;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Embedded admin web UI. Optional IP access control (player whitelist / LAN / subnets).
 * Started/stopped via /enchantmaster web commands; does not auto-start.
 */
public final class EnchantMasterHttpServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final AtomicReference<MinecraftServer> SERVER = new AtomicReference<>();
    private static volatile HttpServer httpServer;
    private static volatile String boundHost;
    private static volatile int boundPort;

    private EnchantMasterHttpServer() {
    }

    public static void bindServer(MinecraftServer server) {
        SERVER.set(server);
    }

    public static synchronized boolean start(String host, int port) throws IOException {
        if (httpServer != null) {
            return false;
        }
        if (SERVER.get() == null) {
            throw new IllegalStateException("Minecraft server is not available");
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        Filter accessFilter = new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                if (!WebAccessControl.isAllowed(exchange)) {
                    String ip = WebAccessControl.clientIpString(exchange);
                    String path = exchange.getRequestURI() != null
                            ? exchange.getRequestURI().getPath() : "?";
                    AuditLog.webAccessDenied(ip, path);
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Forbidden");
                    err.addProperty("message", "Your IP is not allowed to use Enchant Master web UI. "
                            + "An operator must run /enchantmaster web start from the game (or console) "
                            + "so their IP / LAN is whitelisted.");
                    sendJson(exchange, 403, err);
                    return;
                }
                chain.doFilter(exchange);
            }

            @Override
            public String description() {
                return "EnchantMaster IP access control";
            }
        };
        mount(server, "/", EnchantMasterHttpServer::handleRoot, accessFilter);
        mount(server, "/api/health", EnchantMasterHttpServer::handleHealth, accessFilter);
        mount(server, "/api/stats", EnchantMasterHttpServer::handleStats, accessFilter);
        mount(server, "/api/items", EnchantMasterHttpServer::handleItems, accessFilter);
        mount(server, "/api/enchantments", EnchantMasterHttpServer::handleEnchantments, accessFilter);
        mount(server, "/api/attributes", EnchantMasterHttpServer::handleAttributes, accessFilter);
        mount(server, "/api/meta", EnchantMasterHttpServer::handleMeta, accessFilter);
        mount(server, "/api/players", EnchantMasterHttpServer::handlePlayers, accessFilter);
        mount(server, "/api/players/stream", EnchantMasterHttpServer::handlePlayersStream, accessFilter);
        mount(server, "/api/players/all", EnchantMasterHttpServer::handleAllPlayers, accessFilter);
        mount(server, "/api/inventory", EnchantMasterHttpServer::handleInventory, accessFilter);
        mount(server, "/api/inventory/modify", EnchantMasterHttpServer::handleInventoryModify, accessFilter);
        mount(server, "/api/forge", EnchantMasterHttpServer::handleForge, accessFilter);
        mount(server, "/api/assets/item/", EnchantMasterHttpServer::handleItemAsset, accessFilter);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "enchantmaster-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        httpServer = server;
        boundHost = host;
        boundPort = port;
        EnchantMaster.LOGGER.info(
                "Enchant Master web UI listening on {}:{} (public URL: {}; {})",
                host,
                port,
                EnchantMasterConfig.publicWebUrl(host, port),
                WebAccessControl.statusSummary()
        );
        return true;
    }

    private static void mount(HttpServer server, String path, HttpHandler handler, Filter accessFilter) {
        HttpContext ctx = server.createContext(path, handler);
        List<Filter> filters = ctx.getFilters();
        filters.add(accessFilter);
    }

    public static synchronized boolean stopIfRunning() {
        if (httpServer == null) {
            return false;
        }
        httpServer.stop(0);
        httpServer = null;
        boundHost = null;
        boundPort = 0;
        WebAccessControl.clearPlayerEntries();
        EnchantMaster.LOGGER.info("Enchant Master web UI stopped");
        return true;
    }

    public static boolean isRunning() {
        return httpServer != null;
    }

    public static String boundHost() {
        return boundHost;
    }

    public static int boundPort() {
        return boundPort;
    }

    private static void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if (path == null || path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        // prevent path traversal
        if (path.contains("..")) {
            sendText(exchange, 400, "Bad path");
            return;
        }

        String resourcePath = "assets/enchantmaster/web" + path;
        try (InputStream in = EnchantMasterHttpServer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                sendText(exchange, 404, "Not found");
                return;
            }
            byte[] data = in.readAllBytes();
            Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", contentType(path));
            headers.set("Cache-Control", "no-cache");
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(data);
            }
        }
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        JsonObject obj = new JsonObject();
        obj.addProperty("ok", true);
        obj.addProperty("mod", EnchantMaster.MODID);
        MinecraftServer mc = SERVER.get();
        obj.addProperty("serverReady", mc != null);
        sendJson(exchange, 200, obj);
    }

    private static void handleStats(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        JsonObject stats = runOnServer(mc, () -> ItemCatalog.catalogStats(mc));
        sendJson(exchange, 200, stats);
    }

    private static void handleItems(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        Map<String, String> q = query(exchange);
        int limit = parseInt(q.get("limit"), 500);
        int offset = parseInt(q.get("offset"), 0);
        var items = runOnServer(mc, () -> ItemCatalog.listItems(mc, q.get("q"), q.get("namespace"), limit, offset));
        JsonObject wrapper = new JsonObject();
        wrapper.add("items", items);
        sendJson(exchange, 200, wrapper);
    }

    private static void handleEnchantments(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        Map<String, String> q = query(exchange);
        boolean override = "true".equalsIgnoreCase(q.get("override"));
        var enchants = runOnServer(mc, () -> ItemCatalog.listEnchantments(mc, q.get("q"), q.get("item"), override));
        JsonObject wrapper = new JsonObject();
        wrapper.add("enchantments", enchants);
        sendJson(exchange, 200, wrapper);
    }

    private static void handleAttributes(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        Map<String, String> q = query(exchange);

        // Relevant mode: attributes for a specific item + chosen enchantments
        // GET /api/attributes?relevant=true&item=minecraft:diamond_sword&enchants=minecraft:sharpness:5,minecraft:unbreaking:3
        if ("true".equalsIgnoreCase(q.get("relevant"))) {
            String itemId = q.get("item");
            java.util.List<ItemCatalog.EnchantLevel> levels = new java.util.ArrayList<>();
            String enchants = q.get("enchants");
            if (enchants != null && !enchants.isBlank()) {
                for (String part : enchants.split(",")) {
                    String p = part.trim();
                    if (p.isEmpty()) continue;
                    int colon = p.lastIndexOf(':');
                    // id is namespace:path, level is last :number — prefer last colon only if followed by digits
                    int lastColon = p.lastIndexOf(':');
                    if (lastColon > 0 && lastColon < p.length() - 1) {
                        String maybeLevel = p.substring(lastColon + 1);
                        if (maybeLevel.matches("\\d+")) {
                            String id = p.substring(0, lastColon);
                            // Handle minecraft:sharpness:5 → id minecraft:sharpness
                            // But id already has one colon. lastIndexOf is correct for trailing level.
                            levels.add(new ItemCatalog.EnchantLevel(id, Integer.parseInt(maybeLevel)));
                            continue;
                        }
                    }
                    levels.add(new ItemCatalog.EnchantLevel(p, 1));
                }
            }
            var attrs = runOnServer(mc, () ->
                    ItemCatalog.listRelevantAttributes(mc.registryAccess(), itemId, levels, q.get("q")));
            JsonObject wrapper = new JsonObject();
            wrapper.add("attributes", attrs);
            sendJson(exchange, 200, wrapper);
            return;
        }

        var attrs = runOnServer(mc, () -> ItemCatalog.listAttributes(q.get("q")));
        JsonObject wrapper = new JsonObject();
        wrapper.add("attributes", attrs);
        sendJson(exchange, 200, wrapper);
    }

    private static void handleMeta(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        JsonObject obj = new JsonObject();
        obj.add("slots", ItemCatalog.equipmentSlots());
        obj.add("operations", ItemCatalog.attributeOperations());
        sendJson(exchange, 200, obj);
    }

    private static void handlePlayers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        var players = runOnServer(mc, () -> ItemCatalog.listPlayers(mc));
        JsonObject wrapper = new JsonObject();
        wrapper.add("players", players);
        sendJson(exchange, 200, wrapper);
    }

    private static void handleAllPlayers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        var players = runOnServer(mc, () -> PlayerInventoryService.listKnownPlayers(mc));
        JsonObject wrapper = new JsonObject();
        wrapper.add("players", players);
        sendJson(exchange, 200, wrapper);
    }

    private static void handleInventory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        Map<String, String> q = query(exchange);
        String uuidStr = q.get("uuid");
        if (uuidStr == null || uuidStr.isBlank()) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "uuid is required");
            sendJson(exchange, 400, err);
            return;
        }
        boolean forgeableOnly = !"false".equalsIgnoreCase(q.get("forgeableOnly"));
        try {
            java.util.UUID uuid = java.util.UUID.fromString(uuidStr);
            JsonObject inv = runOnServer(mc, () -> PlayerInventoryService.getInventory(mc, uuid, forgeableOnly));
            sendJson(exchange, 200, inv);
        } catch (Exception e) {
            JsonObject err = new JsonObject();
            err.addProperty("error", e.getMessage());
            sendJson(exchange, 400, err);
        }
    }

    private static void handleInventoryModify(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;
        AuditActor actor = WebAccessControl.primaryActor(exchange);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            java.util.UUID uuid = java.util.UUID.fromString(json.get("uuid").getAsString());
            String path = json.get("path").getAsString();
            ForgeRequest request = ForgeRequest.fromJson(json);
            PlayerInventoryService.Result result = runOnServer(mc,
                    () -> PlayerInventoryService.modifyItem(mc, uuid, path, request, actor));
            JsonObject resp = new JsonObject();
            resp.addProperty("success", result.success());
            resp.addProperty("message", result.message());
            sendJson(exchange, result.success() ? 200 : 400, resp);
        } catch (Exception e) {
            AuditLog.inventoryModify(actor, "?", "?", "?", null, false, "Invalid request: " + e.getMessage());
            JsonObject resp = new JsonObject();
            resp.addProperty("success", false);
            resp.addProperty("message", "Invalid request: " + e.getMessage());
            sendJson(exchange, 400, resp);
        }
    }

    private static void handlePlayersStream(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;

        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < 3600 && httpServer != null; i++) {
                MinecraftServer current = SERVER.get();
                if (current == null) break;
                var players = runOnServer(current, () -> ItemCatalog.listPlayers(current));
                String payload = "data: " + GSON.toJson(players) + "\n\n";
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // client disconnected
        }
    }

    private static void handleForge(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        MinecraftServer mc = requireServer(exchange);
        if (mc == null) return;

        AuditActor actor = WebAccessControl.primaryActor(exchange);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            ForgeRequest request = ForgeRequest.fromJson(json);
            GiveService.Result result = runOnServer(mc, () -> GiveService.forgeAndGive(mc, request, actor));
            JsonObject resp = new JsonObject();
            resp.addProperty("success", result.success());
            resp.addProperty("message", result.message());
            sendJson(exchange, result.success() ? 200 : 400, resp);
        } catch (Exception e) {
            AuditLog.forge(actor, null, false, "Invalid request: " + e.getMessage(), null, null);
            JsonObject resp = new JsonObject();
            resp.addProperty("success", false);
            resp.addProperty("message", "Invalid request: " + e.getMessage());
            sendJson(exchange, 400, resp);
        }
    }

    private static void handleItemAsset(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        // /api/assets/item/{ns}/{path}.png
        String prefix = "/api/assets/item/";
        if (!path.startsWith(prefix) || !path.endsWith(".png")) {
            sendText(exchange, 404, "Not found");
            return;
        }
        String rest = path.substring(prefix.length(), path.length() - 4);
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            sendText(exchange, 404, "Not found");
            return;
        }
        String namespace = rest.substring(0, slash);
        String itemPath = rest.substring(slash + 1);
        if (namespace.contains("..") || itemPath.contains("..")) {
            sendText(exchange, 400, "Bad path");
            return;
        }

        byte[] png = ItemTextureResolver.resolvePng(namespace, itemPath);
        if (png == null) {
            // 1x1 transparent PNG fallback is still 404 so UI can show placeholder
            sendText(exchange, 404, "No texture");
            return;
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "image/png");
        headers.set("Cache-Control", "public, max-age=300");
        exchange.sendResponseHeaders(200, png.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(png);
        }
    }

    private static MinecraftServer requireServer(HttpExchange exchange) throws IOException {
        MinecraftServer mc = SERVER.get();
        if (mc == null) {
            JsonObject err = new JsonObject();
            err.addProperty("error", "Minecraft server not ready");
            sendJson(exchange, 503, err);
            return null;
        }
        return mc;
    }

    private static <T> T runOnServer(MinecraftServer server, java.util.function.Supplier<T> task) {
        if (server.isSameThread()) {
            return task.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Server task failed: " + e.getMessage(), e);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> map = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return map;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                map.put(urlDecode(part), "");
            } else {
                map.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1)));
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isBlank()) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".json")) return "application/json; charset=utf-8";
        return "application/octet-stream";
    }

    private static void sendJson(HttpExchange exchange, int code, Object body) throws IOException {
        byte[] data = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        headers.set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    private static void sendText(HttpExchange exchange, int code, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(code, data.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(data);
        }
    }

    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendText(exchange, 405, "Method not allowed");
    }
}
