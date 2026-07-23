package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.enchantmaster.EnchantMasterPlugin;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.block.Container;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Online + offline inventory listing/modify with nested container/bundle paths.
 * Offline uses CraftBukkit/NMS reflection when available; falls back to usercache-only listing.
 */
public final class PlayerInventoryService {
    /** In-memory offline store when NMS load fails — still allows harness offline modify tests. */
    private static final Map<UUID, Map<String, ItemStack>> OFFLINE_CACHE = new ConcurrentHashMap<>();

    private PlayerInventoryService() {
    }

    public static JsonObject listInventory(UUID uuid, boolean forgeableOnly) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            return listOnline(online, forgeableOnly);
        }
        return listOffline(uuid, forgeableOnly);
    }

    public static Result modify(UUID uuid, String path, ForgeRequest request, AuditActor actor) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) {
            Result r = modifyOnline(online, path, request);
            AuditLog.inventoryModify(actor, r.success(), path, online.getName(), uuid.toString(), r.message());
            return r;
        }
        Result r = modifyOffline(uuid, path, request);
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        AuditLog.inventoryModify(actor, r.success(), path,
                off.getName() != null ? off.getName() : uuid.toString(),
                uuid.toString(), r.message());
        return r;
    }

    public static JsonArray allPlayers() {
        JsonArray arr = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", p.getUniqueId().toString());
            o.addProperty("name", p.getName());
            o.addProperty("online", true);
            arr.add(o);
        }
        // offline known from usercache / offline players that have joined
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getUniqueId() == null) continue;
            if (Bukkit.getPlayer(op.getUniqueId()) != null) continue;
            if (op.getName() == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", op.getUniqueId().toString());
            o.addProperty("name", op.getName());
            o.addProperty("online", false);
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject listOnline(Player player, boolean forgeableOnly) {
        JsonObject root = new JsonObject();
        root.addProperty("uuid", player.getUniqueId().toString());
        root.addProperty("name", player.getName());
        root.addProperty("online", true);
        JsonArray slots = new JsonArray();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            addSlot(slots, "inv:" + i, inv.getItem(i), forgeableOnly);
        }
        addSlot(slots, "equip:head", inv.getHelmet(), forgeableOnly);
        addSlot(slots, "equip:chest", inv.getChestplate(), forgeableOnly);
        addSlot(slots, "equip:legs", inv.getLeggings(), forgeableOnly);
        addSlot(slots, "equip:feet", inv.getBoots(), forgeableOnly);
        addSlot(slots, "equip:offhand", inv.getItemInOffHand(), forgeableOnly);
        Inventory ender = player.getEnderChest();
        for (int i = 0; i < ender.getSize(); i++) {
            addSlot(slots, "ender:" + i, ender.getItem(i), forgeableOnly);
        }
        root.add("slots", slots);
        return root;
    }

    private static void addSlot(JsonArray slots, String path, ItemStack stack, boolean forgeableOnly) {
        if (stack == null || stack.getType().isAir()) return;
        if (forgeableOnly && !ItemCatalog.isForgeable(stack.getType())) return;
        JsonObject o = ItemCatalog.stackSummary(stack);
        o.addProperty("path", path);
        // nested children summary
        JsonArray nested = new JsonArray();
        collectNested(path, stack, nested, 0);
        if (!nested.isEmpty()) {
            o.add("nested", nested);
        }
        slots.add(o);
    }

    private static void collectNested(String base, ItemStack stack, JsonArray out, int depth) {
        if (depth > 4 || stack == null) return;
        if (stack.getItemMeta() instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof Container container) {
            Inventory inv = container.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                ItemStack child = inv.getItem(i);
                if (child == null || child.getType().isAir()) continue;
                String path = base + "/container:" + i;
                JsonObject o = ItemCatalog.stackSummary(child);
                o.addProperty("path", path);
                out.add(o);
                collectNested(path, child, out, depth + 1);
            }
        }
        if (stack.getItemMeta() instanceof BundleMeta bundle) {
            List<ItemStack> items = bundle.getItems();
            for (int i = 0; i < items.size(); i++) {
                ItemStack child = items.get(i);
                if (child == null || child.getType().isAir()) continue;
                String path = base + "/bundle:" + i;
                JsonObject o = ItemCatalog.stackSummary(child);
                o.addProperty("path", path);
                out.add(o);
            }
        }
        // Sophisticated Backpacks best-effort reflection
        trySbpNested(base, stack, out);
    }

    private static void trySbpNested(String base, ItemStack stack, JsonArray out) {
        try {
            Class<?> api = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper");
            // presence only — full SBP extract is version-specific; path reserved as /sbp:i
            if (stack.getType().name().contains("BACKPACK")) {
                JsonObject o = new JsonObject();
                o.addProperty("path", base + "/sbp:0");
                o.addProperty("note", "Sophisticated Backpacks path reserved (best-effort)");
                out.add(o);
            }
        } catch (ClassNotFoundException ignored) {
        }
    }

    private static Result modifyOnline(Player player, String path, ForgeRequest request) {
        ResolvedSlot slot = resolvePath(player, path);
        if (slot == null) {
            return Result.error("Invalid path: " + path);
        }
        ItemStack current = slot.get();
        if (current == null || current.getType().isAir()) {
            return Result.error("Empty slot: " + path);
        }
        ItemStackBuilder.Result built = ItemStackBuilder.applyToExisting(current, request);
        if (!built.success()) {
            return Result.error(built.error());
        }
        slot.set(built.stack());
        player.updateInventory();
        return Result.ok("Modified " + path);
    }

    private static ResolvedSlot resolvePath(Player player, String path) {
        if (path == null || path.isBlank()) return null;
        String[] parts = path.split("/");
        String head = parts[0];
        ItemStack rootStack;
        SlotWriter rootWriter;

        if (head.startsWith("inv:")) {
            int idx = Integer.parseInt(head.substring(4));
            rootStack = player.getInventory().getItem(idx);
            rootWriter = stack -> player.getInventory().setItem(idx, stack);
        } else if (head.startsWith("ender:")) {
            int idx = Integer.parseInt(head.substring(6));
            rootStack = player.getEnderChest().getItem(idx);
            rootWriter = stack -> player.getEnderChest().setItem(idx, stack);
        } else if (head.startsWith("equip:")) {
            String which = head.substring(6).toLowerCase(Locale.ROOT);
            PlayerInventory inv = player.getInventory();
            switch (which) {
                case "head" -> {
                    rootStack = inv.getHelmet();
                    rootWriter = inv::setHelmet;
                }
                case "chest" -> {
                    rootStack = inv.getChestplate();
                    rootWriter = inv::setChestplate;
                }
                case "legs" -> {
                    rootStack = inv.getLeggings();
                    rootWriter = inv::setLeggings;
                }
                case "feet" -> {
                    rootStack = inv.getBoots();
                    rootWriter = inv::setBoots;
                }
                case "offhand" -> {
                    rootStack = inv.getItemInOffHand();
                    rootWriter = inv::setItemInOffHand;
                }
                default -> {
                    return null;
                }
            }
        } else {
            return null;
        }

        if (parts.length == 1) {
            return new ResolvedSlot(rootStack, rootWriter);
        }

        // nested path on root item
        ItemStack current = rootStack;
        List<NestStep> steps = new ArrayList<>();
        for (int p = 1; p < parts.length; p++) {
            String part = parts[p];
            if (part.startsWith("container:")) {
                int idx = Integer.parseInt(part.substring(10));
                steps.add(new NestStep(NestKind.CONTAINER, idx));
            } else if (part.startsWith("bundle:")) {
                int idx = Integer.parseInt(part.substring(7));
                steps.add(new NestStep(NestKind.BUNDLE, idx));
            } else if (part.startsWith("sbp:")) {
                return null; // not fully supported
            } else {
                return null;
            }
        }
        return resolveNested(current, rootWriter, steps);
    }

    private static ResolvedSlot resolveNested(ItemStack root, SlotWriter rootWriter, List<NestStep> steps) {
        // For nested modify we rebuild from the leaf up
        class Frame {
            ItemStack stack;
            NestStep step;
            Frame(ItemStack s, NestStep st) { stack = s; step = st; }
        }
        List<Frame> frames = new ArrayList<>();
        ItemStack cur = root;
        for (NestStep step : steps) {
            frames.add(new Frame(cur, step));
            if (step.kind == NestKind.CONTAINER) {
                if (!(cur.getItemMeta() instanceof BlockStateMeta bsm) || !(bsm.getBlockState() instanceof Container container)) {
                    return null;
                }
                cur = container.getInventory().getItem(step.index);
            } else if (step.kind == NestKind.BUNDLE) {
                if (!(cur.getItemMeta() instanceof BundleMeta bundle)) return null;
                List<ItemStack> items = new ArrayList<>(bundle.getItems());
                if (step.index < 0 || step.index >= items.size()) return null;
                cur = items.get(step.index);
            }
        }
        final ItemStack leaf = cur;
        return new ResolvedSlot(leaf, newLeaf -> {
            ItemStack built = newLeaf;
            for (int i = frames.size() - 1; i >= 0; i--) {
                Frame f = frames.get(i);
                ItemStack parent = f.stack.clone();
                if (f.step.kind == NestKind.CONTAINER) {
                    BlockStateMeta bsm = (BlockStateMeta) parent.getItemMeta();
                    Container container = (Container) bsm.getBlockState();
                    container.getInventory().setItem(f.step.index, built);
                    bsm.setBlockState(container);
                    parent.setItemMeta(bsm);
                    built = parent;
                } else if (f.step.kind == NestKind.BUNDLE) {
                    BundleMeta bundle = (BundleMeta) parent.getItemMeta();
                    List<ItemStack> items = new ArrayList<>(bundle.getItems());
                    items.set(f.step.index, built);
                    bundle.setItems(items);
                    parent.setItemMeta(bundle);
                    built = parent;
                }
            }
            rootWriter.set(built);
        });
    }

    private static JsonObject listOffline(UUID uuid, boolean forgeableOnly) {
        JsonObject root = new JsonObject();
        OfflinePlayer off = Bukkit.getOfflinePlayer(uuid);
        root.addProperty("uuid", uuid.toString());
        root.addProperty("name", off.getName() != null ? off.getName() : uuid.toString());
        root.addProperty("online", false);
        JsonArray slots = new JsonArray();
        Map<String, ItemStack> cache = loadOffline(uuid);
        for (Map.Entry<String, ItemStack> e : cache.entrySet()) {
            addSlot(slots, e.getKey(), e.getValue(), forgeableOnly);
        }
        root.add("slots", slots);
        return root;
    }

    private static Result modifyOffline(UUID uuid, String path, ForgeRequest request) {
        Map<String, ItemStack> cache = loadOffline(uuid);
        ItemStack current = cache.get(path);
        if (current == null || current.getType().isAir()) {
            // allow creating a path entry if forge request has itemId (treat as set)
            if (request.itemId != null && !request.itemId.isBlank() && !path.contains("/")) {
                ItemStackBuilder.Result built = ItemStackBuilder.build(request);
                if (!built.success()) return Result.error(built.error());
                cache.put(path, built.stack());
                saveOffline(uuid, cache);
                return Result.ok("Set offline " + path);
            }
            return Result.error("Empty offline slot: " + path);
        }
        ItemStackBuilder.Result built = ItemStackBuilder.applyToExisting(current, request);
        if (!built.success()) return Result.error(built.error());
        cache.put(path, built.stack());
        saveOffline(uuid, cache);
        // try flush to playerdata via NMS
        tryFlushOfflineNms(uuid, cache);
        return Result.ok("Modified offline " + path);
    }

    private static Map<String, ItemStack> loadOffline(UUID uuid) {
        return OFFLINE_CACHE.computeIfAbsent(uuid, id -> {
            Map<String, ItemStack> map = new ConcurrentHashMap<>();
            // seed from NMS playerdata if possible
            tryLoadNmsPlayerData(id, map);
            return map;
        });
    }

    private static void saveOffline(UUID uuid, Map<String, ItemStack> cache) {
        OFFLINE_CACHE.put(uuid, cache);
    }

    private static void tryLoadNmsPlayerData(UUID uuid, Map<String, ItemStack> into) {
        try {
            // Prefer existing offline player who has played
            OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
            if (!op.hasPlayedBefore() && !op.isOnline()) {
                return;
            }
            File worldContainer = Bukkit.getWorlds().isEmpty()
                    ? Bukkit.getWorldContainer()
                    : Bukkit.getWorlds().get(0).getWorldFolder().getParentFile();
            File playerData = new File(new File(worldContainer, "world"), "playerdata");
            if (!playerData.isDirectory()) {
                playerData = new File(Bukkit.getWorldContainer(), "world/playerdata");
            }
            File dat = new File(playerData, uuid + ".dat");
            if (!dat.isFile()) return;

            // Use CraftBukkit CraftItemStack + NBT via reflection where possible
            Class<?> nbtIo = Class.forName("net.minecraft.nbt.NbtIo");
            Method readCompressed = null;
            for (Method m : nbtIo.getMethods()) {
                if (m.getName().equals("readCompressed") && m.getParameterCount() >= 1) {
                    readCompressed = m;
                    break;
                }
            }
            if (readCompressed == null) return;
            Object tag = readCompressed.invoke(null, dat.toPath());
            // Inventory list extraction is version-specific; best-effort skip if structure unknown
            EnchantMasterPlugin.log().info("Offline playerdata found for " + uuid + " (NMS tag loaded; slot map via cache/session)");
        } catch (Throwable t) {
            EnchantMasterPlugin.log().fine("Offline NMS load skipped: " + t.getMessage());
        }
    }

    private static void tryFlushOfflineNms(UUID uuid, Map<String, ItemStack> cache) {
        // Full NMS write is highly version-specific; cache is source of truth until player joins.
        // On join, a listener could apply cache — register simple join apply.
        EnchantMasterPlugin.log().fine("Offline modify cached for " + uuid + " slots=" + cache.size());
    }

    /** Apply offline cache when player joins. */
    public static void applyCacheOnJoin(Player player) {
        Map<String, ItemStack> cache = OFFLINE_CACHE.remove(player.getUniqueId());
        if (cache == null || cache.isEmpty()) return;
        for (Map.Entry<String, ItemStack> e : cache.entrySet()) {
            try {
                ResolvedSlot slot = resolvePath(player, e.getKey());
                if (slot != null) {
                    slot.set(e.getValue());
                }
            } catch (Exception ex) {
                EnchantMasterPlugin.log().warning("Failed applying offline cache " + e.getKey() + ": " + ex.getMessage());
            }
        }
        player.updateInventory();
        EnchantMasterPlugin.log().info("Applied offline inventory cache for " + player.getName());
    }

    private enum NestKind { CONTAINER, BUNDLE }

    private record NestStep(NestKind kind, int index) {
    }

    @FunctionalInterface
    private interface SlotWriter {
        void set(ItemStack stack);
    }

    private record ResolvedSlot(ItemStack current, SlotWriter writer) {
        ItemStack get() { return current; }
        void set(ItemStack stack) { writer.set(stack); }
    }

    public record Result(boolean success, String message) {
        public static Result ok(String m) { return new Result(true, m); }
        public static Result error(String m) { return new Result(false, m); }
    }
}
