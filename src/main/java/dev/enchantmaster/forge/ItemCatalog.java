package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.enchantmaster.EnchantMaster;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;

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
 * Registry-driven catalogs of forgeable items, enchantments, and attributes.
 * Includes vanilla and all loaded mods. Failures on individual entries are skipped.
 */
public final class ItemCatalog {
    /** Bump when forgeable filter logic changes so hot-reloads pick it up. */
    private static final int FORGEABLE_LOGIC_VERSION = 4;
    private static volatile int forgeableLogicVersion;
    private static volatile Set<Item> forgeableCache;
    private static volatile CatalogStats lastStats;

    private ItemCatalog() {
    }

    /** Clears cached forgeable item set (call on datapack reload if needed). */
    public static void invalidateCache() {
        forgeableCache = null;
        lastStats = null;
        forgeableLogicVersion = 0;
    }

    public static JsonObject catalogStats(MinecraftServer server) {
        return catalogStats(server.registryAccess());
    }

    public static JsonObject catalogStats(HolderLookup.Provider registries) {
        forgeableItems(registries); // ensure cache+stats
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
        return listItems(server.registryAccess(), query, namespace, limit, offset);
    }

    public static JsonArray listItems(HolderLookup.Provider registries, String query, String namespace, int limit, int offset) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        String ns = namespace == null ? "" : namespace.toLowerCase(Locale.ROOT);
        Set<Item> forgeable = forgeableItems(registries);

