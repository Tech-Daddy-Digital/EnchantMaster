package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.enchantmaster.EnchantMaster;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read/modify player inventories (online + offline), including nested containers
 * (shulker {@link DataComponents#CONTAINER}, bundles) and Sophisticated Backpacks
 * when present (reflection).
 */
public final class PlayerInventoryService {
    private PlayerInventoryService() {
    }

    public static JsonArray listKnownPlayers(MinecraftServer server) {
        Map<UUID, JsonObject> byId = new HashMap<>();

        // Online first
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", player.getUUID().toString());
                o.addProperty("name", player.getGameProfile().name());
                o.addProperty("online", true);
                byId.put(player.getUUID(), o);
            } catch (Exception e) {
                EnchantMaster.LOGGER.debug("Skip online player: {}", e.toString());
            }
        }

        // Offline from playerdata + usercache names when possible
        try {
            File dir = server.getPlayerList().getPlayerIo().getPlayerDir();
            File[] files = dir.listFiles((d, name) -> name.endsWith(".dat") && !name.endsWith("_old.dat"));
            if (files != null) {
                for (File f : files) {
                    try {
                        String base = f.getName().substring(0, f.getName().length() - 4);
                        UUID uuid = UUID.fromString(base);
                        if (byId.containsKey(uuid)) continue;
                        String name = resolveName(server, uuid).orElse(uuid.toString());
                        JsonObject o = new JsonObject();
                        o.addProperty("uuid", uuid.toString());
                        o.addProperty("name", name);
                        o.addProperty("online", false);
                        byId.put(uuid, o);
                    } catch (Exception e) {
                        EnchantMaster.LOGGER.debug("Skip playerdata {}: {}", f.getName(), e.toString());
                    }
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Failed listing offline players: {}", e.toString());
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
        try {
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            if (online != null) return Optional.of(online.getGameProfile().name());
        } catch (Exception ignored) {
        }
        // usercache.json in game directory
        try {
            Path cache = server.getServerDirectory().resolve("usercache.json");
            if (Files.isRegularFile(cache)) {
                String json = Files.readString(cache);
                // simple parse: find matching uuid entry
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json).getAsJsonArray();
                for (var el : arr) {
                    if (!el.isJsonObject()) continue;
                    var o = el.getAsJsonObject();
                    if (o.has("uuid") && uuid.toString().equalsIgnoreCase(o.get("uuid").getAsString().replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"
                    ).replace("--", "-"))) {
                        // uuid in cache may be with or without dashes
                    }
                    String rawUuid = o.has("uuid") ? o.get("uuid").getAsString() : "";
                    UUID parsed;
                    try {
                        parsed = UUID.fromString(rawUuid.contains("-") ? rawUuid :
                                rawUuid.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
                    } catch (Exception e) {
                        continue;
                    }
                    if (parsed.equals(uuid) && o.has("name")) {
                        return Optional.of(o.get("name").getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return Optional.empty();
    }

    /**
     * List inventory entries (modifiable items only by default).
     *
     * @param forgeableOnly if true, only items that can be enchanted or have attributes
     */
    public static JsonObject getInventory(MinecraftServer server, UUID uuid, boolean forgeableOnly) {
        JsonObject result = new JsonObject();
        result.addProperty("uuid", uuid.toString());
        Optional<String> name = resolveName(server, uuid);
        name.ifPresent(n -> result.addProperty("name", n));

        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        result.addProperty("online", online != null);

        JsonArray slots = new JsonArray();
        if (online != null) {
            scanOnlinePlayer(server, online, slots, forgeableOnly);
        } else {
            scanOfflinePlayer(server, uuid, name.orElse(uuid.toString()), slots, forgeableOnly);
        }
        result.add("slots", slots);
        result.addProperty("count", slots.size());
        return result;
    }

    private static void scanOnlinePlayer(
            MinecraftServer server, ServerPlayer player, JsonArray out, boolean forgeableOnly
    ) {
        Inventory inv = player.getInventory();
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String path = pathForInventorySlot(i);
            collectStack(server, stack, path, slotLabel("Inventory", i), out, forgeableOnly, 0);
        }
        // Ender chest
        var ender = player.getEnderChestInventory();
        for (int i = 0; i < ender.getContainerSize(); i++) {
            ItemStack stack = ender.getItem(i);
            if (stack.isEmpty()) continue;
            collectStack(server, stack, "ender:" + i, "Ender Chest #" + i, out, forgeableOnly, 0);
        }
    }

    private static String pathForInventorySlot(int slot) {
        EquipmentSlot equip = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);
        if (equip != null) {
            return "equip:" + equip.getSerializedName();
        }
        return "inv:" + slot;
    }

    private static String slotLabel(String section, int index) {
        return section + " slot " + index;
    }

    private static void scanOfflinePlayer(
            MinecraftServer server, UUID uuid, String name, JsonArray out, boolean forgeableOnly
    ) {
        try {
            Optional<CompoundTag> tagOpt = server.getPlayerList().getPlayerIo()
                    .load(new NameAndId(uuid, name));
            if (tagOpt.isEmpty()) {
                return;
            }
            CompoundTag tag = tagOpt.get();
            HolderLookup.Provider registries = server.registryAccess();
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);

            // Main inventory
            for (ItemStackWithSlot entry : input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC)) {
                if (entry.stack().isEmpty()) continue;
                collectStack(server, entry.stack(), "inv:" + entry.slot(),
                        "Inventory slot " + entry.slot(), out, forgeableOnly, 0);
            }
            // Ender chest
            for (ItemStackWithSlot entry : input.listOrEmpty("EnderItems", ItemStackWithSlot.CODEC)) {
                if (entry.stack().isEmpty()) continue;
                collectStack(server, entry.stack(), "ender:" + entry.slot(),
                        "Ender Chest #" + entry.slot(), out, forgeableOnly, 0);
            }
            // Equipment map
            try {
                input.read("equipment", net.minecraft.world.entity.EntityEquipment.CODEC).ifPresent(equip -> {
                    for (EquipmentSlot slot : EquipmentSlot.values()) {
                        try {
                            ItemStack stack = equip.get(slot);
                            if (stack == null || stack.isEmpty()) continue;
                            collectStack(server, stack, "equip:" + slot.getSerializedName(),
                                    "Equipment " + slot.getSerializedName(), out, forgeableOnly, 0);
                        } catch (Exception ignored) {
                        }
                    }
                });
            } catch (Exception e) {
                EnchantMaster.LOGGER.debug("Offline equipment parse: {}", e.toString());
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Failed reading offline inventory for {}: {}", uuid, e.toString());
        }
    }

    private static void collectStack(
            MinecraftServer server,
            ItemStack stack,
            String path,
            String location,
            JsonArray out,
            boolean forgeableOnly,
            int depth
    ) {
        if (depth > 6 || stack.isEmpty()) return;

        boolean relevant = isModifiable(server, stack);
        if (!forgeableOnly || relevant) {
            out.add(stackToJson(server, stack, path, location, relevant));
        }

        // Nested vanilla container (shulker, etc.)
        try {
            ItemContainerContents container = stack.get(DataComponents.CONTAINER);
            if (container != null && container != ItemContainerContents.EMPTY) {
                int slots = container.getSlots();
                for (int i = 0; i < slots; i++) {
                    ItemStack inner = container.getStackInSlot(i);
                    if (inner.isEmpty()) continue;
                    collectStack(server, inner, path + "/container:" + i,
                            location + " → container " + i, out, forgeableOnly, depth + 1);
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Container scan failed at {}: {}", path, e.toString());
        }

        // Bundles
        try {
            BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
            if (bundle != null && !bundle.isEmpty()) {
                List<ItemStack> copies = bundle.itemCopyStream().toList();
                for (int i = 0; i < copies.size(); i++) {
                    ItemStack inner = copies.get(i);
                    if (inner.isEmpty()) continue;
                    collectStack(server, inner, path + "/bundle:" + i,
                            location + " → bundle " + i, out, forgeableOnly, depth + 1);
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Bundle scan failed at {}: {}", path, e.toString());
        }

        // Sophisticated Backpacks (reflection)
        scanSophisticatedBackpack(server, stack, path, location, out, forgeableOnly, depth);
    }

    private static void scanSophisticatedBackpack(
            MinecraftServer server, ItemStack stack, String path, String location,
            JsonArray out, boolean forgeableOnly, int depth
    ) {
        try {
            Class<?> wrapperCl = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Object wrapper = wrapperCl.getMethod("fromStack", ItemStack.class).invoke(null, stack);
            if (wrapper == null) return;
            Object handler = wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
            if (handler == null) return;
            // size from ResourceHandler size() or InventoryHandler
            int size;
            try {
                size = (int) handler.getClass().getMethod("size").invoke(handler);
            } catch (NoSuchMethodException e) {
                // try getSlots
                size = (int) handler.getClass().getMethod("getSlots").invoke(handler);
            }
            for (int i = 0; i < size; i++) {
                ItemStack inner;
                try {
                    inner = (ItemStack) handler.getClass().getMethod("getInternalStack", int.class).invoke(handler, i);
                } catch (NoSuchMethodException e) {
                    continue;
                }
                if (inner == null || inner.isEmpty()) continue;
                collectStack(server, inner, path + "/sbp:" + i,
                        location + " → backpack " + i, out, forgeableOnly, depth + 1);
            }
        } catch (ClassNotFoundException e) {
            // mod not installed
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("SBP scan failed at {}: {}", path, e.toString());
        }
    }

    private static boolean isModifiable(MinecraftServer server, ItemStack stack) {
        try {
            if (ItemCatalog.isBookLike(stack.getItem())) return true;
            ItemAttributeProbe attrs = ItemAttributeProbe.fromStack(stack);
            if (attrs.hasAny()) return true;
            // any applicable enchant?
            List<Holder.Reference<Enchantment>> enchants = new ArrayList<>();
            server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).listElements().forEach(enchants::add);
            return ItemCatalog.isForgeableItem(stack, stack.getItem(), enchants);
        } catch (Exception e) {
            return stack.isEnchanted();
        }
    }

    private static JsonObject stackToJson(
            MinecraftServer server, ItemStack stack, String path, String location, boolean modifiable
    ) {
        JsonObject o = new JsonObject();
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        o.addProperty("path", path);
        o.addProperty("location", location);
        o.addProperty("itemId", id != null ? id.toString() : "minecraft:air");
        o.addProperty("count", stack.getCount());
        o.addProperty("name", ItemCatalog.safeStackDisplayName(stack));
        o.addProperty("namespace", id != null ? id.getNamespace() : "minecraft");
        o.addProperty("pathName", id != null ? id.getPath() : "air");
        o.addProperty("iconUrl", id != null
                ? "/api/assets/item/" + id.getNamespace() + "/" + id.getPath() + ".png"
                : "");
        o.addProperty("modifiable", modifiable);

        // Custom name (styled) — only when the stack has an explicit CUSTOM_NAME component
        try {
            net.minecraft.network.chat.Component customName = stack.get(DataComponents.CUSTOM_NAME);
            if (customName != null) {
                o.add("customName", StyledText.fromComponent(customName, false).toJson());
            }
        } catch (Exception ignored) {
        }

        // Lore / flavor text lines (styled) — must be returned so the UI can prefill and not wipe on apply
        JsonArray loreArr = new JsonArray();
        try {
            ItemLore lore = stack.get(DataComponents.LORE);
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

        // Enchantments
        JsonArray enchants = new JsonArray();
        try {
            ItemEnchantments itemEnchants = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
            if (itemEnchants.isEmpty()) {
                itemEnchants = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            }
            itemEnchants.entrySet().forEach(entry -> {
                try {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", entry.getKey().getRegisteredName());
                    e.addProperty("level", entry.getIntValue());
                    try {
                        Identifier eid = entry.getKey().unwrapKey().map(k -> k.identifier()).orElse(null);
                        String ename = entry.getKey().value().description().getString();
                        if (!ItemCatalog.isUsableDisplayName(ename, eid)) {
                            ename = eid != null ? ItemCatalog.humanizePath(eid) : ename;
                        }
                        e.addProperty("name", ename);
                        e.addProperty("maxLevel", entry.getKey().value().getMaxLevel());
                    } catch (Exception ignored) {
                        e.addProperty("name", e.get("id").getAsString());
                        e.addProperty("maxLevel", 1);
                    }
                    enchants.add(e);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        o.add("enchantments", enchants);

        // Attributes summary
        JsonArray attrs = new JsonArray();
        try {
            var mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                    net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
            for (var entry : mods.modifiers()) {
                JsonObject a = new JsonObject();
                Identifier aid = entry.attribute().unwrapKey().map(k -> k.identifier())
                        .orElse(null);
                a.addProperty("id", aid != null ? aid.toString() : "?");
                a.addProperty("amount", entry.modifier().amount());
                a.addProperty("operation", entry.modifier().operation().getSerializedName());
                a.addProperty("slot", entry.slot().getSerializedName());
                attrs.add(a);
            }
        } catch (Exception ignored) {
        }
        o.add("attributes", attrs);
        return o;
    }

    /**
     * Apply forge-style modifications to an inventory item at path, replacing it in place.
     * Patches the existing stack so nested container/backpack data is preserved.
     */
    public static Result modifyItem(MinecraftServer server, UUID uuid, String path, ForgeRequest request) {
        return modifyItem(server, uuid, path, request, null);
    }

    public static Result modifyItem(
            MinecraftServer server,
            UUID uuid,
            String path,
            ForgeRequest request,
            dev.enchantmaster.audit.AuditActor actor
    ) {
        String targetName = resolveName(server, uuid).orElse(uuid != null ? uuid.toString() : "?");
        String targetUuid = uuid != null ? uuid.toString() : "?";

        if (path == null || path.isBlank()) {
            Result err = Result.error("path is required");
            auditModify(actor, targetName, targetUuid, path, request, err);
            return err;
        }

        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            try {
                targetName = online.getGameProfile().name();
            } catch (Exception ignored) {
            }
        }
        try {
            ItemStack original = resolveStackAtPath(server, uuid, online, path);
            if (original == null || original.isEmpty()) {
                Result err = Result.error("No item at path " + path);
                auditModify(actor, targetName, targetUuid, path, request, err);
                return err;
            }
            ItemStackBuilder.Result built = ItemStackBuilder.applyToExisting(server, original, request);
            if (!built.success()) {
                Result err = Result.error(built.error());
                auditModify(actor, targetName, targetUuid, path, request, err);
                return err;
            }
            ItemStack newStack = built.stack();
            newStack.setCount(original.getCount());

            Result result;
            if (online != null) {
                result = replaceOnline(server, online, path, newStack);
            } else {
                String name = resolveName(server, uuid).orElse(uuid.toString());
                result = replaceOffline(server, uuid, name, path, newStack);
            }
            auditModify(actor, targetName, targetUuid, path, request, result);
            return result;
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Inventory modify failed: {}", e.toString());
            Result err = Result.error("Modify failed: " + e.getMessage());
            auditModify(actor, targetName, targetUuid, path, request, err);
            return err;
        }
    }

    private static void auditModify(
            dev.enchantmaster.audit.AuditActor actor,
            String targetName,
            String targetUuid,
            String path,
            ForgeRequest request,
            Result result
    ) {
        if (actor == null || result == null) return;
        dev.enchantmaster.audit.AuditLog.inventoryModify(
                actor, targetName, targetUuid, path, request, result.success(), result.message()
        );
    }

    /** Resolve the current ItemStack at a path (online or offline). */
    private static ItemStack resolveStackAtPath(
            MinecraftServer server, UUID uuid, ServerPlayer online, String path
    ) {
        PathParts parts = PathParts.parse(path);
        if (parts.segments.isEmpty()) return ItemStack.EMPTY;

        ItemStack root;
        if (online != null) {
            root = getTopLevelOnline(online, parts.segments.get(0));
        } else {
            root = getTopLevelOffline(server, uuid, parts.segments.get(0));
        }
        if (root.isEmpty()) return ItemStack.EMPTY;
        if (parts.segments.size() == 1) return root;

        ItemStack current = root;
        for (int i = 1; i < parts.segments.size(); i++) {
            current = getNestedChild(current, parts.segments.get(i));
            if (current == null || current.isEmpty()) return ItemStack.EMPTY;
        }
        return current;
    }

    private static ItemStack getTopLevelOffline(MinecraftServer server, UUID uuid, String segment) {
        try {
            String name = resolveName(server, uuid).orElse(uuid.toString());
            Optional<CompoundTag> tagOpt = server.getPlayerList().getPlayerIo()
                    .load(new NameAndId(uuid, name));
            if (tagOpt.isEmpty()) return ItemStack.EMPTY;
            CompoundTag tag = tagOpt.get();
            HolderLookup.Provider registries = server.registryAccess();
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);

            if (segment.startsWith("inv:")) {
                int slot = Integer.parseInt(segment.substring(4));
                for (ItemStackWithSlot entry : input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC)) {
                    if (entry.slot() == slot) return entry.stack();
                }
            } else if (segment.startsWith("ender:")) {
                int slot = Integer.parseInt(segment.substring(6));
                for (ItemStackWithSlot entry : input.listOrEmpty("EnderItems", ItemStackWithSlot.CODEC)) {
                    if (entry.slot() == slot) return entry.stack();
                }
            } else if (segment.startsWith("equip:")) {
                String equipName = segment.substring(6);
                var equipOpt = input.read("equipment", net.minecraft.world.entity.EntityEquipment.CODEC);
                if (equipOpt.isPresent()) {
                    for (EquipmentSlot s : EquipmentSlot.values()) {
                        if (s.getSerializedName().equals(equipName)) {
                            ItemStack st = equipOpt.get().get(s);
                            return st != null ? st : ItemStack.EMPTY;
                        }
                    }
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Offline resolve failed: {}", e.toString());
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack getNestedChild(ItemStack parent, String seg) {
        try {
            if (seg.startsWith("container:")) {
                int idx = Integer.parseInt(seg.substring(10));
                ItemContainerContents contents = parent.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
                if (idx < 0 || idx >= contents.getSlots()) return ItemStack.EMPTY;
                return contents.getStackInSlot(idx);
            }
            if (seg.startsWith("bundle:")) {
                int idx = Integer.parseInt(seg.substring(7));
                BundleContents bundle = parent.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
                List<ItemStack> items = bundle.itemCopyStream().toList();
                if (idx < 0 || idx >= items.size()) return ItemStack.EMPTY;
                return items.get(idx);
            }
            if (seg.startsWith("sbp:")) {
                int idx = Integer.parseInt(seg.substring(4));
                Class<?> wrapperCl = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
                Object wrapper = wrapperCl.getMethod("fromStack", ItemStack.class).invoke(null, parent);
                Object handler = wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
                ItemStack inner = (ItemStack) handler.getClass().getMethod("getInternalStack", int.class).invoke(handler, idx);
                return inner != null ? inner : ItemStack.EMPTY;
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Nested resolve failed for {}: {}", seg, e.toString());
        }
        return ItemStack.EMPTY;
    }

    private static Result replaceOnline(MinecraftServer server, ServerPlayer player, String path, ItemStack newStack) {
        PathParts parts = PathParts.parse(path);
        if (parts.segments.isEmpty()) return Result.error("Invalid path");

        // Top-level resolution
        String root = parts.segments.get(0);
        if (parts.segments.size() == 1) {
            ItemStack old = getTopLevelOnline(player, root);
            if (old.isEmpty()) return Result.error("Empty slot at " + path);
            newStack.setCount(old.getCount());
            setTopLevelOnline(player, root, newStack);
            player.containerMenu.broadcastChanges();
            return Result.ok("Updated " + path);
        }

        // Nested: get root stack, modify nested, write root back
        ItemStack rootStack = getTopLevelOnline(player, root).copy();
        if (rootStack.isEmpty()) return Result.error("Empty slot at " + root);
        ItemStack updatedRoot = replaceNested(rootStack, parts.segments.subList(1, parts.segments.size()), newStack);
        if (updatedRoot == null) return Result.error("Failed nested replace at " + path);
        setTopLevelOnline(player, root, updatedRoot);
        player.containerMenu.broadcastChanges();
        return Result.ok("Updated " + path);
    }

    private static ItemStack getTopLevelOnline(ServerPlayer player, String segment) {
        if (segment.startsWith("inv:")) {
            int slot = Integer.parseInt(segment.substring(4));
            return player.getInventory().getItem(slot);
        }
        if (segment.startsWith("equip:")) {
            String name = segment.substring(6);
            for (var e : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
                if (e.getValue().getSerializedName().equals(name)) {
                    return player.getInventory().getItem(e.getIntKey());
                }
            }
            // also try EquipmentSlot by name on living entity
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.getSerializedName().equals(name)) {
                    return player.getItemBySlot(slot);
                }
            }
        }
        if (segment.startsWith("ender:")) {
            int slot = Integer.parseInt(segment.substring(6));
            return player.getEnderChestInventory().getItem(slot);
        }
        return ItemStack.EMPTY;
    }

    private static void setTopLevelOnline(ServerPlayer player, String segment, ItemStack stack) {
        if (segment.startsWith("inv:")) {
            int slot = Integer.parseInt(segment.substring(4));
            player.getInventory().setItem(slot, stack);
            return;
        }
        if (segment.startsWith("equip:")) {
            String name = segment.substring(6);
            for (var e : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
                if (e.getValue().getSerializedName().equals(name)) {
                    player.getInventory().setItem(e.getIntKey(), stack);
                    return;
                }
            }
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (slot.getSerializedName().equals(name)) {
                    player.setItemSlot(slot, stack);
                    return;
                }
            }
        }
        if (segment.startsWith("ender:")) {
            int slot = Integer.parseInt(segment.substring(6));
            player.getEnderChestInventory().setItem(slot, stack);
        }
    }

    private static Result replaceOffline(
            MinecraftServer server, UUID uuid, String name, String path, ItemStack newStack
    ) {
        PlayerDataStorage io = server.getPlayerList().getPlayerIo();
        NameAndId nameAndId = new NameAndId(uuid, name);
        Optional<CompoundTag> tagOpt = io.load(nameAndId);
        if (tagOpt.isEmpty()) {
            return Result.error("No offline player data for " + name);
        }
        CompoundTag tag = tagOpt.get().copy();
        HolderLookup.Provider registries = server.registryAccess();
        PathParts parts = PathParts.parse(path);
        if (parts.segments.isEmpty()) return Result.error("Invalid path");

        String root = parts.segments.get(0);
        if (root.startsWith("inv:")) {
            int slot = Integer.parseInt(root.substring(4));
            return replaceOfflineList(server, io, nameAndId, tag, "Inventory", slot,
                    parts.segments.subList(1, parts.segments.size()), newStack);
        }
        if (root.startsWith("ender:")) {
            int slot = Integer.parseInt(root.substring(6));
            return replaceOfflineList(server, io, nameAndId, tag, "EnderItems", slot,
                    parts.segments.subList(1, parts.segments.size()), newStack);
        }
        // Offline equipment modification
        if (root.startsWith("equip:")) {
            return replaceOfflineEquipment(server, io, nameAndId, tag, root.substring(6),
                    parts.segments.subList(1, parts.segments.size()), newStack);
        }
        return Result.error("Unsupported offline path: " + path);
    }

    private static Result replaceOfflineList(
            MinecraftServer server, PlayerDataStorage io, NameAndId nameAndId, CompoundTag tag,
            String listKey, int slot, List<String> nested, ItemStack newStack
    ) {
        try {
            HolderLookup.Provider registries = server.registryAccess();
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);
            List<ItemStackWithSlot> entries = new ArrayList<>();
            Map<Integer, ItemStack> bySlot = new HashMap<>();
            for (ItemStackWithSlot e : input.listOrEmpty(listKey, ItemStackWithSlot.CODEC)) {
                bySlot.put(e.slot(), e.stack());
            }
            ItemStack current = bySlot.getOrDefault(slot, ItemStack.EMPTY);
            if (current.isEmpty()) return Result.error("Empty offline slot " + slot);

            ItemStack replacement;
            if (nested.isEmpty()) {
                replacement = newStack.copy();
                replacement.setCount(current.getCount());
            } else {
                replacement = replaceNested(current.copy(), nested, newStack);
                if (replacement == null) return Result.error("Nested replace failed");
            }
            bySlot.put(slot, replacement);

            // Write inventory list back
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
            var listOut = output.list(listKey, ItemStackWithSlot.CODEC);
            for (Map.Entry<Integer, ItemStack> e : bySlot.entrySet()) {
                if (!e.getValue().isEmpty()) {
                    listOut.add(new ItemStackWithSlot(e.getKey(), e.getValue()));
                }
            }
            CompoundTag listTag = output.buildResult();
            // Merge list into player tag
            if (listTag.contains(listKey)) {
                tag.put(listKey, listTag.get(listKey));
            }
            writePlayerTag(io, nameAndId, tag);
            return Result.ok("Updated offline " + listKey + " slot " + slot);
        } catch (Exception e) {
            return Result.error("Offline modify failed: " + e.getMessage());
        }
    }

    private static Result replaceOfflineEquipment(
            MinecraftServer server, PlayerDataStorage io, NameAndId nameAndId, CompoundTag tag,
            String equipName, List<String> nested, ItemStack newStack
    ) {
        try {
            HolderLookup.Provider registries = server.registryAccess();
            ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING, registries, tag);
            var equipOpt = input.read("equipment", net.minecraft.world.entity.EntityEquipment.CODEC);
            if (equipOpt.isEmpty()) {
                return Result.error("No equipment data offline");
            }
            net.minecraft.world.entity.EntityEquipment equip = equipOpt.get();
            EquipmentSlot slot = null;
            for (EquipmentSlot s : EquipmentSlot.values()) {
                if (s.getSerializedName().equals(equipName)) {
                    slot = s;
                    break;
                }
            }
            if (slot == null) return Result.error("Unknown equipment slot " + equipName);
            ItemStack current = equip.get(slot);
            if (current == null || current.isEmpty()) return Result.error("Empty equipment " + equipName);

            ItemStack replacement;
            if (nested.isEmpty()) {
                replacement = newStack.copy();
                replacement.setCount(current.getCount());
            } else {
                replacement = replaceNested(current.copy(), nested, newStack);
                if (replacement == null) return Result.error("Nested equip replace failed");
            }
            equip.set(slot, replacement);

            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
            output.store("equipment", net.minecraft.world.entity.EntityEquipment.CODEC, equip);
            CompoundTag eqTag = output.buildResult();
            if (eqTag.contains("equipment")) {
                tag.put("equipment", eqTag.get("equipment"));
            }
            writePlayerTag(io, nameAndId, tag);
            return Result.ok("Updated offline equipment " + equipName);
        } catch (Exception e) {
            return Result.error("Offline equipment modify failed: " + e.getMessage());
        }
    }

    private static void writePlayerTag(PlayerDataStorage io, NameAndId nameAndId, CompoundTag tag) throws Exception {
        File dir = io.getPlayerDir();
        Path playerDirPath = dir.toPath();
        String id = nameAndId.id().toString();
        Path tmp = Files.createTempFile(playerDirPath, id + "-", ".dat");
        NbtIo.writeCompressed(tag, tmp);
        Path real = playerDirPath.resolve(id + ".dat");
        Path old = playerDirPath.resolve(id + ".dat_old");
        net.minecraft.util.Util.safeReplaceFile(real, tmp, old);
    }

    /**
     * Replace nested path inside a parent stack; returns modified parent or null on failure.
     */
    private static ItemStack replaceNested(ItemStack parent, List<String> segments, ItemStack newStack) {
        if (segments.isEmpty()) return newStack;
        String seg = segments.get(0);
        List<String> rest = segments.subList(1, segments.size());

        if (seg.startsWith("container:")) {
            int idx = Integer.parseInt(seg.substring(10));
            ItemContainerContents contents = parent.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
            int size = Math.max(contents.getSlots(), idx + 1);
            List<ItemStack> items = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                items.add(i < contents.getSlots() ? contents.getStackInSlot(i) : ItemStack.EMPTY);
            }
            ItemStack current = items.get(idx);
            if (current.isEmpty() && rest.isEmpty()) {
                // allow setting empty slot
            }
            ItemStack replacement;
            if (rest.isEmpty()) {
                replacement = newStack.copy();
                if (!current.isEmpty()) replacement.setCount(current.getCount());
            } else {
                if (current.isEmpty()) return null;
                replacement = replaceNested(current.copy(), rest, newStack);
                if (replacement == null) return null;
            }
            items.set(idx, replacement);
            parent.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            return parent;
        }

        if (seg.startsWith("bundle:")) {
            int idx = Integer.parseInt(seg.substring(7));
            BundleContents bundle = parent.getOrDefault(DataComponents.BUNDLE_CONTENTS, BundleContents.EMPTY);
            List<ItemStack> items = new ArrayList<>(bundle.itemCopyStream().toList());
            while (items.size() <= idx) items.add(ItemStack.EMPTY);
            ItemStack current = items.get(idx);
            ItemStack replacement;
            if (rest.isEmpty()) {
                replacement = newStack.copy();
                if (!current.isEmpty()) replacement.setCount(current.getCount());
            } else {
                if (current.isEmpty()) return null;
                replacement = replaceNested(current.copy(), rest, newStack);
                if (replacement == null) return null;
            }
            items.set(idx, replacement);
            parent.set(DataComponents.BUNDLE_CONTENTS, rebuildBundleContents(items));
            return parent;
        }

        if (seg.startsWith("sbp:")) {
            int idx = Integer.parseInt(seg.substring(4));
            return replaceSophisticatedSlot(parent, idx, rest, newStack);
        }

        return null;
    }

    /**
     * Rebuild bundle contents across MC versions:
     * 26.x uses ItemStackTemplate list; 1.21.x uses List&lt;ItemStack&gt;.
     */
    private static BundleContents rebuildBundleContents(List<ItemStack> items) {
        List<ItemStack> nonEmpty = new ArrayList<>();
        for (ItemStack s : items) {
            if (s != null && !s.isEmpty()) nonEmpty.add(s);
        }
        // 1.21.x: BundleContents(List<ItemStack>)
        try {
            return BundleContents.class.getConstructor(List.class).newInstance(nonEmpty);
        } catch (ReflectiveOperationException ignored) {
        }
        // 26.x: BundleContents(List<ItemStackTemplate>)
        try {
            Class<?> templateCl = Class.forName("net.minecraft.world.item.ItemStackTemplate");
            List<Object> templates = new ArrayList<>();
            var from = templateCl.getMethod("fromNonEmptyStack", ItemStack.class);
            for (ItemStack s : nonEmpty) {
                templates.add(from.invoke(null, s));
            }
            return (BundleContents) BundleContents.class.getConstructor(List.class).newInstance(templates);
        } catch (ReflectiveOperationException e) {
            EnchantMaster.LOGGER.warn("Could not rebuild bundle contents: {}", e.toString());
            return BundleContents.EMPTY;
        }
    }

    private static ItemStack replaceSophisticatedSlot(
            ItemStack backpack, int slot, List<String> rest, ItemStack newStack
    ) {
        try {
            Class<?> wrapperCl = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackWrapper");
            Object wrapper = wrapperCl.getMethod("fromStack", ItemStack.class).invoke(null, backpack);
            Object handler = wrapper.getClass().getMethod("getInventoryHandler").invoke(wrapper);
            ItemStack current = (ItemStack) handler.getClass().getMethod("getInternalStack", int.class).invoke(handler, slot);
            ItemStack replacement;
            if (rest.isEmpty()) {
                replacement = newStack.copy();
                if (current != null && !current.isEmpty()) replacement.setCount(current.getCount());
            } else {
                if (current == null || current.isEmpty()) return null;
                replacement = replaceNested(current.copy(), rest, newStack);
                if (replacement == null) return null;
            }
            handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(handler, slot, replacement);
            try {
                handler.getClass().getMethod("saveInventory").invoke(handler);
            } catch (NoSuchMethodException ignored) {
            }
            // Backpack stack may be updated on wrapper
            try {
                ItemStack updated = (ItemStack) wrapper.getClass().getMethod("getBackpack").invoke(wrapper);
                if (updated != null && !updated.isEmpty()) return updated;
            } catch (Exception ignored) {
            }
            return backpack;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("SBP replace failed: {}", e.toString());
            return null;
        }
    }

    private record PathParts(List<String> segments) {
        static PathParts parse(String path) {
            List<String> segs = new ArrayList<>();
            for (String p : path.split("/")) {
                if (!p.isBlank()) segs.add(p.trim());
            }
            return new PathParts(segs);
        }
    }

    /** Tiny helper to detect any attribute modifiers. */
    private static final class ItemAttributeProbe {
        static ItemAttributeProbe fromStack(ItemStack stack) {
            ItemAttributeProbe p = new ItemAttributeProbe();
            try {
                var mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS,
                        net.minecraft.world.item.component.ItemAttributeModifiers.EMPTY);
                p.any = mods != null && !mods.modifiers().isEmpty();
            } catch (Exception e) {
                p.any = false;
            }
            return p;
        }

        boolean any;

        boolean hasAny() {
            return any;
        }
    }

    public record Result(boolean success, String message) {
        public static Result ok(String message) {
            return new Result(true, message);
        }

        public static Result error(String message) {
            return new Result(false, message);
        }
    }
}
