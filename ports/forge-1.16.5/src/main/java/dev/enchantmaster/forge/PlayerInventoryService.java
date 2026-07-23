package dev.enchantmaster.forge;

import net.minecraft.util.text.ITextComponent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.audit.AuditActor;
import dev.enchantmaster.audit.AuditLog;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.util.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.world.storage.FolderName;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Online player inventory + ender chest; offline via playerdata Inventory/EnderItems NBT.
 * Nested containers/bundles/SBP not fully supported on 1.16.5 (documented limitation).
 */
public final class PlayerInventoryService {
    private PlayerInventoryService() {
    }

    public static JsonArray listKnownPlayers(MinecraftServer server) {
        Map<UUID, JsonObject> byId = new HashMap<UUID, JsonObject>();
        for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
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
            Path dir = server.getWorldPath(FolderName.PLAYER_DATA_DIR);
            File[] files = dir.toFile().listFiles((d, name) -> name.endsWith(".dat") && !name.endsWith("_old.dat"));
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
        ServerPlayerEntity online = server.getPlayerList().getPlayer(uuid);
        if (online != null) return Optional.of(online.getGameProfile().getName());
        try {
            Path cache = server.getServerDirectory().toPath().resolve("usercache.json");
            if (Files.isRegularFile(cache)) {
                byte[] bytes = Files.readAllBytes(cache);
                String json = new String(bytes, StandardCharsets.UTF_8);
                JsonArray arr = new JsonParser().parse(json).getAsJsonArray();
                for (int i = 0; i < arr.size(); i++) {
                    if (!arr.get(i).isJsonObject()) continue;
                    JsonObject o = arr.get(i).getAsJsonObject();
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
        Optional<String> name = resolveName(server, uuid);
        if (name.isPresent()) result.addProperty("name", name.get());
        ServerPlayerEntity online = server.getPlayerList().getPlayer(uuid);
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

    private static void scanOnline(ServerPlayerEntity player, JsonArray out, boolean forgeableOnly) {
        PlayerInventory inv = player.inventory;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            collect(stack, "inv:" + i, "Inventory slot " + i, out, forgeableOnly);
        }
        for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
            ItemStack stack = player.getEnderChestInventory().getItem(i);
            if (stack.isEmpty()) continue;
            collect(stack, "ender:" + i, "Ender Chest #" + i, out, forgeableOnly);
        }
    }

    private static void scanOffline(MinecraftServer server, UUID uuid, JsonArray out, boolean forgeableOnly) {
        try {
            Path file = server.getWorldPath(FolderName.PLAYER_DATA_DIR).resolve(uuid + ".dat");
            if (!Files.isRegularFile(file)) return;
            CompoundNBT tag = CompressedStreamTools.readCompressed(file.toFile());
            if (tag == null) return;
            readList(tag, "Inventory", "inv:", "Inventory slot ", out, forgeableOnly);
            readList(tag, "EnderItems", "ender:", "Ender Chest #", out, forgeableOnly);
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Offline inventory read failed: {}", e.toString());
        }
    }

    private static void readList(
            CompoundNBT tag, String key, String pathPrefix, String labelPrefix,
            JsonArray out, boolean forgeableOnly
    ) {
        if (!tag.contains(key)) return;
        ListNBT list = tag.getList(key, 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundNBT entry = list.getCompound(i);
            int slot = entry.contains("Slot") ? entry.getByte("Slot") & 0xFF : i;
            ItemStack stack = ItemStack.of(entry);
            if (stack.isEmpty()) continue;
            collect(stack, pathPrefix + slot, labelPrefix + slot, out, forgeableOnly);
        }
    }

    private static void collect(ItemStack stack, String path, String location, JsonArray out, boolean forgeableOnly) {
        if (stack.isEmpty()) return;
        boolean modifiable = isModifiable(stack);
        if (!forgeableOnly || modifiable) {
            out.add(stackToJson(stack, path, location, modifiable));
        }
    }

    private static boolean isModifiable(ItemStack stack) {
        try {
            if (ItemCatalog.isBookLike(stack.getItem())) return true;
            if (stack.isEnchanted()) return true;
            return ItemCatalog.isForgeableItem(stack, stack.getItem(),
                    new java.util.ArrayList<Enchantment>(ForgeRegistries.ENCHANTMENTS.getValues()));
        } catch (Exception e) {
            return stack.isEnchanted();
        }
    }

    private static JsonObject stackToJson(ItemStack stack, String path, String location, boolean modifiable) {
        JsonObject o = new JsonObject();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
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

        // Custom name + lore from classic display NBT (1.16)
        try {
            if (stack.hasTag() && stack.getTag().contains("display", 10)) {
                CompoundNBT display = stack.getTag().getCompound("display");
                if (display.contains("Name", 8)) {
                    try {
                        String nameJson = display.getString("Name");
                        ITextComponent cn = ITextComponent.Serializer.fromJson(nameJson);
                        if (cn != null) {
                            o.add("customName", StyledText.fromComponent(cn, false).toJson());
                        }
                    } catch (Exception ignored) {
                    }
                }
                JsonArray loreArr = new JsonArray();
                if (display.contains("Lore", 9)) {
                    ListNBT loreList = display.getList("Lore", 8);
                    for (int i = 0; i < loreList.size(); i++) {
                        try {
                            String lineJson = loreList.getString(i);
                            ITextComponent line = ITextComponent.Serializer.fromJson(lineJson);
                            if (line == null) continue;
                            StyledText styled = StyledText.fromComponent(line, true);
                            if (styled.text() == null || styled.text().trim().isEmpty()) continue;
                            loreArr.add(styled.toJson());
                        } catch (Exception ignored) {
                        }
                    }
                }
                o.add("lore", loreArr);
            } else {
                o.add("lore", new JsonArray());
            }
        } catch (Exception e) {
            o.add("lore", new JsonArray());
        }

        JsonArray enchants = new JsonArray();
        try {
            Map<Enchantment, Integer> map = EnchantmentHelper.getEnchantments(stack);
            for (Map.Entry<Enchantment, Integer> entry : map.entrySet()) {
                try {
                    JsonObject e = new JsonObject();
                    ResourceLocation eid = ForgeRegistries.ENCHANTMENTS.getKey(entry.getKey());
                    e.addProperty("id", eid != null ? eid.toString() : "?");
                    e.addProperty("level", entry.getValue().intValue());
                    e.addProperty("name", new net.minecraft.util.text.TranslationTextComponent(
                            entry.getKey().getDescriptionId()).getString());
                    e.addProperty("maxLevel", entry.getKey().getMaxLevel());
                    enchants.add(e);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        o.add("enchantments", enchants);
        o.add("attributes", new JsonArray());
        return o;
    }

    public static Result modifyItem(MinecraftServer server, UUID uuid, String path, ForgeRequest request) {
        return modifyItem(server, uuid, path, request, null);
    }

    public static Result modifyItem(
            MinecraftServer server, UUID uuid, String path, ForgeRequest request, AuditActor actor
    ) {
        String targetName = resolveName(server, uuid).orElse(uuid.toString());
        if (path == null || path.trim().isEmpty()) {
            Result err = Result.error("path is required");
            audit(actor, targetName, uuid, path, request, err);
            return err;
        }
        // Nested paths not supported on 1.16.5 minimal port
        if (path.contains("/")) {
            Result err = Result.error("Nested inventory paths are not supported on Forge 1.16.5 port");
            audit(actor, targetName, uuid, path, request, err);
            return err;
        }
        ServerPlayerEntity online = server.getPlayerList().getPlayer(uuid);
        try {
            ItemStack original;
            if (online != null) {
                original = getTopOnline(online, path);
            } else {
                original = getTopOffline(server, uuid, path);
            }
            if (original == null || original.isEmpty()) {
                Result err = Result.error("No item at path " + path);
                audit(actor, targetName, uuid, path, request, err);
                return err;
            }
            ItemStackBuilder.Result built = ItemStackBuilder.applyToExisting(server, original, request);
            if (!built.success) {
                Result err = Result.error(built.error);
                audit(actor, targetName, uuid, path, request, err);
                return err;
            }
            ItemStack neu = built.stack;
            neu.setCount(original.getCount());
            Result result;
            if (online != null) {
                setTopOnline(online, path, neu);
                online.containerMenu.broadcastChanges();
                result = Result.ok("Updated " + path);
            } else {
                result = writeOfflineTop(server, uuid, path, neu);
            }
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
        AuditLog.inventoryModify(actor, targetName, uuid.toString(), path, req, result.success, result.message);
    }

    private static ItemStack getTopOnline(ServerPlayerEntity player, String seg) {
        if (seg.startsWith("inv:")) {
            return player.inventory.getItem(Integer.parseInt(seg.substring(4)));
        }
        if (seg.startsWith("ender:")) {
            return player.getEnderChestInventory().getItem(Integer.parseInt(seg.substring(6)));
        }
        return ItemStack.EMPTY;
    }

    private static void setTopOnline(ServerPlayerEntity player, String seg, ItemStack stack) {
        if (seg.startsWith("inv:")) {
            player.inventory.setItem(Integer.parseInt(seg.substring(4)), stack);
        } else if (seg.startsWith("ender:")) {
            player.getEnderChestInventory().setItem(Integer.parseInt(seg.substring(6)), stack);
        }
    }

    private static ItemStack getTopOffline(MinecraftServer server, UUID uuid, String seg) {
        try {
            Path file = server.getWorldPath(FolderName.PLAYER_DATA_DIR).resolve(uuid + ".dat");
            if (!Files.isRegularFile(file)) return ItemStack.EMPTY;
            CompoundNBT tag = CompressedStreamTools.readCompressed(file.toFile());
            if (tag == null) return ItemStack.EMPTY;
            String listKey = seg.startsWith("ender:") ? "EnderItems" : "Inventory";
            int slot = Integer.parseInt(seg.substring(seg.indexOf(':') + 1));
            ListNBT list = tag.getList(listKey, 10);
            for (int i = 0; i < list.size(); i++) {
                CompoundNBT entry = list.getCompound(i);
                int s = entry.contains("Slot") ? entry.getByte("Slot") & 0xFF : i;
                if (s == slot) {
                    return ItemStack.of(entry);
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Offline resolve: {}", e.toString());
        }
        return ItemStack.EMPTY;
    }

    private static Result writeOfflineTop(MinecraftServer server, UUID uuid, String seg, ItemStack stack) {
        try {
            Path playerDir = server.getWorldPath(FolderName.PLAYER_DATA_DIR);
            Path file = playerDir.resolve(uuid + ".dat");
            if (!Files.isRegularFile(file)) return Result.error("No playerdata");
            CompoundNBT tag = CompressedStreamTools.readCompressed(file.toFile());
            if (tag == null) return Result.error("Empty playerdata");
            String listKey = seg.startsWith("ender:") ? "EnderItems" : "Inventory";
            int slot = Integer.parseInt(seg.substring(seg.indexOf(':') + 1));
            ListNBT list = tag.contains(listKey) ? tag.getList(listKey, 10) : new ListNBT();
            ListNBT neu = new ListNBT();
            boolean replaced = false;
            for (int i = 0; i < list.size(); i++) {
                CompoundNBT entry = list.getCompound(i).copy();
                int s = entry.contains("Slot") ? entry.getByte("Slot") & 0xFF : i;
                if (s == slot) {
                    CompoundNBT saved = stack.save(new CompoundNBT());
                    saved.putByte("Slot", (byte) slot);
                    neu.add(saved);
                    replaced = true;
                } else {
                    neu.add(entry);
                }
            }
            if (!replaced && !stack.isEmpty()) {
                CompoundNBT saved = stack.save(new CompoundNBT());
                saved.putByte("Slot", (byte) slot);
                neu.add(saved);
            }
            tag.put(listKey, neu);
            File tmp = File.createTempFile(uuid + "-", ".dat", playerDir.toFile());
            CompressedStreamTools.writeCompressed(tag, tmp);
            File real = file.toFile();
            File old = playerDir.resolve(uuid + ".dat_old").toFile();
            if (old.exists()) old.delete();
            if (real.exists()) real.renameTo(old);
            if (!tmp.renameTo(real)) {
                Files.copy(tmp.toPath(), real.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
            }
            return Result.ok("Updated offline " + seg);
        } catch (Exception e) {
            return Result.error("Offline write failed: " + e.getMessage());
        }
    }

    public static final class Result {
        public final boolean success;
        public final String message;

        private Result(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static Result ok(String m) {
            return new Result(true, m);
        }

        public static Result error(String m) {
            return new Result(false, m);
        }

        public boolean success() {
            return success;
        }

        public String message() {
            return message;
        }
    }
}
