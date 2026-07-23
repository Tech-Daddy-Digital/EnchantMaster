package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.enchantmaster.EnchantMaster;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Registry-driven catalogs for Forge 1.20.1 (BuiltInRegistries / ForgeRegistries, classic enchants).
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
        stats.itemNamespaces.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> itemNs.addProperty(e.getKey(), e.getValue()));
        obj.add("forgeableItemNamespaces", itemNs);
        JsonObject enchNs = new JsonObject();
        stats.enchantNamespaces.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> enchNs.addProperty(e.getKey(), e.getValue()));
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

        List<JsonObject> candidates = new ArrayList<>();
        Map<String, Integer> nameCounts = new HashMap<>();
        for (Item item : forgeable) {
            try {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id == null) continue;
                if (!ns.isEmpty() && !id.getNamespace().equalsIgnoreCase(ns)) continue;

                String baseName = safeItemName(item);
                if (!q.isEmpty()) {
                    String hay = (id.toString() + " " + baseName + " " + sourceLabel(id.getNamespace())).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) continue;
                }

                nameCounts.merge(baseName.toLowerCase(Locale.ROOT), 1, Integer::sum);

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

        List<JsonObject> results = new ArrayList<>();
        for (JsonObject obj : candidates) {
            String baseName = obj.get("baseName").getAsString();
            String source = obj.get("source").getAsString();
            String display = baseName;
            if (nameCounts.getOrDefault(baseName.toLowerCase(Locale.ROOT), 0) > 1) {
                display = baseName + " - " + source;
            }
            obj.addProperty("name", display);
            results.add(obj);
        }

        results.sort(Comparator.comparing(o -> o.get("name").getAsString(), String.CASE_INSENSITIVE_ORDER));
        return slice(results, limit, offset);
    }

    public static String sourceLabel(String namespace) {
        if (namespace == null || namespace.isBlank() || "minecraft".equals(namespace)) {
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
        final ItemStack probe = resolveProbeStack(forItemId);
        final boolean ignoreCompatibility = overrideLimits;

        List<JsonObject> results = new ArrayList<>();
        for (Enchantment enchantment : BuiltInRegistries.ENCHANTMENT) {
            try {
                ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(enchantment);
                if (id == null) continue;
                String name = safeEnchantmentName(id, enchantment);
                String flavor = resolveEnchantmentFlavor(id);
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
                } catch (Exception e) {
                    maxLevel = 1;
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

        results.sort(Comparator.comparing(o -> o.get("id").getAsString()));
        JsonArray arr = new JsonArray();
        results.forEach(arr::add);
        return arr;
    }

    private static String safeEnchantmentName(ResourceLocation id, Enchantment enchantment) {
        try {
            String fromComponent = enchantment.getFullname(1).getString();
            // getFullname includes roman numeral level; strip trailing " I"
            if (fromComponent != null && fromComponent.endsWith(" I")) {
                fromComponent = fromComponent.substring(0, fromComponent.length() - 2);
            }
            if (isUsableDisplayName(fromComponent, id)) {
                return fromComponent;
            }
        } catch (Exception ignored) {
        }
        String key = net.minecraft.Util.makeDescriptionId("enchantment", id);
        String translated = translateKey(key);
        if (isUsableDisplayName(translated, id)) {
            return translated;
        }
        return humanizePath(id);
    }

    private static String resolveEnchantmentFlavor(ResourceLocation id) {
        try {
            List<String> keys = new ArrayList<>();
            String baseKey = net.minecraft.Util.makeDescriptionId("enchantment", id);
            keys.add(baseKey + ".desc");
            keys.add(baseKey + ".description");
            keys.add("descriptions.enchantment." + id.getNamespace() + "." + id.getPath());
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
        if (key == null || key.isBlank()) return "";
        try {
            net.minecraft.locale.Language lang = net.minecraft.locale.Language.getInstance();
            if (lang.has(key)) {
                String v = lang.getOrDefault(key);
                if (v != null && !v.isBlank() && !v.equals(key)) {
                    return v;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            String via = Component.translatable(key).getString();
            if (via != null && !via.isBlank() && !via.equals(key)) {
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
        if (path == null || path.isBlank()) return id.toString();
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
        List<JsonObject> results = new ArrayList<>();

        for (Attribute attribute : BuiltInRegistries.ATTRIBUTE) {
            try {
                ResourceLocation id = BuiltInRegistries.ATTRIBUTE.getKey(attribute);
                if (id == null) continue;
                String translationKey;
                try {
                    translationKey = attribute.getDescriptionId();
                } catch (Exception e) {
                    translationKey = id.toString();
                }
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

        results.sort(Comparator.comparing(o -> o.get("name").getAsString()));
        JsonArray arr = new JsonArray();
        results.forEach(arr::add);
        return arr;
    }

    /**
     * Relevant attributes for an item (default equipment modifiers). Enchant effect attributes
     * are limited on 1.20.1 (no EnchantmentEffectComponents).
     */
    public static JsonArray listRelevantAttributes(
            Object ignoredRegistries,
            String itemId,
            List<EnchantLevel> enchantments,
            String query
    ) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Map<String, RelevantAttribute> byId = new HashMap<>();

        try {
            ItemStack stack = resolveProbeStack(itemId);
            if (!stack.isEmpty()) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    try {
                        var multimap = stack.getAttributeModifiers(slot);
                        for (var entry : multimap.entries()) {
                            Attribute attribute = entry.getKey();
                            AttributeModifier modifier = entry.getValue();
                            ResourceLocation aid = BuiltInRegistries.ATTRIBUTE.getKey(attribute);
                            if (aid == null) continue;
                            String key = aid.toString();
                            RelevantAttribute ra = byId.computeIfAbsent(key, k -> new RelevantAttribute(
                                    key,
                                    humanAttributeName(attribute, aid)
                            ));
                            ra.sources.add("item:" + slot.getName());
                            ra.amount = modifier.getAmount();
                            ra.operation = operationName(modifier.getOperation());
                            ra.slot = slot.getName();
                        }
                    } catch (Exception e) {
                        EnchantMaster.LOGGER.debug("Skip slot attributes {}: {}", slot, e.toString());
                    }
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Failed reading item attributes for {}: {}", itemId, e.toString());
        }

        // Note: enchant-granted attributes via effects not available the same way on 1.20.1.
        if (enchantments != null) {
            for (EnchantLevel el : enchantments) {
                if (el == null || el.id() == null) continue;
                // Keep id listed so UI still sees selection; no auto-amount without effect API.
            }
        }

        List<JsonObject> results = new ArrayList<>();
        for (RelevantAttribute ra : byId.values()) {
            if (!q.isEmpty()) {
                String hay = (ra.id + " " + ra.name + " " + String.join(" ", ra.sources)).toLowerCase(Locale.ROOT);
                if (!hay.contains(q)) continue;
            }
            JsonObject obj = new JsonObject();
            obj.addProperty("id", ra.id);
            obj.addProperty("name", ra.name);
            obj.addProperty("amount", ra.amount);
            obj.addProperty("operation", ra.operation != null ? ra.operation : "ADD_VALUE");
            obj.addProperty("slot", ra.slot != null ? ra.slot : "any");
            obj.addProperty("source", String.join(", ", ra.sources));
            StringBuilder label = new StringBuilder(ra.name);
            if (ra.amount != 0) {
                String sign = ra.amount >= 0 ? "+" : "";
                label.append(" (").append(sign).append(formatAmount(ra.amount)).append(")");
            }
            if (!ra.sources.isEmpty()) {
                label.append(" — ").append(String.join(", ", ra.sources));
            }
            obj.addProperty("label", label.toString());
            results.add(obj);
        }
        results.sort(Comparator.comparing(o -> o.get("name").getAsString()));
        JsonArray arr = new JsonArray();
        results.forEach(arr::add);
        return arr;
    }

    public record EnchantLevel(String id, int level) {
    }

    private static final class RelevantAttribute {
        final String id;
        final String name;
        final Set<String> sources = new HashSet<>();
        double amount;
        String operation;
        String slot;

        RelevantAttribute(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static String operationName(AttributeModifier.Operation op) {
        if (op == null) return "ADD_VALUE";
        return switch (op) {
            case MULTIPLY_BASE -> "ADD_MULTIPLIED_BASE";
            case MULTIPLY_TOTAL -> "ADD_MULTIPLIED_TOTAL";
            default -> "ADD_VALUE";
        };
    }

    private static String humanAttributeName(Attribute attribute, ResourceLocation id) {
        try {
            String translated = Component.translatable(attribute.getDescriptionId()).getString();
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

    private static String formatAmount(double amount) {
        if (amount == (long) amount) {
            return Long.toString((long) amount);
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    public static Optional<Enchantment> findEnchantment(String id) {
        try {
            ResourceLocation identifier = ResourceLocation.tryParse(id);
            if (identifier == null) return Optional.empty();
            return BuiltInRegistries.ENCHANTMENT.getOptional(identifier);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static JsonArray listPlayers(MinecraftServer server) {
        JsonArray arr = new JsonArray();
        try {
            server.getPlayerList().getPlayers().forEach(player -> {
                try {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("uuid", player.getUUID().toString());
                    obj.addProperty("name", player.getGameProfile().getName());
                    obj.addProperty("displayName", player.getDisplayName().getString());
                    arr.add(obj);
                } catch (Exception e) {
                    EnchantMaster.LOGGER.debug("Skipping player in list: {}", e.toString());
                }
            });
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Failed listing players: {}", e.toString());
        }
        return arr;
    }

    public static JsonArray equipmentSlots() {
        JsonArray arr = new JsonArray();
        arr.add("any");
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            arr.add(slot.getName());
        }
        return arr;
    }

    public static JsonArray attributeOperations() {
        JsonArray arr = new JsonArray();
        arr.add("ADD_VALUE");
        arr.add("ADD_MULTIPLIED_BASE");
        arr.add("ADD_MULTIPLIED_TOTAL");
        // Also list classic 1.20 names for clarity
        arr.add("ADDITION");
        arr.add("MULTIPLY_BASE");
        arr.add("MULTIPLY_TOTAL");
        return arr;
    }

    private static Set<Item> forgeableItems() {
        Set<Item> cached = forgeableCache;
        if (cached != null && forgeableLogicVersion == FORGEABLE_LOGIC_VERSION) {
            return cached;
        }
        CatalogStats stats = new CatalogStats();
        Set<Item> set = new HashSet<>();
        int totalItems = 0;

        List<Enchantment> allEnchants = new ArrayList<>();
        try {
            for (Enchantment enchantment : BuiltInRegistries.ENCHANTMENT) {
                try {
                    allEnchants.add(enchantment);
                    ResourceLocation eid = BuiltInRegistries.ENCHANTMENT.getKey(enchantment);
                    stats.enchantments++;
                    if (eid != null) {
                        stats.enchantNamespaces.merge(eid.getNamespace(), 1, Integer::sum);
                    }
                } catch (Exception e) {
                    stats.skippedEnchantments++;
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.warn("Could not list enchantments for forgeable filter: {}", e.toString());
        }

        for (Item item : BuiltInRegistries.ITEM) {
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
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                    stats.skipSamples.add("item:" + (id != null ? id : "?") + " -> " + e.toString());
                }
            }
        }
        stats.totalItemsRegistered = totalItems;

        for (Item item : set) {
            try {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null) {
                    stats.itemNamespaces.merge(id.getNamespace(), 1, Integer::sum);
                }
            } catch (Exception ignored) {
            }
        }
        stats.forgeableItems = set.size();
        stats.attributes = (int) BuiltInRegistries.ATTRIBUTE.stream().count();

        forgeableCache = set;
        lastStats = stats;
        forgeableLogicVersion = FORGEABLE_LOGIC_VERSION;
        EnchantMaster.LOGGER.info(
                "Enchant Master catalog: {} forgeable items ({} registered), {} enchantments, {} attributes, namespaces={}",
                set.size(), totalItems, stats.enchantments, stats.attributes, stats.itemNamespaces.keySet());
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
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                if (!stack.getAttributeModifiers(slot).isEmpty()) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    static boolean canApply(Enchantment enchantment, ItemStack stack) {
        try {
            return enchantment.canEnchant(stack);
        } catch (Exception e) {
            try {
                return enchantment.category.canEnchant(stack.getItem());
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private static ItemStack resolveProbeStack(String forItemId) {
        if (forItemId == null || forItemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            ResourceLocation id = ResourceLocation.tryParse(forItemId);
            if (id == null) return ItemStack.EMPTY;
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
            return item.map(ItemStack::new).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private static String safeItemName(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
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
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
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

    public static Optional<Attribute> findAttribute(String id) {
        try {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) return Optional.empty();
            Attribute attr = ForgeRegistries.ATTRIBUTES.getValue(rl);
            if (attr != null) return Optional.of(attr);
            return BuiltInRegistries.ATTRIBUTE.getOptional(rl);
        } catch (Exception e) {
            return Optional.empty();
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
        final Map<String, Integer> itemNamespaces = new TreeMap<>();
        final Map<String, Integer> enchantNamespaces = new TreeMap<>();
        final List<String> skipSamples = new ArrayList<>();
    }
}
