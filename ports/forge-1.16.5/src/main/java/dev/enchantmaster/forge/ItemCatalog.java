package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.enchantmaster.EnchantMaster;
import net.minecraft.util.text.LanguageMap;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.entity.ai.attributes.Attribute;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.enchantment.Enchantment;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry-driven catalogs using ForgeRegistries (1.16.5).
 */
public final class ItemCatalog {
    private static final int FORGEABLE_LOGIC_VERSION = 1;
    private static volatile int forgeableLogicVersion;
    private static volatile Set<Item> forgeableCache;
    private static volatile CatalogStats lastStats;

    private ItemCatalog() {
    }

    public static void invalidateCache() {
        forgeableCache = null;
        lastStats = null;
        forgeableLogicVersion = 0;
    }

    public static JsonObject catalogStats(MinecraftServer server) {
        forgeableItems();
        CatalogStats stats = lastStats;
        JsonObject obj = new JsonObject();
        if (stats == null) {
            obj.addProperty("error", "stats unavailable");
            return obj;
        }
        obj.addProperty("totalItemsRegistered", stats.totalItemsRegistered);
        obj.addProperty("forgeableItems", stats.forgeableItems);
        obj.addProperty("enchantments", stats.enchantments);
        obj.addProperty("attributes", stats.attributes);
        obj.addProperty("skippedItems", stats.skippedItems);
        obj.addProperty("skippedEnchantments", stats.skippedEnchantments);
        obj.addProperty("nonMinecraftItemNamespaces", stats.itemNamespaces.size());
        obj.addProperty("nonMinecraftEnchantNamespaces", stats.enchantNamespaces.size());
        JsonObject itemNs = new JsonObject();
        for (Map.Entry<String, Integer> e : stats.itemNamespaces.entrySet()) {
            itemNs.addProperty(e.getKey(), e.getValue());
        }
        obj.add("forgeableItemNamespaces", itemNs);
        JsonObject enchNs = new JsonObject();
        for (Map.Entry<String, Integer> e : stats.enchantNamespaces.entrySet()) {
            enchNs.addProperty(e.getKey(), e.getValue());
        }
        obj.add("enchantmentNamespaces", enchNs);
        JsonArray skipped = new JsonArray();
        for (String s : stats.skipSamples) {
            skipped.add(s);
        }
        obj.add("skipSamples", skipped);
        return obj;
    }

    public static JsonArray listItems(MinecraftServer server, String query, String namespace, int limit, int offset) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String ns = namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
        Set<Item> forgeable = forgeableItems();

