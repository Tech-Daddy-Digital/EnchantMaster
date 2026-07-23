package dev.enchantmaster.forge;

import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.config.EnchantMasterConfig;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ItemStackBuilder {
    private ItemStackBuilder() {
    }

    public static Result build(MinecraftServer server, ForgeRequest request) {
        if (request.itemId == null || request.itemId.isBlank()) {
            return Result.error("itemId is required");
        }

        final Identifier itemId;
        try {
            itemId = Identifier.parse(request.itemId);
        } catch (Exception e) {
            return Result.error("Invalid item id: " + request.itemId);
        }

        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemOpt.isEmpty() || itemOpt.get() == Items.AIR) {
            return Result.error("Unknown item: " + request.itemId);
        }

        Item item = itemOpt.get();
        // Enchanted books store enchants differently; convert plain book to enchanted book when needed
        if (item == Items.BOOK && request.enchantments != null && !request.enchantments.isEmpty()) {
            item = Items.ENCHANTED_BOOK;
        }

        final ItemStack stack;
        try {
            stack = new ItemStack(item);
        } catch (Exception e) {
            return Result.error("Failed to create stack for " + request.itemId + ": " + e.getMessage());
        }

        return applyComponents(server, stack, item, request, false);
    }

    /**
     * Patch an existing inventory stack in place (preserves container/backpack data, damage, etc.).
     * Replaces enchantments, attributes, custom name, and lore from the request.
     */
    public static Result applyToExisting(MinecraftServer server, ItemStack base, ForgeRequest request) {
        if (base == null || base.isEmpty()) {
            return Result.error("Cannot modify an empty stack");
        }
        ItemStack stack = base.copy();
        Item item = stack.getItem();
        // Allow book -> enchanted book when adding stored enchants
        if (item == Items.BOOK && request.enchantments != null && !request.enchantments.isEmpty()) {
            stack = new ItemStack(Items.ENCHANTED_BOOK, stack.getCount());
            item = Items.ENCHANTED_BOOK;
        }
        return applyComponents(server, stack, item, request, true);
    }

    /**
     * @param replaceMode when true (inventory modify), clear components when request lists are empty
     */
    private static Result applyComponents(
            MinecraftServer server, ItemStack stack, Item item, ForgeRequest request, boolean replaceMode
    ) {
        boolean override = request.overrideLimits;
        int maxOverride = EnchantMasterConfig.MAX_OVERRIDE_LEVEL.getAsInt();

        // Enchantments
        ItemEnchantments.Mutable enchants = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        List<Holder<Enchantment>> applied = new ArrayList<>();

        if (request.enchantments != null) {
            for (ForgeRequest.EnchantEntry entry : request.enchantments) {
                if (entry == null || entry.id() == null || entry.id().isBlank()) {
                    return Result.error("Enchantment entry missing id");
                }
                Optional<Holder.Reference<Enchantment>> holderOpt = ItemCatalog.findEnchantment(server, entry.id());
                if (holderOpt.isEmpty()) {
                    return Result.error("Unknown enchantment: " + entry.id());
                }
                Holder.Reference<Enchantment> holder = holderOpt.get();
                Enchantment enchantment = holder.value();

                if (!override && !ItemCatalog.canApply(enchantment, stack) && !ItemCatalog.isBookLike(item)) {
                    return Result.error("Enchantment " + entry.id() + " cannot be applied to "
                            + BuiltInRegistries.ITEM.getKey(item)
                            + " (enable override to force)");
                }

                int level = entry.level();
                int max;
                try {
                    max = Math.max(1, enchantment.getMaxLevel());
                } catch (Exception e) {
                    max = 1;
                }
                if (!override) {
                    if (level < 1 || level > max) {
                        return Result.error("Level " + level + " out of range for " + entry.id()
                                + " (1-" + max + "). Enable override for higher levels.");
                    }
                } else {
                    if (level < 1 || level > maxOverride) {
                        return Result.error("Level " + level + " out of override range (1-" + maxOverride + ")");
                    }
                }

                if (!override) {
                    for (Holder<Enchantment> existing : applied) {
                        if (!ItemCatalog.areCompatible(existing, holder)) {
                            return Result.error("Enchantments conflict: " + existing.getRegisteredName()
                                    + " and " + entry.id() + " (enable override to force)");
                        }
                    }
                }

                try {
                    enchants.set(holder, level);
                    applied.add(holder);
                } catch (Exception e) {
                    return Result.error("Failed applying enchantment " + entry.id() + ": " + e.getMessage());
                }
            }
        }

        try {
            ItemEnchantments builtEnchants = enchants.toImmutable();
            if (!builtEnchants.isEmpty()) {
                if (ItemCatalog.isBookLike(item)) {
                    stack.set(DataComponents.STORED_ENCHANTMENTS, builtEnchants);
                    stack.remove(DataComponents.ENCHANTMENTS);
                } else {
                    stack.set(DataComponents.ENCHANTMENTS, builtEnchants);
                    stack.remove(DataComponents.STORED_ENCHANTMENTS);
                }
            } else if (replaceMode) {
                stack.remove(DataComponents.ENCHANTMENTS);
                stack.remove(DataComponents.STORED_ENCHANTMENTS);
            }
        } catch (Exception e) {
            return Result.error("Failed to write enchantments: " + e.getMessage());
        }

        // Name
        try {
            if (request.name != null && request.name.text() != null && !request.name.text().isBlank()) {
                stack.set(DataComponents.CUSTOM_NAME, request.name.toComponent());
            } else if (replaceMode) {
                stack.remove(DataComponents.CUSTOM_NAME);
            }
        } catch (Exception e) {
            return Result.error("Failed to set custom name: " + e.getMessage());
        }

        // Lore
        try {
            List<net.minecraft.network.chat.Component> lines = new ArrayList<>();
            if (request.lore != null) {
                for (StyledText line : request.lore) {
                    if (line != null && line.text() != null && !line.text().isBlank()) {
                        lines.add(line.toComponent());
                    }
                }
            }
            if (!lines.isEmpty()) {
                stack.set(DataComponents.LORE, new ItemLore(lines));
            } else if (replaceMode) {
                stack.remove(DataComponents.LORE);
            }
        } catch (Exception e) {
            return Result.error("Failed to set lore: " + e.getMessage());
        }

        // Attributes
        try {
            if (request.attributes != null && !request.attributes.isEmpty()) {
                ItemAttributeModifiers.Builder attrBuilder = ItemAttributeModifiers.builder();
                int index = 0;
                for (ForgeRequest.AttributeEntry attr : request.attributes) {
                    if (attr == null || attr.id() == null || attr.id().isBlank()) {
                        return Result.error("Attribute entry missing id");
                    }
                    Optional<Holder.Reference<Attribute>> attrHolder = ItemCatalog.findAttribute(attr.id());
                    if (attrHolder.isEmpty()) {
                        return Result.error("Unknown attribute: " + attr.id());
                    }
                    AttributeModifier.Operation operation = parseOperation(attr.operation());
                    EquipmentSlotGroup slot = parseSlot(attr.slot());
                    String safePath = attrHolder.get().key().identifier().getPath()
                            .replaceAll("[^a-z0-9/._-]", "_");
                    Identifier modifierId = Identifier.fromNamespaceAndPath(
                            EnchantMaster.MODID,
                            "forge_" + index + "_" + safePath
                    );
                    attrBuilder.add(
                            attrHolder.get(),
                            new AttributeModifier(modifierId, attr.amount(), operation),
                            slot
                    );
                    index++;
                }
                stack.set(DataComponents.ATTRIBUTE_MODIFIERS, attrBuilder.build());
            } else if (replaceMode) {
                stack.remove(DataComponents.ATTRIBUTE_MODIFIERS);
            }
        } catch (Exception e) {
            return Result.error("Failed to set attributes: " + e.getMessage());
        }

        return Result.ok(stack);
    }

    private static AttributeModifier.Operation parseOperation(String raw) {
        if (raw == null) return AttributeModifier.Operation.ADD_VALUE;
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ADD_MULTIPLIED_BASE", "MULTIPLY_BASE" -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case "ADD_MULTIPLIED_TOTAL", "MULTIPLY_TOTAL" -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
            default -> AttributeModifier.Operation.ADD_VALUE;
        };
    }

    private static EquipmentSlotGroup parseSlot(String raw) {
        if (raw == null || raw.isBlank()) return EquipmentSlotGroup.ANY;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (EquipmentSlotGroup group : EquipmentSlotGroup.values()) {
            if (group.getSerializedName().equalsIgnoreCase(key) || group.name().equalsIgnoreCase(key)) {
                return group;
            }
        }
        return EquipmentSlotGroup.ANY;
    }

    public record Result(boolean success, ItemStack stack, String error) {
        public static Result ok(ItemStack stack) {
            return new Result(true, stack, null);
        }

        public static Result error(String message) {
            return new Result(false, ItemStack.EMPTY, message);
        }
    }
}
