package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 1.20.6 inventory read/modify: online + offline playerdata (components + playerdata NBT).
 */
public final class PlayerInventoryService {
    private PlayerInventoryService() {
    }

    public static JsonArray listKnownPlayers(MinecraftServer server) {
        Map<UUID, JsonObject> byId = new HashMap<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", player.getUUID().toString());
                o.addProperty("name", player.getGameProfile().getName());
                o.addProperty("online", true);
                byId.put(player.getUUID(), o);
            } catch (Exception ignored) {
            }
        }
        try {
            File dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".dat") && !name.endsWith("_old.dat"));
            if (files != null) {
                for (File f : files) {
                    try {
                        String base = f.getName().substring(0, f.getName().length() - 4);
                        UUID uuid = UUID.fromString(base);
                        if (byId.containsKey(uuid)) continue;
                        JsonObject o = new JsonObject();
                        o.addProperty("uuid", uuid.toString());
                        o.addProperty("name", resolveName(server, uuid).orElse(uuid.toString()));
                        o.addProperty("online", false);
                        byId.put(uuid, o);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Offline player list failed: {}", e.toString());
        }
        JsonArray arr = new JsonArray();
        byId.values().stream()
                .sorted((a, b) -> {
                    boolean ao = a.get("online").getAsBoolean();
                    boolean bo = b.get("online").getAsBoolean();
                    if (ao != bo) return ao ? -1 : 1;
                    return a.get("name").getAsString().compareToIgnoreCase(b.get("name").getAsString());
                })
                .forEach(arr::add);
        return arr;
    }

    private static Optional<String> resolveName(MinecraftServer server, UUID uuid) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return Optional.of(online.getGameProfile().getName());
        try {
            Path cache = server.getServerDirectory().toPath().resolve("usercache.json");
            if (Files.isRegularFile(cache)) {
                String json = Files.readString(cache);
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
                for (var el : arr) {
                    if (!el.isJsonObject()) continue;
                    var o = el.getAsJsonObject();
                    if (!o.has("uuid") || !o.has("name")) continue;
                    try {
                        if (UUID.fromString(o.get("uuid").getAsString()).equals(uuid)) {
                            return Optional.of(o.get("name").getAsString());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    public static JsonObject getInventory(MinecraftServer server, UUID uuid, boolean forgeableOnly) {
        JsonObject result = new JsonObject();
        result.addProperty("uuid", uuid.toString());
        resolveName(server, uuid).ifPresent(n -> result.addProperty("name", n));
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        result.addProperty("online", online != null);
        JsonArray slots = new JsonArray();
        if (online != null) {
            scanOnline(online, slots, forgeableOnly);
        } else {
            scanOffline(server, uuid, slots, forgeableOnly);
        }
        result.add("slots", slots);
        result.addProperty("count", slots.size());
        return result;
    }

    private static void scanOnline(ServerPlayer player, JsonArray out, boolean forgeableOnly) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            collect(player.serverLevel().getServer(), stack, "inv:" + i, "Inventory slot " + i, out, forgeableOnly, 0);
        }
        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (stack.isEmpty()) continue;
            collect(player.serverLevel().getServer(), stack, "ender:" + i, "Ender Chest #" + i, out, forgeableOnly, 0);
        }
    }

    private static void scanOffline(MinecraftServer server, UUID uuid, JsonArray out, boolean forgeableOnly) {
        try {
            Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
            if (!Files.isRegularFile(file)) return;
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (tag == null) return;
            readList(server, tag, "Inventory", "inv:", "Inventory slot ", out, forgeableOnly);
            readList(server, tag, "EnderItems", "ender:", "Ender Chest #", out, forgeableOnly);
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Offline inventory read failed: {}", e.toString());
        }
    }

    private static void readList(
            MinecraftServer server, CompoundTag tag, String key, String pathPrefix, String labelPrefix,
            JsonArray out, boolean forgeableOnly
    ) {
        if (!tag.contains(key)) return;
        ListTag list = tag.getList(key, 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int slot = entry.contains("Slot") ? entry.getByte("Slot") & 0xFF : i;
            ItemStack stack = ItemStack.parseOptional(server.registryAccess(), entry);
            if (stack.isEmpty()) continue;
            collect(server, stack, pathPrefix + slot, labelPrefix + slot, out, forgeableOnly, 0);
        }
    }

    private static void collect(
            MinecraftServer server, ItemStack stack, String path, String location,
            JsonArray out, boolean forgeableOnly, int depth
    ) {
        if (depth > 6 || stack.isEmpty()) return;
        boolean modifiable = isModifiable(stack);
        if (!forgeableOnly || modifiable) {
            out.add(stackToJson(stack, path, location, modifiable));
        }
        // Nested container component (shulker / chests on 1.20.5+)
        try {
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents != null) {
                for (int i = 0; i < contents.getSlots(); i++) {
                    ItemStack inner = contents.getStackInSlot(i);
                    if (inner.isEmpty()) continue;
                    collect(server, inner, path + "/container:" + i, location + " -> container " + i,
                            out, forgeableOnly, depth + 1);
                }
            }
        } catch (Exception ignored) {
        }
        // Block entity custom data Items list fallback
        try {
            CustomData be = stack.get(DataComponents.BLOCK_ENTITY_DATA);
            if (be != null) {
                CompoundTag tag = be.copyTag();
                if (tag.contains("Items", 9)) {
                    ListTag items = tag.getList("Items", 10);
                    for (int i = 0; i < items.size(); i++) {
                        CompoundTag entry = items.getCompound(i);
                        int slot = entry.contains("Slot") ? entry.getByte("Slot") & 0xFF : i;
                        ItemStack inner = ItemStack.parseOptional(server.registryAccess(), entry);
                        if (inner.isEmpty()) continue;
                        collect(server, inner, path + "/container:" + slot, location + " -> container " + slot,
                                out, forgeableOnly, depth + 1);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // SBP reflection (optional)
        try {
            Class<?> wrapperCl = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Object wrapper = wrapperCl.getMethod("fromStack", ItemStack.class).invoke(null, stack);
            if (wrapper == null) return;
            Object handler = wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
            int size;
            try {
                size = (int) handler.getClass().getMethod("getSlots").invoke(handler);
            } catch (NoSuchMethodException e) {
                size = (int) handler.getClass().getMethod("size").invoke(handler);
            }
            for (int i = 0; i < size; i++) {
                ItemStack inner;
                try {
                    inner = (ItemStack) handler.getClass().getMethod("getStackInSlot", int.class).invoke(handler, i);
                } catch (NoSuchMethodException e) {
                    try {
                        inner = (ItemStack) handler.getClass().getMethod("getInternalStack", int.class).invoke(handler, i);
                    } catch (NoSuchMethodException e2) {
                        continue;
                    }
                }
                if (inner == null || inner.isEmpty()) continue;
                collect(server, inner, path + "/sbp:" + i, location + " -> backpack " + i,
                        out, forgeableOnly, depth + 1);
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("SBP scan: {}", e.toString());
        }
    }

    private static boolean isModifiable(ItemStack stack) {
        try {
            if (ItemCatalog.isBookLike(stack.getItem())) return true;
            if (stack.isEnchanted()) return true;
            if (stack.has(DataComponents.ATTRIBUTE_MODIFIERS)) return true;
            if (stack.has(DataComponents.CUSTOM_NAME) || stack.has(DataComponents.LORE)) return true;
            List<Enchantment> enchants = new ArrayList<>();
            BuiltInRegistries.ENCHANTMENT.forEach(enchants::add);
            return ItemCatalog.isForgeableItem(stack, stack.getItem(), enchants);
        } catch (Exception e) {
            return stack.isEnchanted();
        }
    }

    private static JsonObject stackToJson(ItemStack stack, String path, String location, boolean modifiable) {
        JsonObject o = new JsonObject();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        o.addProperty("path", path);
        o.addProperty("location", location);
        o.addProperty("itemId", id != null ? id.toString() : "minecraft:air");
        o.addProperty("count", stack.getCount());
        o.addProperty("name", ItemCatalog.safeStackDisplayName(stack));
        o.addProperty("namespace", id != null ? id.getNamespace() : "minecraft");
        o.addProperty("pathName", id != null ? id.getPath() : "air");
        o.addProperty("iconUrl", id != null
                ? "/api/assets/item/" + id.getNamespace() + "/" + id.getPath() + ".png" : "");
        o.addProperty("modifiable", modifiable);

        // Custom name (styled)
        try {
            net.minecraft.network.chat.Component customName = stack.get(DataComponents.CUSTOM_NAME);
            if (customName != null) {
                o.add("customName", StyledText.fromComponent(customName, false).toJson());
            }
        } catch (Exception ignored) {
        }

        // Lore / flavor text
        JsonArray loreArr = new JsonArray();
        try {
            net.minecraft.world.item.component.ItemLore lore = stack.get(DataComponents.LORE);
            if (lore != null) {
                for (net.minecraft.network.chat.Component line : lore.lines()) {
                    if (line == null) continue;
                    StyledText styled = StyledText.fromComponent(line, true);
                    if (styled.text() == null || styled.text().isBlank()) continue;
                    loreArr.add(styled.toJson());
                }
            }
        } catch (Exception ignored) {
        }
        o.add("lore", loreArr);

        JsonArray enchants = new JsonArray();
        try {
            ItemEnchantments map = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (map.isEmpty()) {
                map = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            }
            for (var entry : map.entrySet()) {
                try {
                    ResourceLocation eid = entry.getKey().unwrapKey()
                            .map(k -> k.location())
                            .orElse(BuiltInRegistries.ENCHANTMENT.getKey(entry.getKey().value()));
                    JsonObject e = new JsonObject();
                    e.addProperty("id", eid != null ? eid.toString() : "?");
                    e.addProperty("level", entry.getIntValue());
                    try {
                        e.addProperty("name", entry.getKey().value().getFullname(entry.getIntValue()).getString());
                    } catch (Exception ignored) {
                        e.addProperty("name", eid != null ? eid.toString() : "?");
                    }
                    try {
                        e.addProperty("maxLevel", entry.getKey().value().getMaxLevel());
                    } catch (Exception ignored) {
                        e.addProperty("maxLevel", 1);
                    }
                    enchants.add(e);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        o.add("enchantments", enchants);
        JsonArray attrs = new JsonArray();
        try {
            ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
            if (mods != null) {
                for (var entry : mods.modifiers()) {
                    JsonObject a = new JsonObject();
                    ResourceLocation aid = BuiltInRegistries.ATTRIBUTE.getKey(entry.attribute().value());
                    a.addProperty("id", aid != null ? aid.toString() : "?");
                    a.addProperty("amount", entry.modifier().amount());
                    a.addProperty("operation", entry.modifier().operation().getSerializedName());
                    a.addProperty("slot", entry.slot().getSerializedName());
                    attrs.add(a);
                }
            }
        } catch (Exception ignored) {
        }
        o.add("attributes", attrs);
        return o;
    }

    public static Result modifyItem(MinecraftServer server, UUID uuid, String path, ForgeRequest request) {
        return modifyItem(server, uuid, path, request, null);
    }

    public static Result modifyItem(
            MinecraftServer server, UUID uuid, String path, ForgeRequest request, AuditActor actor
    ) {
        String targetName = resolveName(server, uuid).orElse(uuid.toString());
        if (path == null || path.isBlank()) {
            Result err = Result.error("path is required");
            audit(actor, targetName, uuid, path, request, err);
            return err;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        try {
            ItemStack original = resolveStack(server, uuid, online, path);
            if (original == null || original.isEmpty()) {
                Result err = Result.error("No item at path " + path);
                audit(actor, targetName, uuid, path, request, err);
                return err;
            }
            ItemStackBuilder.Result built = ItemStackBuilder.applyToExisting(server, original, request);
            if (!built.success()) {
                Result err = Result.error(built.error());
                audit(actor, targetName, uuid, path, request, err);
                return err;
            }
            ItemStack neu = built.stack();
            neu.setCount(original.getCount());
            Result result = writeStack(server, uuid, online, path, neu);
            audit(actor, targetName, uuid, path, request, result);
            return result;
        } catch (Exception e) {
            Result err = Result.error("Modify failed: " + e.getMessage());
            audit(actor, targetName, uuid, path, request, err);
            return err;
        }
    }

    private static void audit(AuditActor actor, String targetName, UUID uuid, String path, ForgeRequest req, Result result) {
        if (actor == null) return;
        AuditLog.inventoryModify(actor, targetName, uuid.toString(), path, req, result.success(), result.message());
    }

    private static ItemStack resolveStack(MinecraftServer server, UUID uuid, ServerPlayer online, String path) {
        String[] parts = path.split("/");
        if (parts.length == 0) return ItemStack.EMPTY;
        ItemStack root;
        if (online != null) {
            root = getTopOnline(online, parts[0]);
        } else {
            root = getTopOffline(server, uuid, parts[0]);
        }
        if (root.isEmpty()) return ItemStack.EMPTY;
        ItemStack cur = root;
        for (int i = 1; i < parts.length; i++) {
            cur = getNested(cur, parts[i]);
            if (cur == null || cur.isEmpty()) return ItemStack.EMPTY;
        }
        return cur;
    }

    private static ItemStack getTopOnline(ServerPlayer player, String seg) {
        if (seg.startsWith("inv:")) {
            return player.getInventory().getItem(Integer.parseInt(seg.substring(4)));
        }
        if (seg.startsWith("ender:")) {
            return player.getEnderChestInventory().getItem(Integer.parseInt(seg.substring(6)));
        }
        return ItemStack.EMPTY;
    }

    private static void setTopOnline(ServerPlayer player, String seg, ItemStack stack) {
        if (seg.startsWith("inv:")) {
            player.getInventory().setItem(Integer.parseInt(seg.substring(4)), stack);
        } else if (seg.startsWith("ender:")) {
            player.getEnderChestInventory().setItem(Integer.parseInt(seg.substring(6)), stack);
        }
    }

    private static ItemStack getTopOffline(MinecraftServer server, UUID uuid, String seg) {
        try {
            Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(uuid + ".dat");
            if (!Files.isRegularFile(file)) return ItemStack.EMPTY;
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (tag == null) return ItemStack.EMPTY;
            String listKey = seg.startsWith("ender:") ? "EnderItems" : "Inventory";
            int slot = Integer.parseInt(seg.substring(seg.indexOf(':') + 1));
            ListTag list = tag.getList(listKey, 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                int s = entry.contains("Slot") ? entry.getByte("Slot") & 0xFF : i;
                if (s == slot) {
                    return ItemStack.parseOptional(server.registryAccess(), entry);
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Offline resolve: {}", e.toString());
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack getNested(ItemStack parent, String seg) {
        try {
            if (seg.startsWith("container:")) {
                int idx = Integer.parseInt(seg.substring(10));
                ItemContainerContents contents = parent.get(DataComponents.CONTAINER);
                if (contents == null) return ItemStack.EMPTY;
                if (idx < 0 || idx >= contents.getSlots()) return ItemStack.EMPTY;
                return contents.getStackInSlot(idx);
            }
            if (seg.startsWith("sbp:")) {
                int idx = Integer.parseInt(seg.substring(4));
                Class<?> wrapperCl = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
                Object wrapper = wrapperCl.getMethod("fromStack", ItemStack.class).invoke(null, parent);
                Object handler = wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
                try {
                    return (ItemStack) handler.getClass().getMethod("getStackInSlot", int.class).invoke(handler, idx);
                } catch (NoSuchMethodException e) {
                    return (ItemStack) handler.getClass().getMethod("getInternalStack", int.class).invoke(handler, idx);
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Nested resolve: {}", e.toString());
        }
        return ItemStack.EMPTY;
    }

    private static Result writeStack(
            MinecraftServer server, UUID uuid, ServerPlayer online, String path, ItemStack neu
    ) {
        String[] parts = path.split("/");
        if (parts.length == 1) {
            if (online != null) {
                setTopOnline(online, parts[0], neu);
                online.containerMenu.broadcastChanges();
                return Result.ok("Updated " + path);
            }
            return writeOfflineTop(server, uuid, parts[0], neu);
        }
        ItemStack root = online != null ? getTopOnline(online, parts[0]).copy()
                : getTopOffline(server, uuid, parts[0]).copy();
        if (root.isEmpty()) return Result.error("Empty root " + parts[0]);
        ItemStack updated = replaceNested(root, List.of(parts).subList(1, parts.length), neu);
        if (updated == null) return Result.error("Nested replace failed");
        if (online != null) {
            setTopOnline(online, parts[0], updated);
            online.containerMenu.broadcastChanges();
            return Result.ok("Updated " + path);
        }
        return writeOfflineTop(server, uuid, parts[0], updated);
    }

    private static Result writeOfflineTop(MinecraftServer server, UUID uuid, String seg, ItemStack stack) {
        try {
            Path playerDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            Path file = playerDir.resolve(uuid + ".dat");
            if (!Files.isRegularFile(file)) return Result.error("No playerdata");
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            if (tag == null) return Result.error("Empty playerdata");
            String listKey = seg.startsWith("ender:") ? "EnderItems" : "Inventory";
            int slot = Integer.parseInt(seg.substring(seg.indexOf(':') + 1));
            ListTag list = tag.contains(listKey) ? tag.getList(listKey, 10) : new ListTag();
            ListTag neu = new ListTag();
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i).copy();
                int s = entry.contains("Slot") ? entry.getByte("Slot") & 0xFF : i;
                if (s == slot) {
                    CompoundTag saved = (CompoundTag) stack.save(server.registryAccess(), new CompoundTag());
                    saved.putByte("Slot", (byte) slot);
                    neu.add(saved);
                    replaced = true;
                } else {
                    neu.add(entry);
                }
            }
            if (!replaced && !stack.isEmpty()) {
                CompoundTag saved = (CompoundTag) stack.save(server.registryAccess(), new CompoundTag());
                saved.putByte("Slot", (byte) slot);
                neu.add(saved);
            }
            tag.put(listKey, neu);
            Path tmp = Files.createTempFile(playerDir, uuid + "-", ".dat");
            NbtIo.writeCompressed(tag, tmp);
            Path real = playerDir.resolve(uuid + ".dat");
            Path old = playerDir.resolve(uuid + ".dat_old");
            net.minecraft.Util.safeReplaceFile(real, tmp, old);
            return Result.ok("Updated offline " + seg);
        } catch (Exception e) {
            return Result.error("Offline write failed: " + e.getMessage());
        }
    }

    private static ItemStack replaceNested(ItemStack parent, List<String> segs, ItemStack neu) {
        if (segs.isEmpty()) return neu;
        String seg = segs.get(0);
        List<String> rest = segs.subList(1, segs.size());
        if (seg.startsWith("container:")) {
            // Nested container write via data components is limited in this port;
            // top-level inventory/ender slots are fully supported.
            EnchantMaster.LOGGER.warn("Nested container modify not fully supported on 1.20.6 component stacks: {}", seg);
            return null;
        }
        if (seg.startsWith("sbp:")) {
            try {
                int idx = Integer.parseInt(seg.substring(4));
                Class<?> wrapperCl = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
                Object wrapper = wrapperCl.getMethod("fromStack", ItemStack.class).invoke(null, parent);
                Object handler = wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
                ItemStack cur;
                try {
                    cur = (ItemStack) handler.getClass().getMethod("getStackInSlot", int.class).invoke(handler, idx);
                } catch (NoSuchMethodException e) {
                    cur = (ItemStack) handler.getClass().getMethod("getInternalStack", int.class).invoke(handler, idx);
                }
                ItemStack rep = rest.isEmpty() ? neu.copy() : replaceNested(cur.copy(), rest, neu);
                if (rep == null) return null;
                handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(handler, idx, rep);
                return parent;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    public record Result(boolean success, String message) {
        public static Result ok(String m) {
            return new Result(true, m);
        }

        public static Result error(String m) {
            return new Result(false, m);
        }
    }
}
