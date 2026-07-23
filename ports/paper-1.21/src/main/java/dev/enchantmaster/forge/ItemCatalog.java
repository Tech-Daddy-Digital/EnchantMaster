package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ItemCatalog {
    private ItemCatalog() {
    }

    public static JsonObject stats() {
        JsonObject o = new JsonObject();
        o.addProperty("items", Material.values().length);
        o.addProperty("enchantments", listEnchantments().size());
        o.addProperty("attributes", listAttributes().size());
        o.addProperty("onlinePlayers", Bukkit.getOnlinePlayers().size());
        return o;
    }

    public static JsonArray items(String query, String namespace, int limit, int offset) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String ns = namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
        List<Material> mats = new ArrayList<>();
        for (Material m : Material.values()) {
            if (!m.isItem() || m.isAir()) continue;
            NamespacedKey key = m.getKey();
            if (!ns.isBlank() && !key.getNamespace().equalsIgnoreCase(ns)) continue;
            String id = key.toString();
            String name = pretty(m.name());
            if (!q.isBlank() && !id.toLowerCase(Locale.ROOT).contains(q) && !name.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            mats.add(m);
        }
        mats.sort(Comparator.comparing(m -> m.getKey().toString()));
        JsonArray arr = new JsonArray();
        int to = Math.min(mats.size(), Math.max(0, offset) + Math.max(1, limit));
        for (int i = Math.max(0, offset); i < to; i++) {
            Material m = mats.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", m.getKey().toString());
            o.addProperty("name", pretty(m.name()));
            o.addProperty("namespace", m.getKey().getNamespace());
            o.addProperty("forgeable", isForgeable(m));
            arr.add(o);
        }
        return arr;
    }

    public static JsonArray enchantments(String query, String itemId, boolean override) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        ItemStack probe = null;
        if (itemId != null && !itemId.isBlank()) {
            Material m = material(itemId).orElse(null);
            if (m != null) probe = new ItemStack(m);
        }
        JsonArray arr = new JsonArray();
        for (Enchantment ench : listEnchantments()) {
            String id = ench.getKey().toString();
            String name = pretty(ench.getKey().getKey());
            if (!q.isBlank() && !id.toLowerCase(Locale.ROOT).contains(q) && !name.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            boolean can = override || probe == null || canApply(ench, probe) || isBookLike(probe.getType());
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            o.addProperty("maxLevel", ench.getMaxLevel());
            o.addProperty("canApply", can);
            arr.add(o);
        }
        return arr;
    }

    public static JsonArray attributes(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        JsonArray arr = new JsonArray();
        for (Attribute attr : listAttributes()) {
            NamespacedKey key = attr.getKey();
            String id = key.toString();
            String name = pretty(key.getKey());
            if (!q.isBlank() && !id.toLowerCase(Locale.ROOT).contains(q) && !name.toLowerCase(Locale.ROOT).contains(q)) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("name", name);
            arr.add(o);
        }
        return arr;
    }

    public static JsonObject meta() {
        JsonObject o = new JsonObject();
        JsonArray slots = new JsonArray();
        for (String s : List.of("ANY", "MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET", "HAND", "ARMOR")) {
            slots.add(s);
        }
        o.add("equipmentSlots", slots);
        JsonArray ops = new JsonArray();
        for (String s : List.of("ADD_VALUE", "ADD_MULTIPLIED_BASE", "ADD_MULTIPLIED_TOTAL",
                "ADDITION", "MULTIPLY_BASE", "MULTIPLY_TOTAL")) {
            ops.add(s);
        }
        o.add("attributeOperations", ops);
        return o;
    }

    public static JsonArray onlinePlayers() {
        JsonArray arr = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", p.getUniqueId().toString());
            o.addProperty("name", p.getName());
            o.addProperty("online", true);
            arr.add(o);
        }
        return arr;
    }

    public static boolean isForgeable(Material m) {
        if (m == null || !m.isItem() || m.isAir()) return false;
        if (isBookLike(m)) return true;
        ItemStack stack = new ItemStack(m);
        for (Enchantment e : listEnchantments()) {
            if (canApply(e, stack)) return true;
        }
        return m.getMaxDurability() > 0 || m.name().contains("SWORD") || m.name().contains("AXE")
                || m.name().contains("PICKAXE") || m.name().contains("SHOVEL") || m.name().contains("HOE")
                || m.name().contains("HELMET") || m.name().contains("CHESTPLATE")
                || m.name().contains("LEGGINGS") || m.name().contains("BOOTS")
                || m.name().contains("BOW") || m.name().contains("CROSSBOW")
                || m.name().contains("TRIDENT") || m.name().contains("MACE");
    }

    public static boolean isBookLike(Material m) {
        return m == Material.BOOK || m == Material.ENCHANTED_BOOK || m == Material.WRITABLE_BOOK;
    }

    public static boolean canApply(Enchantment ench, ItemStack stack) {
        try {
            return ench.canEnchantItem(stack);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean areCompatible(Enchantment a, Enchantment b) {
        try {
            return a.conflictsWith(b) == false;
        } catch (Exception e) {
            return true;
        }
    }

    public static Optional<Material> material(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        NamespacedKey key = NamespacedKey.fromString(id.contains(":") ? id : "minecraft:" + id);
        if (key == null) return Optional.empty();
        Material m = Registry.MATERIAL.get(key);
        if (m == null || !m.isItem()) {
            // fallback
            try {
                m = Material.matchMaterial(id.contains(":") ? id.split(":", 2)[1] : id);
            } catch (Exception ignored) {
            }
        }
        return Optional.ofNullable(m);
    }

    public static Optional<Enchantment> findEnchantment(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        NamespacedKey key = NamespacedKey.fromString(id.contains(":") ? id : "minecraft:" + id);
        if (key == null) return Optional.empty();
        Enchantment e = Registry.ENCHANTMENT.get(key);
        return Optional.ofNullable(e);
    }

    public static Optional<Attribute> findAttribute(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        NamespacedKey key = NamespacedKey.fromString(id.contains(":") ? id : "minecraft:" + id);
        if (key == null) return Optional.empty();
        Attribute a = Registry.ATTRIBUTE.get(key);
        return Optional.ofNullable(a);
    }

    public static List<Enchantment> listEnchantments() {
        List<Enchantment> list = new ArrayList<>();
        try {
            Registry<Enchantment> reg = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT);
            for (Enchantment e : reg) {
                list.add(e);
            }
        } catch (Throwable t) {
            for (Enchantment e : Registry.ENCHANTMENT) {
                list.add(e);
            }
        }
        list.sort(Comparator.comparing(e -> e.getKey().toString()));
        return list;
    }

    public static List<Attribute> listAttributes() {
        List<Attribute> list = new ArrayList<>();
        for (Attribute a : Registry.ATTRIBUTE) {
            list.add(a);
        }
        list.sort(Comparator.comparing(a -> a.getKey().toString()));
        return list;
    }

    public static String pretty(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (s.isEmpty()) return raw;
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : s.toCharArray()) {
            if (cap && Character.isLetter(c)) {
                sb.append(Character.toUpperCase(c));
                cap = false;
            } else {
                sb.append(c);
                if (c == ' ') cap = true;
            }
        }
        return sb.toString();
    }

    public static JsonObject stackSummary(ItemStack stack) {
        JsonObject o = new JsonObject();
        if (stack == null || stack.getType().isAir()) {
            o.addProperty("empty", true);
            return o;
        }
        o.addProperty("empty", false);
        o.addProperty("id", stack.getType().getKey().toString());
        o.addProperty("count", stack.getAmount());
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            o.add("customName", StyledText.fromComponent(meta.displayName(), false).toJson());
        } else {
            o.add("customName", new StyledText("", null, false, false).toJson());
        }
        JsonArray lore = new JsonArray();
        if (meta != null && meta.hasLore() && meta.lore() != null) {
            for (var line : meta.lore()) {
                lore.add(StyledText.fromComponent(line, true).toJson());
            }
        }
        o.add("lore", lore);
        JsonArray enchants = new JsonArray();
        if (meta instanceof EnchantmentStorageMeta storage) {
            storage.getStoredEnchants().forEach((ench, level) -> {
                JsonObject e = new JsonObject();
                e.addProperty("id", ench.getKey().toString());
                e.addProperty("level", level);
                enchants.add(e);
            });
        } else if (meta != null) {
            meta.getEnchants().forEach((ench, level) -> {
                JsonObject e = new JsonObject();
                e.addProperty("id", ench.getKey().toString());
                e.addProperty("level", level);
                enchants.add(e);
            });
        }
        o.add("enchantments", enchants);
        JsonArray attrs = new JsonArray();
        if (meta != null && meta.hasAttributeModifiers() && meta.getAttributeModifiers() != null) {
            meta.getAttributeModifiers().forEach((attr, mod) -> {
                JsonObject a = new JsonObject();
                a.addProperty("id", attr.getKey().toString());
                a.addProperty("amount", mod.getAmount());
                a.addProperty("operation", mod.getOperation().name());
                a.addProperty("slot", mod.getSlotGroup() != null ? mod.getSlotGroup().toString() : "ANY");
                attrs.add(a);
            });
        }
        o.add("attributes", attrs);
        return o;
    }
}