        List<JsonObject> candidates = new ArrayList<JsonObject>();
        Map<String, Integer> nameCounts = new HashMap<String, Integer>();
        for (Item item : forgeable) {
            try {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id == null) continue;
                if (!ns.isEmpty() && !id.getNamespace().equalsIgnoreCase(ns)) continue;

                String baseName = safeItemName(item);
                if (!q.isEmpty()) {
                    String hay = (id.toString() + " " + baseName + " " + sourceLabel(id.getNamespace())).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }

                Integer prev = nameCounts.get(baseName.toLowerCase(Locale.ROOT));
                nameCounts.put(baseName.toLowerCase(Locale.ROOT), Integer.valueOf(prev == null ? 1 : prev.intValue() + 1));

                JsonObject obj = new JsonObject();
                obj.addProperty("id", id.toString());
                obj.addProperty("baseName", baseName);
                obj.addProperty("namespace", id.getNamespace());
                obj.addProperty("path", id.getPath());
                obj.addProperty("source", sourceLabel(id.getNamespace()));
                obj.addProperty("iconUrl", "/api/assets/item/" + id.getNamespace() + "/" + id.getPath() + ".png");
                candidates.add(obj);
            } catch (Exception e) {
                EnchantMaster.LOGGER.debug("Skipping item in catalog: {}", e.toString());
            }
        }

        List<JsonObject> results = new ArrayList<JsonObject>();
        for (JsonObject obj : candidates) {
            String baseName = obj.get("baseName").getAsString();
            String source = obj.get("source").getAsString();
            String display = baseName;
            Integer count = nameCounts.get(baseName.toLowerCase(Locale.ROOT));
            if (count != null && count.intValue() > 1) {
                display = baseName + " - " + source;
            }
            obj.addProperty("name", display);
            results.add(obj);
        }

        results.sort(new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                return String.CASE_INSENSITIVE_ORDER.compare(
                        a.get("name").getAsString(), b.get("name").getAsString());
            }
        });
        return slice(results, limit, offset);
    }

    public static String sourceLabel(String namespace) {
        if (namespace == null || namespace.trim().isEmpty() || "minecraft".equals(namespace)) {
            return "Vanilla Minecraft";
        }
        try {
            return ModList.get()
                    .getModContainerById(namespace)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(namespace);
        } catch (Exception e) {
            return namespace;
        }
    }

    public static JsonArray listEnchantments(MinecraftServer server, String query, String forItemId, boolean overrideLimits) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        ItemStack probe = resolveProbeStack(forItemId);
        boolean ignoreCompatibility = overrideLimits;

        List<JsonObject> results = new ArrayList<JsonObject>();
        for (Enchantment enchantment : ForgeRegistries.ENCHANTMENTS) {
            try {
                ResourceLocation id = ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
                if (id == null) continue;
                String name = safeEnchantmentName(id, enchantment);
                String flavor = resolveEnchantmentFlavor(id, enchantment);
                if (!q.isEmpty()) {
                    String hay = (id.toString() + " " + name + " " + flavor).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }

                boolean compatible = true;
                if (!probe.isEmpty() && !ignoreCompatibility) {
                    compatible = canApply(enchantment, probe);
                }

                int maxLevel = 1;
                try {
                    maxLevel = Math.max(1, enchantment.getMaxLevel());
                } catch (Exception ignored) {
                }

                JsonObject obj = new JsonObject();
                obj.addProperty("id", id.toString());
                obj.addProperty("name", name);
                obj.addProperty("description", flavor);
                obj.addProperty("maxLevel", maxLevel);
                obj.addProperty("compatible", compatible);
                obj.addProperty("namespace", id.getNamespace());
                results.add(obj);
            } catch (Exception e) {
                EnchantMaster.LOGGER.debug("Skipping enchantment in catalog: {}", e.toString());
            }
        }

        results.sort(new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                return a.get("id").getAsString().compareTo(b.get("id").getAsString());
            }
        });
        JsonArray arr = new JsonArray();
        for (JsonObject o : results) arr.add(o);
        return arr;
    }

    private static String safeEnchantmentName(ResourceLocation id, Enchantment enchantment) {
        try {
            String fromComponent = new TranslationTextComponent(enchantment.getDescriptionId()).getString();
            if (isUsableDisplayName(fromComponent, id)) {
                return fromComponent;
            }
        } catch (Exception ignored) {
        }
        String translated = translateKey(enchantment.getDescriptionId());
        if (isUsableDisplayName(translated, id)) {
            return translated;
        }
        return humanizePath(id);
    }

    private static String resolveEnchantmentFlavor(ResourceLocation id, Enchantment enchantment) {
        try {
            List<String> keys = new ArrayList<String>();
            String baseKey = enchantment.getDescriptionId();
            keys.add(baseKey + ".desc");
            keys.add(baseKey + ".description");
            keys.add("descriptions.enchantment." + id.getNamespace() + "." + id.getPath());
            keys.add("enchantment." + id.getNamespace() + "." + id.getPath() + ".desc");
            for (String key : keys) {
                String value = translateKey(key);
                if (isUsableDisplayName(value, id) && !value.equals(key)) {
                    return value;
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("No flavor text for {}: {}", id, e.toString());
        }
        return "";
    }

    private static String translateKey(String key) {
        if (key == null || key.trim().isEmpty()) return "";
        try {
            LanguageMap lang = LanguageMap.getInstance();
            if (lang.has(key)) {
                String v = lang.getOrDefault(key);
                if (v != null && !v.trim().isEmpty() && !v.equals(key)) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            String via = new TranslationTextComponent(key).getString();
            if (via != null && !via.trim().isEmpty() && !via.equals(key)) {
                return via;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    static boolean isUsableDisplayName(String name, ResourceLocation id) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        if (id != null) {
            if (n.equalsIgnoreCase(id.toString())) return false;
            if (n.equalsIgnoreCase(id.getPath())) return false;
            if (n.equalsIgnoreCase(id.getNamespace() + ":" + id.getPath())) return false;
        }
        if (n.startsWith("item.") || n.startsWith("block.") || n.startsWith("enchantment.")) {
            if (!n.contains(" ")) return false;
        }
        return true;
    }

    static String humanizePath(ResourceLocation id) {
        if (id == null) return "Unknown";
        String path = id.getPath();
        if (path == null || path.trim().isEmpty()) return id.toString();
        String[] parts = path.split("[_/]+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) {
                sb.append(p.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        return sb.length() > 0 ? sb.toString() : id.toString();
    }

    public static JsonArray listAttributes(String query) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<JsonObject> results = new ArrayList<JsonObject>();

        for (Attribute attribute : ForgeRegistries.ATTRIBUTES) {
            try {
                ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
                if (id == null) continue;
                String translationKey = attribute.getDescriptionId();
                String display = humanAttributeName(attribute, id);
                if (!q.isEmpty()) {
                    String hay = (id.toString() + " " + display + " " + translationKey).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }
                JsonObject obj = new JsonObject();
                obj.addProperty("id", id.toString());
                obj.addProperty("name", display);
                obj.addProperty("translationKey", translationKey);
                obj.addProperty("namespace", id.getNamespace());
                results.add(obj);
            } catch (Exception e) {
                EnchantMaster.LOGGER.debug("Skipping attribute in catalog: {}", e.toString());
            }
        }

        results.sort(new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                return a.get("name").getAsString().compareTo(b.get("name").getAsString());
            }
        });
        JsonArray arr = new JsonArray();
        for (JsonObject o : results) arr.add(o);
        return arr;
    }

    public static JsonArray listRelevantAttributes(
            String itemId,
            List<EnchantLevel> enchantments,
            String query
    ) {
        // 1.16: enchantments don't carry attribute effect components like modern; return item defaults + full list filtered
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Map<String, RelevantAttribute> byId = new HashMap<String, RelevantAttribute>();

        try {
            ItemStack stack = resolveProbeStack(itemId);
            if (!stack.isEmpty()) {
                for (EquipmentSlotType slot : EquipmentSlotType.values()) {
                    try {
                        com.google.common.collect.Multimap<Attribute, AttributeModifier> mods =
                                stack.getAttributeModifiers(slot);
                        if (mods == null) continue;
                        for (Map.Entry<Attribute, AttributeModifier> entry : mods.entries()) {
                            ResourceLocation aid = ForgeRegistries.ATTRIBUTES.getKey(entry.getKey());
                            if (aid == null) continue;
                            String key = aid.toString();
                            RelevantAttribute ra = byId.get(key);
                            if (ra == null) {
                                ra = new RelevantAttribute(key, humanAttributeName(entry.getKey(), aid));
                                byId.put(key, ra);
                            }
                            ra.sources.add("item:" + slot.getName());
                            ra.amount = entry.getValue().getAmount();
                            ra.operation = operationName(entry.getValue().getOperation());
                            ra.slot = slot.getName();
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Failed reading item attributes for {}: {}", itemId, e.toString());
        }

        // If nothing found, fall back to common combat attributes
        if (byId.isEmpty()) {
            return listAttributes(query);
        }

        List<JsonObject> results = new ArrayList<JsonObject>();
        for (RelevantAttribute ra : byId.values()) {
            if (!q.isEmpty()) {
                String hay = (ra.id + " " + ra.name + " " + ra.sources).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("id", ra.id);
            obj.addProperty("name", ra.name);
            obj.addProperty("amount", ra.amount);
            obj.addProperty("operation", ra.operation != null ? ra.operation : "ADD_VALUE");
            obj.addProperty("slot", ra.slot != null ? ra.slot : "any");
            obj.addProperty("source", ra.sources.toString());
            obj.addProperty("label", ra.name);
            results.add(obj);
        }
        results.sort(new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                return a.get("name").getAsString().compareTo(b.get("name").getAsString());
            }
        });
        JsonArray arr = new JsonArray();
        for (JsonObject o : results) arr.add(o);
        return arr;
    }

    public static final class EnchantLevel {
        public final String id;
        public final int level;

        public EnchantLevel(String id, int level) {
            this.id = id;
            this.level = level;
        }
    }

    private static final class RelevantAttribute {
        final String id;
        final String name;
        final Set<String> sources = new HashSet<String>();
        double amount;
        String operation;
        String slot;

        RelevantAttribute(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static String humanAttributeName(Attribute attribute, ResourceLocation id) {
        try {
            String translated = new TranslationTextComponent(attribute.getDescriptionId()).getString();
            if (translated.equals(attribute.getDescriptionId()) || translated.startsWith("attribute.")) {
                String path = id.getPath();
                int dot = path.lastIndexOf('.');
                String leaf = dot >= 0 ? path.substring(dot + 1) : path;
                return titleCase(leaf.replace('_', ' '));
            }
            return translated;
        } catch (Exception e) {
            return id != null ? id.toString() : "Unknown";
        }
    }

    private static String titleCase(String raw) {
        String[] parts = raw.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) sb.append(p.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    private static String operationName(AttributeModifier.Operation op) {
        if (op == AttributeModifier.Operation.MULTIPLY_BASE) return "ADD_MULTIPLIED_BASE";
        if (op == AttributeModifier.Operation.MULTIPLY_TOTAL) return "ADD_MULTIPLIED_TOTAL";
        return "ADD_VALUE";
    }

    public static JsonArray listPlayers(MinecraftServer server) {
        JsonArray arr = new JsonArray();
        try {
            for (ServerPlayerEntity player : server.getPlayerList().getPlayers()) {
                try {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("uuid", player.getUUID().toString());
                    obj.addProperty("name", player.getGameProfile().getName());
                    obj.addProperty("displayName", player.getDisplayName().getString());
                    arr.add(obj);
                } catch (Exception e) {
                    EnchantMaster.LOGGER.debug("Skipping player in list: {}", e.toString());
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Failed listing players: {}", e.toString());
        }
        return arr;
    }

    public static JsonArray equipmentSlots() {
        JsonArray arr = new JsonArray();
        arr.add("any");
        for (EquipmentSlotType group : EquipmentSlotType.values()) {
            arr.add(group.getName());
        }
        return arr;
    }

    public static JsonArray attributeOperations() {
        JsonArray arr = new JsonArray();
        arr.add("ADD_VALUE");
        arr.add("ADD_MULTIPLIED_BASE");
        arr.add("ADD_MULTIPLIED_TOTAL");
        return arr;
    }

    private static Set<Item> forgeableItems() {
        Set<Item> cached = forgeableCache;
        if (cached != null && forgeableLogicVersion == FORGEABLE_LOGIC_VERSION) {
            return cached;
        }
        CatalogStats stats = new CatalogStats();
        Set<Item> set = new HashSet<Item>();
        int totalItems = 0;

        List<Enchantment> allEnchants = new ArrayList<Enchantment>();
        try {
            for (Enchantment enchantment : ForgeRegistries.ENCHANTMENTS) {
                try {
                    allEnchants.add(enchantment);
                    stats.enchantments++;
                    ResourceLocation eid = ForgeRegistries.ENCHANTMENTS.getKey(enchantment);
                    if (eid != null) {
                        Integer n = stats.enchantNamespaces.get(eid.getNamespace());
                        stats.enchantNamespaces.put(eid.getNamespace(), Integer.valueOf(n == null ? 1 : n.intValue() + 1));
                    }
                } catch (Exception e) {
                    stats.skippedEnchantments++;
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Could not list enchantments: {}", e.toString());
        }

        for (Item item : ForgeRegistries.ITEMS) {
            totalItems++;
            try {
                if (item == Items.AIR) continue;
                ItemStack stack = new ItemStack(item);
                if (isForgeableItem(stack, item, allEnchants)) {
                    set.add(item);
                }
            } catch (Exception e) {
                stats.skippedItems++;
                if (stats.skipSamples.size() < 25) {
                    ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                    stats.skipSamples.add("item:" + (id != null ? id : "?") + " -> " + e.toString());
                }
            }
        }
        stats.totalItemsRegistered = totalItems;

        for (Item item : set) {
            try {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id != null) {
                    Integer n = stats.itemNamespaces.get(id.getNamespace());
                    stats.itemNamespaces.put(id.getNamespace(), Integer.valueOf(n == null ? 1 : n.intValue() + 1));
                }
            } catch (Exception ignored) {
            }
        }
        stats.forgeableItems = set.size();
        int attrCount = 0;
        for (Attribute ignored : ForgeRegistries.ATTRIBUTES) {
            attrCount++;
        }
        stats.attributes = attrCount;

        forgeableCache = set;
        lastStats = stats;
        forgeableLogicVersion = FORGEABLE_LOGIC_VERSION;
        EnchantMaster.LOGGER.info(
                "Enchant Master catalog: {} forgeable items ({} registered), {} enchantments, {} attributes",
                Integer.valueOf(set.size()), Integer.valueOf(totalItems),
                Integer.valueOf(stats.enchantments), Integer.valueOf(stats.attributes));
        return set;
    }

    static boolean isForgeableItem(ItemStack stack, Item item, List<Enchantment> allEnchants) {
        if (isBookLike(item)) {
            return true;
        }
        if (hasModifiableAttributes(stack)) {
            return true;
        }
        return hasAnyApplicableEnchantment(stack, allEnchants);
    }

    private static boolean hasAnyApplicableEnchantment(ItemStack stack, List<Enchantment> allEnchants) {
        if (allEnchants == null || allEnchants.isEmpty()) {
            return false;
        }
        for (Enchantment enchantment : allEnchants) {
            try {
                if (canApply(enchantment, stack)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean hasModifiableAttributes(ItemStack stack) {
        try {
            for (EquipmentSlotType slot : EquipmentSlotType.values()) {
                com.google.common.collect.Multimap<Attribute, AttributeModifier> mods =
                        stack.getItem().getDefaultAttributeModifiers(slot);
                if (mods != null && !mods.isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    static boolean canApply(Enchantment enchantment, ItemStack stack) {
        try {
            return enchantment.canEnchant(stack);
        } catch (Exception e) {
            try {
                return enchantment.canApplyAtEnchantingTable(stack);
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private static ItemStack resolveProbeStack(String forItemId) {
        if (forItemId == null || forItemId.trim().isEmpty()) {
            return ItemStack.EMPTY;
        }
        try {
            ResourceLocation id = ResourceLocation.tryParse(forItemId);
            if (id == null) return ItemStack.EMPTY;
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) return ItemStack.EMPTY;
            return new ItemStack(item);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static String safeItemName(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        try {
            ItemStack stack = new ItemStack(item);
            String hover = stack.getHoverName().getString();
            if (isUsableDisplayName(hover, id)) {
                return hover;
            }
            try {
                String fromItem = item.getName(stack).getString();
                if (isUsableDisplayName(fromItem, id)) {
                    return fromItem;
                }
            } catch (Exception ignored) {
            }
            String descId = item.getDescriptionId();
            String translated = translateKey(descId);
            if (isUsableDisplayName(translated, id)) {
                return translated;
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Item name resolve failed for {}: {}", id, e.toString());
        }
        return humanizePath(id);
    }

    public static String safeStackDisplayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "Empty";
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        try {
            String hover = stack.getHoverName().getString();
            if (isUsableDisplayName(hover, id)) {
                return hover;
            }
        } catch (Exception ignored) {
        }
        return safeItemName(stack.getItem());
    }

    private static JsonArray slice(List<JsonObject> list, int limit, int offset) {
        int from = Math.max(0, offset);
        int to = limit <= 0 ? list.size() : Math.min(list.size(), from + limit);
        JsonArray arr = new JsonArray();
        if (from >= list.size()) return arr;
        for (int i = from; i < to; i++) {
            arr.add(list.get(i));
        }
        return arr;
    }

    public static boolean areCompatible(Enchantment a, Enchantment b) {
        if (a == b) return false;
        try {
            return a.isCompatibleWith(b);
        } catch (Exception e) {
            return true;
        }
    }

    public static Enchantment findEnchantment(String id) {
        try {
            ResourceLocation identifier = ResourceLocation.tryParse(id);
            if (identifier == null) return null;
            if (!ForgeRegistries.ENCHANTMENTS.containsKey(identifier)) return null;
            return ForgeRegistries.ENCHANTMENTS.getValue(identifier);
        } catch (Exception e) {
            return null;
        }
    }

    public static Attribute findAttribute(String id) {
        try {
            ResourceLocation identifier = ResourceLocation.tryParse(id);
            if (identifier == null) return null;
            if (!ForgeRegistries.ATTRIBUTES.containsKey(identifier)) return null;
            return ForgeRegistries.ATTRIBUTES.getValue(identifier);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean isBookLike(Item item) {
        return item == Items.BOOK || item == Items.ENCHANTED_BOOK;
    }

    private static final class CatalogStats {
        int totalItemsRegistered;
        int forgeableItems;
        int enchantments;
        int attributes;
        int skippedItems;
        int skippedEnchantments;
        final Map<String, Integer> itemNamespaces = new TreeMap<String, Integer>();
        final Map<String, Integer> enchantNamespaces = new TreeMap<String, Integer>();
        final List<String> skipSamples = new ArrayList<String>();
    }
}
