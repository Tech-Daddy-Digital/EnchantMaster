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
import dev.enchantmaster.EnchantMasterPlugin;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import dev.enchantmaster.forge.ForgeRequest;
import dev.enchantmaster.forge.GiveService;
import dev.enchantmaster.forge.ItemCatalog;
import dev.enchantmaster.forge.PlayerInventoryService;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class EnchantMasterHttpServer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static volatile HttpServer httpServer;
    private static volatile String boundHost;
    private static volatile int boundPort;

    private EnchantMasterHttpServer() {
    }

    public static synchronized boolean start(String host, int port) throws IOException {
        if (httpServer != null) return false;
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        Filter accessFilter = new Filter() {
            @Override
            public void doFilter(HttpExchange exchange, Chain chain) throws IOException {
                if (!WebAccessControl.isAllowed(exchange)) {
                    String ip = WebAccessControl.clientIpString(exchange);
                    String path = exchange.getRequestURI() != null ? exchange.getRequestURI().getPath() : "?";
                    AuditLog.webAccessDenied(ip, path);
                    JsonObject err = new JsonObject();
                    err.addProperty("error", "Forbidden");
                    err.addProperty("message", "Your IP is not allowed. An OP must run /enchantmaster web start.");
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
        EnchantMasterPlugin.log().info("Web UI listening on " + host + ":" + port
                + " (" + WebAccessControl.statusSummary() + ")");
        return true;
    }

    private static void mount(HttpServer server, String path, HttpHandler handler, Filter accessFilter) {
        HttpContext ctx = server.createContext(path, handler);
        ctx.getFilters().add(accessFilter);
    }

    public static synchronized boolean stopIfRunning() {
        if (httpServer == null) return false;
        httpServer.stop(0);
        httpServer = null;
        boundHost = null;
        boundPort = 0;
        WebAccessControl.clearPlayerEntries();
        EnchantMasterPlugin.log().info("Web UI stopped");
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
        if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
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
        obj.addProperty("mod", "enchantmaster");
        obj.addProperty("loader", "paper");
        obj.addProperty("serverReady", Bukkit.getServer() != null);
        sendJson(exchange, 200, obj);
    }

    private static void handleStats(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        sendJson(exchange, 200, runSync(ItemCatalog::stats));
    }

    private static void handleItems(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, String> q = query(exchange);
        int limit = parseInt(q.get("limit"), 500);
        int offset = parseInt(q.get("offset"), 0);
        JsonObject wrapper = new JsonObject();
        wrapper.add("items", runSync(() -> ItemCatalog.items(q.get("q"), q.get("namespace"), limit, offset)));
        sendJson(exchange, 200, wrapper);
    }

    private static void handleEnchantments(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, String> q = query(exchange);
        boolean override = "true".equalsIgnoreCase(q.get("override"));
        JsonObject wrapper = new JsonObject();
        wrapper.add("enchantments", runSync(() -> ItemCatalog.enchantments(q.get("q"), q.get("item"), override)));
        sendJson(exchange, 200, wrapper);
    }

    private static void handleAttributes(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        Map<String, String> q = query(exchange);
        JsonObject wrapper = new JsonObject();
        wrapper.add("attributes", runSync(() -> ItemCatalog.attributes(q.get("q"))));
        sendJson(exchange, 200, wrapper);
    }

    private static void handleMeta(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        JsonObject meta = ItemCatalog.meta();
        // web UI expects slots / operations keys in some builds — provide both
        JsonObject obj = new JsonObject();
        obj.add("slots", meta.get("equipmentSlots"));
        obj.add("operations", meta.get("attributeOperations"));
        obj.add("equipmentSlots", meta.get("equipmentSlots"));
        obj.add("attributeOperations", meta.get("attributeOperations"));
        sendJson(exchange, 200, obj);
    }

    private static void handlePlayers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("players", runSync(ItemCatalog::onlinePlayers));
        sendJson(exchange, 200, wrapper);
    }

    private static void handleAllPlayers(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("players", runSync(PlayerInventoryService::allPlayers));
        sendJson(exchange, 200, wrapper);
    }

    private static void handleInventory(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
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
            UUID uuid = UUID.fromString(uuidStr);
            sendJson(exchange, 200, runSync(() -> PlayerInventoryService.listInventory(uuid, forgeableOnly)));
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
        AuditActor actor = primaryActor(exchange);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            UUID uuid = UUID.fromString(json.get("uuid").getAsString());
            String path = json.get("path").getAsString();
            ForgeRequest request = ForgeRequest.fromJson(json);
            PlayerInventoryService.Result result = runSync(() -> PlayerInventoryService.modify(uuid, path, request, actor));
            JsonObject resp = new JsonObject();
            resp.addProperty("success", result.success());
            resp.addProperty("message", result.message());
            sendJson(exchange, result.success() ? 200 : 400, resp);
        } catch (Exception e) {
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
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/event-stream; charset=utf-8");
        headers.set("Cache-Control", "no-cache");
        headers.set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < 3600 && httpServer != null; i++) {
                var players = runSync(ItemCatalog::onlinePlayers);
                String payload = "data: " + GSON.toJson(players) + "\n\n";
                out.write(payload.getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(1000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
        }
    }

    private static void handleForge(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendMethodNotAllowed(exchange);
            return;
        }
        AuditActor actor = primaryActor(exchange);
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            ForgeRequest request = ForgeRequest.fromJson(json);
            GiveService.Result result = runSync(() -> GiveService.forgeAndGive(request, actor));
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
        sendText(exchange, 404, "No texture");
    }

    private static AuditActor primaryActor(HttpExchange exchange) {
        String ip = WebAccessControl.clientIpString(exchange);
        return AuditActor.web("web", null, ip);
    }

    private static <T> T runSync(Callable<T> task) {
        try {
            if (Bukkit.isPrimaryThread()) {
                return task.call();
            }
            Future<T> future = Bukkit.getScheduler().callSyncMethod(EnchantMasterPlugin.get(), task);
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
            if (eq < 0) map.put(urlDecode(part), "");
            else map.put(urlDecode(part.substring(0, eq)), urlDecode(part.substring(eq + 1)));
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
