package dev.enchantmaster.forge;

import dev.enchantmaster.EnchantMasterPlugin;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class ItemStackBuilder {
    private ItemStackBuilder() {
    }

    public static Result build(ForgeRequest request) {
        if (request.itemId == null || request.itemId.isBlank()) {
            return Result.error("itemId is required");
        }
        Optional<Material> matOpt = ItemCatalog.material(request.itemId);
        if (matOpt.isEmpty() || matOpt.get().isAir()) {
            return Result.error("Unknown item: " + request.itemId);
        }
        Material mat = matOpt.get();
        if (mat == Material.BOOK && request.enchantments != null && !request.enchantments.isEmpty()) {
            mat = Material.ENCHANTED_BOOK;
        }
        ItemStack stack = new ItemStack(mat);
        return apply(stack, request, false);
    }

    public static Result applyToExisting(ItemStack base, ForgeRequest request) {
        if (base == null || base.getType().isAir()) {
            return Result.error("Cannot modify an empty stack");
        }
        ItemStack stack = base.clone();
        if (stack.getType() == Material.BOOK && request.enchantments != null && !request.enchantments.isEmpty()) {
            int count = stack.getAmount();
            stack = new ItemStack(Material.ENCHANTED_BOOK, count);
        }
        return apply(stack, request, true);
    }

    private static Result apply(ItemStack stack, ForgeRequest request, boolean replaceMode) {
        boolean override = request.overrideLimits;
        int maxOverride = EnchantMasterPlugin.get().config().maxOverrideLevel();
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return Result.error("Item has no meta");
        }

        // clear enchants when replacing
        if (replaceMode) {
            if (meta instanceof EnchantmentStorageMeta storage) {
                for (Enchantment e : new ArrayList<>(storage.getStoredEnchants().keySet())) {
                    storage.removeStoredEnchant(e);
                }
            } else {
                for (Enchantment e : new ArrayList<>(meta.getEnchants().keySet())) {
                    meta.removeEnchant(e);
                }
            }
        }

        List<Enchantment> applied = new ArrayList<>();
        if (request.enchantments != null) {
            for (ForgeRequest.EnchantEntry entry : request.enchantments) {
                if (entry == null || entry.id() == null || entry.id().isBlank()) {
                    return Result.error("Enchantment entry missing id");
                }
                Optional<Enchantment> enchOpt = ItemCatalog.findEnchantment(entry.id());
                if (enchOpt.isEmpty()) {
                    return Result.error("Unknown enchantment: " + entry.id());
                }
                Enchantment ench = enchOpt.get();
                if (!override && !ItemCatalog.canApply(ench, stack) && !ItemCatalog.isBookLike(stack.getType())) {
                    return Result.error("Enchantment " + entry.id() + " cannot be applied to "
                            + stack.getType().getKey() + " (enable override to force)");
                }
                int level = entry.level();
                int max = Math.max(1, ench.getMaxLevel());
                if (!override) {
                    if (level < 1 || level > max) {
                        return Result.error("Level " + level + " out of range for " + entry.id()
                                + " (1-" + max + "). Enable override for higher levels.");
                    }
                } else if (level < 1 || level > maxOverride) {
                    return Result.error("Level " + level + " out of override range (1-" + maxOverride + ")");
                }
                if (!override) {
                    for (Enchantment existing : applied) {
                        if (!ItemCatalog.areCompatible(existing, ench)) {
                            return Result.error("Enchantments conflict: " + existing.getKey()
                                    + " and " + entry.id() + " (enable override to force)");
                        }
                    }
                }
                if (meta instanceof EnchantmentStorageMeta storage) {
                    storage.addStoredEnchant(ench, level, override);
                } else {
                    meta.addEnchant(ench, level, override);
                }
                applied.add(ench);
            }
        }

        // name
        if (request.name != null && request.name.text() != null && !request.name.text().isBlank()) {
            meta.displayName(request.name.toComponent());
        } else if (replaceMode) {
            meta.displayName(null);
        }

        // lore
        if (request.lore != null) {
            if (request.lore.isEmpty() && replaceMode) {
                meta.lore(null);
            } else if (!request.lore.isEmpty()) {
                List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
                for (StyledText line : request.lore) {
                    lines.add(line.toComponent());
                }
                meta.lore(lines);
            }
        }

        // attributes
        if (replaceMode || (request.attributes != null && !request.attributes.isEmpty())) {
            if (meta.getAttributeModifiers() != null) {
                meta.getAttributeModifiers().forEach((attr, mod) -> meta.removeAttributeModifier(attr));
            }
            if (request.attributes != null) {
                for (ForgeRequest.AttributeEntry entry : request.attributes) {
                    Optional<Attribute> attrOpt = ItemCatalog.findAttribute(entry.id());
                    if (attrOpt.isEmpty()) {
                        return Result.error("Unknown attribute: " + entry.id());
                    }
                    AttributeModifier.Operation op = mapOperation(entry.operation());
                    EquipmentSlotGroup group = mapSlot(entry.slot());
                    AttributeModifier mod = new AttributeModifier(
                            new org.bukkit.NamespacedKey("enchantmaster", "mod_" + UUID.randomUUID().toString().substring(0, 8)),
                            entry.amount(),
                            op,
                            group
                    );
                    meta.addAttributeModifier(attrOpt.get(), mod);
                }
            }
        }

        stack.setItemMeta(meta);
        return Result.ok(stack);
    }

    private static AttributeModifier.Operation mapOperation(String op) {
        if (op == null) return AttributeModifier.Operation.ADD_NUMBER;
        String u = op.toUpperCase(Locale.ROOT);
        return switch (u) {
            case "ADD_MULTIPLIED_BASE", "MULTIPLY_BASE" -> AttributeModifier.Operation.ADD_SCALAR;
            case "ADD_MULTIPLIED_TOTAL", "MULTIPLY_TOTAL" -> AttributeModifier.Operation.MULTIPLY_SCALAR_1;
            default -> AttributeModifier.Operation.ADD_NUMBER;
        };
    }

    @SuppressWarnings("UnstableApiUsage")
    private static EquipmentSlotGroup mapSlot(String slot) {
        if (slot == null || slot.isBlank() || "ANY".equalsIgnoreCase(slot)) {
            return EquipmentSlotGroup.ANY;
        }
        String u = slot.toUpperCase(Locale.ROOT);
        try {
            return switch (u) {
                case "MAINHAND", "HAND" -> EquipmentSlotGroup.MAINHAND;
                case "OFFHAND" -> EquipmentSlotGroup.OFFHAND;
                case "HEAD" -> EquipmentSlotGroup.HEAD;
                case "CHEST" -> EquipmentSlotGroup.CHEST;
                case "LEGS" -> EquipmentSlotGroup.LEGS;
                case "FEET" -> EquipmentSlotGroup.FEET;
                case "ARMOR" -> EquipmentSlotGroup.ARMOR;
                default -> EquipmentSlotGroup.ANY;
            };
        } catch (Throwable t) {
            return EquipmentSlotGroup.ANY;
        }
    }

    public record Result(boolean success, String error, ItemStack stack) {
        public static Result ok(ItemStack stack) {
            return new Result(true, null, stack);
        }

        public static Result error(String error) {
            return new Result(false, error, null);
        }
    }
}