        // First pass: collect candidates with base display names
        List<JsonObject> candidates = new ArrayList<>();
        Map<String, Integer> nameCounts = new HashMap<>();
        for (Item item : forgeable) {
            try {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
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

        // Second pass: disambiguate duplicate display names as "Name - Source"
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

    /** Human-readable pack/mod label for a namespace. */
    public static String sourceLabel(String namespace) {
        if (namespace == null || namespace.isBlank() || "minecraft".equals(namespace)) {
            return "Vanilla Minecraft";
        }
        try {
            return net.neoforged.fml.ModList.get()
                    .getModContainerById(namespace)
                    .map(c -> c.getModInfo().getDisplayName())
                    .orElse(namespace);
        } catch (Exception e) {
            return namespace;
        }
    }

    public static JsonArray listEnchantments(MinecraftServer server, String query, String forItemId, boolean overrideLimits) {
        return listEnchantments(server.registryAccess(), query, forItemId, overrideLimits);
    }

    public static JsonArray listEnchantments(HolderLookup.Provider registries, String query, String forItemId, boolean overrideLimits) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        final ItemStack probe = resolveProbeStack(forItemId);
        final boolean ignoreCompatibility = overrideLimits;

        List<JsonObject> results = new ArrayList<>();
        HolderLookup.RegistryLookup<Enchantment> enchants =
                registries.lookupOrThrow(Registries.ENCHANTMENT);

        enchants.listElements().forEach(holder -> {
            try {
                Enchantment enchantment = holder.value();
                Identifier id = holder.key().identifier();
                String name = safeEnchantmentName(id, enchantment);
                String flavor = resolveEnchantmentFlavor(id, enchantment);
                if (!q.isEmpty()) {
                    String hay = (id.toString() + " " + name + " " + flavor).toLowerCase(Locale.ROOT);
                    if (!hay.contains(q)) return;
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
        });

        results.sort(Comparator.comparing(o -> o.get("id").getAsString()));
        JsonArray arr = new JsonArray();
        results.forEach(arr::add);
        return arr;
    }

    private static String safeEnchantmentName(Identifier id, Enchantment enchantment) {
        try {
            String fromComponent = enchantment.description().getString();
            if (isUsableDisplayName(fromComponent, id)) {
                return fromComponent;
            }
        } catch (Exception ignored) {
        }
        String key = net.minecraft.util.Util.makeDescriptionId("enchantment", id);
        String translated = translateKey(key);
        if (isUsableDisplayName(translated, id)) {
            return translated;
        }
        return humanizePath(id);
    }

    /**
     * Human flavor text for an enchantment (tooltip-style). Tries common translation keys used by
     * vanilla packs, Enchantment Descriptions, Apothic Enchanting, etc.
     */
    private static String resolveEnchantmentFlavor(Identifier id, Enchantment enchantment) {
        try {
            List<String> keys = new ArrayList<>();
            String baseKey = net.minecraft.util.Util.makeDescriptionId("enchantment", id);
            // Standard: enchantment.namespace.path.desc
            keys.add(baseKey + ".desc");
            keys.add(baseKey + ".description");
            // Enchantment Descriptions mod style
            keys.add("descriptions.enchantment." + id.getNamespace() + "." + id.getPath());
            keys.add("enchantment." + id.getNamespace() + "." + id.getPath() + ".desc");
            keys.add("enchantment." + id.getNamespace() + "." + id.getPath() + ".description");

            // If the name component itself is translatable, also try nameKey + ".desc"
            try {
                var contents = enchantment.description().getContents();
                if (contents instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
                    String base = tc.getKey();
                    if (base != null && !base.isBlank()) {
                        keys.add(base + ".desc");
                        keys.add(base + ".description");
                    }
                }
            } catch (Exception ignored) {
            }

            for (String key : keys) {
                String value = translateKey(key);
                if (isUsableDisplayName(value, id) && !value.equals(key)) {
                    // Reject if "flavor" is just the same as the short name (not useful)
                    return value;
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("No flavor text for {}: {}", id, e.toString());
        }
        return "";
    }

    /** Resolve a translation key via Language and Component (dedicated-server safe). */
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

    /**
     * True if {@code name} looks like a real UI label rather than a raw id / untranslated key.
     */
    static boolean isUsableDisplayName(String name, Identifier id) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        if (id != null) {
            if (n.equalsIgnoreCase(id.toString())) return false;
            if (n.equalsIgnoreCase(id.getPath())) return false;
            if (n.equalsIgnoreCase(id.getNamespace() + ":" + id.getPath())) return false;
        }
        // Untranslated lang keys
        if (n.startsWith("item.") || n.startsWith("block.") || n.startsWith("enchantment.")) {
            // Allow if it contains spaces (unlikely for keys) — still treat as bad if no spaces and has dots
            if (!n.contains(" ")) return false;
        }
        return true;
    }

    /** copper_chestplate → Copper Chestplate */
    static String humanizePath(Identifier id) {
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
                Identifier id = BuiltInRegistries.ATTRIBUTE.getKey(attribute);
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
     * Attributes relevant to forging a specific item: defaults on the item plus attributes
     * granted by the selected enchantments. Names are human-readable (translated).
     *
     * @param enchantments list of objects with "id" and "level" fields (may be empty)
     */
    public static JsonArray listRelevantAttributes(
            HolderLookup.Provider registries,
            String itemId,
            List<EnchantLevel> enchantments,
            String query
    ) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        // id -> aggregate info
        Map<String, RelevantAttribute> byId = new HashMap<>();

        // Item default modifiers
        try {
            ItemStack stack = resolveProbeStack(itemId);
            if (!stack.isEmpty()) {
                ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
                for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
                    try {
                        Identifier aid = entry.attribute().unwrapKey()
                                .map(k -> k.identifier())
                                .orElseGet(() -> BuiltInRegistries.ATTRIBUTE.getKey(entry.attribute().value()));
                        if (aid == null) continue;
                        String key = aid.toString();
                        RelevantAttribute ra = byId.computeIfAbsent(key, k -> new RelevantAttribute(
                                key,
                                humanAttributeName(entry.attribute().value(), aid)
                        ));
                        ra.sources.add("item");
                        ra.amount = entry.modifier().amount();
                        ra.operation = entry.modifier().operation().getSerializedName();
                        ra.slot = entry.slot().getSerializedName();
                    } catch (Exception e) {
                        EnchantMaster.LOGGER.debug("Skip item attribute entry: {}", e.toString());
                    }
                }
            }
        } catch (Exception e) {
            EnchantMaster.LOGGER.debug("Failed reading item attributes for {}: {}", itemId, e.toString());
        }

        // Enchantment-granted attributes from chosen enchants
        if (enchantments != null) {
            for (EnchantLevel el : enchantments) {
                if (el == null || el.id() == null) continue;
                try {
                    Optional<Holder.Reference<Enchantment>> holder = findEnchantment(registries, el.id());
                    if (holder.isEmpty()) continue;
                    Enchantment enchantment = holder.get().value();
                    String enchantName;
                    try {
                        enchantName = enchantment.description().getString();
                    } catch (Exception e) {
                        enchantName = el.id();
                    }
                    List<EnchantmentAttributeEffect> effects =
                            enchantment.getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                    for (EnchantmentAttributeEffect effect : effects) {
                        try {
                            Identifier aid = effect.attribute().unwrapKey()
                                    .map(k -> k.identifier())
                                    .orElseGet(() -> BuiltInRegistries.ATTRIBUTE.getKey(effect.attribute().value()));
                            if (aid == null) continue;
                            String key = aid.toString();
                            RelevantAttribute ra = byId.computeIfAbsent(key, k -> new RelevantAttribute(
                                    key,
                                    humanAttributeName(effect.attribute().value(), aid)
                            ));
                            ra.sources.add("enchant:" + enchantName);
                            double amount = effect.amount().calculate(Math.max(1, el.level()));
                            // Prefer showing enchant amount if item didn't set one, else keep item amount as base
                            if (!ra.sources.contains("item") || ra.amount == 0) {
                                ra.amount = amount;
                            }
                            ra.operation = effect.operation().getSerializedName();
                        } catch (Exception e) {
                            EnchantMaster.LOGGER.debug("Skip enchant attribute effect: {}", e.toString());
                        }
                    }
                } catch (Exception e) {
                    EnchantMaster.LOGGER.debug("Skip enchant attributes for {}: {}", el.id(), e.toString());
                }
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
            obj.addProperty("operation", ra.operation != null ? ra.operation : AttributeModifier.Operation.ADD_VALUE.getSerializedName());
            obj.addProperty("slot", ra.slot != null ? ra.slot : "any");
            obj.addProperty("source", String.join(", ", ra.sources));
            // Label for UI lists
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

    private static String humanAttributeName(Attribute attribute, Identifier id) {
        try {
            String translated = Component.translatable(attribute.getDescriptionId()).getString();
            // If untranslated, fall back to a cleaned path
            if (translated.equals(attribute.getDescriptionId()) || translated.startsWith("attribute.")) {
                String path = id.getPath();
                // generic.attack_damage -> Attack Damage
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

    public static Optional<Holder.Reference<Enchantment>> findEnchantment(HolderLookup.Provider registries, String id) {
        try {
            Identifier identifier = Identifier.parse(id);
            return registries.lookupOrThrow(Registries.ENCHANTMENT)
                    .get(ResourceKey.create(Registries.ENCHANTMENT, identifier));
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
                    obj.addProperty("name", player.getGameProfile().name());
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
        for (EquipmentSlotGroup group : EquipmentSlotGroup.values()) {
            try {
                arr.add(group.getSerializedName());
            } catch (Exception ignored) {
                arr.add(group.name().toLowerCase(Locale.ROOT));
            }
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

    /**
     * Builds the set of forgeable items once per session.
     * <p>
     * An item is included only if at least one of:
     * <ul>
     *   <li>it is a book / enchanted book (stored enchants)</li>
     *   <li>at least one registered enchantment can actually apply to it ({@code canEnchant})</li>
     *   <li>it has non-empty default {@link DataComponents#ATTRIBUTE_MODIFIERS}</li>
     * </ul>
     * Merely having an {@code ENCHANTABLE} component is not enough (many items have it without
     * any enchantment accepting them; boats/blocks were incorrectly listed).
     */
    private static Set<Item> forgeableItems(HolderLookup.Provider registries) {
        Set<Item> cached = forgeableCache;
        if (cached != null && forgeableLogicVersion == FORGEABLE_LOGIC_VERSION) {
            return cached;
        }
        CatalogStats stats = new CatalogStats();
        Set<Item> set = new HashSet<>();
        int totalItems = 0;

        // Preload enchant holders once
        List<Holder.Reference<Enchantment>> allEnchants = new ArrayList<>();
        try {
            HolderLookup.RegistryLookup<Enchantment> enchants =
                    registries.lookupOrThrow(Registries.ENCHANTMENT);
            enchants.listElements().forEach(holder -> {
                try {
                    allEnchants.add(holder);
                    Identifier eid = holder.key().identifier();
                    stats.enchantments++;
                    stats.enchantNamespaces.merge(eid.getNamespace(), 1, Integer::sum);
                } catch (Exception e) {
                    stats.skippedEnchantments++;
                }
            });
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
                    Identifier id = BuiltInRegistries.ITEM.getKey(item);
                    stats.skipSamples.add("item:" + (id != null ? id : "?") + " -> " + e.toString());
                }
                EnchantMaster.LOGGER.debug("Skipping item while building forgeable set: {}", e.toString());
            }
        }
        stats.totalItemsRegistered = totalItems;

        for (Item item : set) {
            try {
                Identifier id = BuiltInRegistries.ITEM.getKey(item);
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

    /**
     * Strict per-item gate used by the catalog.
     */
    static boolean isForgeableItem(ItemStack stack, Item item, List<Holder.Reference<Enchantment>> allEnchants) {
        if (isBookLike(item)) {
            return true;
        }
        if (hasModifiableAttributes(stack)) {
            return true;
        }
        return hasAnyApplicableEnchantment(stack, allEnchants);
    }

    private static boolean hasAnyApplicableEnchantment(ItemStack stack, List<Holder.Reference<Enchantment>> allEnchants) {
        if (allEnchants == null || allEnchants.isEmpty()) {
            return false;
        }
        for (Holder.Reference<Enchantment> holder : allEnchants) {
            try {
                if (canApply(holder.value(), stack)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private static boolean hasModifiableAttributes(ItemStack stack) {
        try {
            ItemAttributeModifiers mods = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
            return mods != null && !mods.modifiers().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean canApply(Enchantment enchantment, ItemStack stack) {
        try {
            return enchantment.canEnchant(stack);
        } catch (Exception e) {
            try {
                return enchantment.definition().supportedItems().contains(itemHolder(stack));
            } catch (Exception e2) {
                // Fail closed for non-override path
                return false;
            }
        }
    }

    private static ItemStack resolveProbeStack(String forItemId) {
        if (forItemId == null || forItemId.isBlank()) {
            return ItemStack.EMPTY;
        }
        try {
            Identifier id = Identifier.parse(forItemId);
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(id);
            return item.map(ItemStack::new).orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * Human-readable item name for the web UI. Never prefers raw registry ids when a
     * translated or prettified name is available (fixes dedicated-server cases where
     * hover text falls back to {@code minecraft:copper_chestplate}).
     */
    private static String safeItemName(Item item) {
        Identifier id = BuiltInRegistries.ITEM.getKey(item);
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

    /** Prefer custom name / hover text, then catalog-style resolution. */
    public static String safeStackDisplayName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "Empty";
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        try {
            String hover = stack.getHoverName().getString();
            if (isUsableDisplayName(hover, id)) {
                return hover;
            }
        } catch (Exception ignored) {
        }
        return safeItemName(stack.getItem());
    }

    /** 26.x {@code typeHolder()} / 1.21.x {@code getItemHolder()}. */
    @SuppressWarnings("unchecked")
    public static Holder<Item> itemHolder(ItemStack stack) {
        try {
            return (Holder<Item>) ItemStack.class.getMethod("typeHolder").invoke(stack);
        } catch (ReflectiveOperationException e1) {
            try {
                return (Holder<Item>) ItemStack.class.getMethod("getItemHolder").invoke(stack);
            } catch (ReflectiveOperationException e2) {
                return stack.getItem().builtInRegistryHolder();
            }
        }
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

    /** Utility for conflict detection between two enchant holders. */
    public static boolean areCompatible(Holder<Enchantment> a, Holder<Enchantment> b) {
        if (a.equals(b)) return false;
        try {
            return Enchantment.areCompatible(a, b);
        } catch (Exception e) {
            // Fail open only for exclusive-set evaluation errors
            return true;
        }
    }

    public static Optional<Holder.Reference<Enchantment>> findEnchantment(MinecraftServer server, String id) {
        return findEnchantment(server.registryAccess(), id);
    }

    public static Optional<Holder.Reference<Attribute>> findAttribute(String id) {
        try {
            return BuiltInRegistries.ATTRIBUTE.get(Identifier.parse(id));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public static boolean isBookLike(Item item) {
        // Only real books — do not treat "has empty stored_enchantments component" as book-like
        return item == Items.BOOK || item == Items.ENCHANTED_BOOK;
    }

    public static ItemEnchantments emptyEnchantments() {
        return ItemEnchantments.EMPTY;
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
