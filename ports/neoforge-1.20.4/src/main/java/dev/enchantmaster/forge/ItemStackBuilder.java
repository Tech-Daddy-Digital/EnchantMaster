package dev.enchantmaster.forge;

import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.config.EnchantMasterConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Builds / patches ItemStacks using classic 1.20.1 NBT (pre data-components).
 */
public final class ItemStackBuilder {
    private ItemStackBuilder() {
    }

    public static Result build(MinecraftServer server, ForgeRequest request) {
        if (request.itemId == null || request.itemId.isBlank()) {
            return Result.error("itemId is required");
        }

        final ResourceLocation itemId;
        try {
            itemId = ResourceLocation.tryParse(request.itemId);
            if (itemId == null) {
                return Result.error("Invalid item id: " + request.itemId);
            }
        } catch (Exception e) {
            return Result.error("Invalid item id: " + request.itemId);
        }

        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(itemId);
        if (itemOpt.isEmpty() || itemOpt.get() == Items.AIR) {
            return Result.error("Unknown item: " + request.itemId);
        }

        Item item = itemOpt.get();
        if (item == Items.BOOK && request.enchantments != null && !request.enchantments.isEmpty()) {
            item = Items.ENCHANTED_BOOK;
        }

        final ItemStack stack;
        try {
            stack = new ItemStack(item);
        } catch (Exception e) {
            return Result.error("Failed to create stack for " + request.itemId + ": " + e.getMessage());
        }

        return applyNbt(stack, item, request, false);
    }

    public static Result applyToExisting(MinecraftServer server, ItemStack base, ForgeRequest request) {
        if (base == null || base.isEmpty()) {
            return Result.error("Cannot modify an empty stack");
        }
        ItemStack stack = base.copy();
        Item item = stack.getItem();
        if (item == Items.BOOK && request.enchantments != null && !request.enchantments.isEmpty()) {
            stack = new ItemStack(Items.ENCHANTED_BOOK, stack.getCount());
            item = Items.ENCHANTED_BOOK;
        }
        return applyNbt(stack, item, request, true);
    }

    private static Result applyNbt(ItemStack stack, Item item, ForgeRequest request, boolean replaceMode) {
        boolean override = request.overrideLimits;
        int maxOverride = EnchantMasterConfig.MAX_OVERRIDE_LEVEL.getAsInt();

        List<Enchantment> applied = new ArrayList<>();
        List<EnchantmentInstance> toApply = new ArrayList<>();

        if (request.enchantments != null) {
            for (ForgeRequest.EnchantEntry entry : request.enchantments) {
                if (entry == null || entry.id() == null || entry.id().isBlank()) {
                    return Result.error("Enchantment entry missing id");
                }
                Optional<Enchantment> enchOpt = ItemCatalog.findEnchantment(entry.id());
                if (enchOpt.isEmpty()) {
                    return Result.error("Unknown enchantment: " + entry.id());
                }
                Enchantment enchantment = enchOpt.get();

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
                    for (Enchantment existing : applied) {
                        if (!ItemCatalog.areCompatible(existing, enchantment)) {
                            ResourceLocation aId = BuiltInRegistries.ENCHANTMENT.getKey(existing);
                            return Result.error("Enchantments conflict: "
                                    + (aId != null ? aId : "?")
                                    + " and " + entry.id() + " (enable override to force)");
                        }
                    }
                }

                applied.add(enchantment);
                toApply.add(new EnchantmentInstance(enchantment, level));
            }
        }

        try {
            if (!toApply.isEmpty()) {
                // Clear previous enchants then apply
                if (ItemCatalog.isBookLike(item)) {
                    CompoundTag tag = stack.getOrCreateTag();
                    tag.remove("StoredEnchantments");
                    tag.remove("Enchantments");
                    for (EnchantmentInstance inst : toApply) {
                        EnchantedBookItem.addEnchantment(stack, inst);
                    }
                } else {
                    EnchantmentHelper.setEnchantments(java.util.Map.of(), stack);
                    for (EnchantmentInstance inst : toApply) {
                        stack.enchant(inst.enchantment, inst.level);
                    }
                }
            } else if (replaceMode) {
                if (ItemCatalog.isBookLike(item)) {
                    if (stack.hasTag()) {
                        stack.getTag().remove("StoredEnchantments");
                        stack.getTag().remove("Enchantments");
                    }
                } else {
                    EnchantmentHelper.setEnchantments(java.util.Map.of(), stack);
                }
            }
        } catch (Exception e) {
            return Result.error("Failed to write enchantments: " + e.getMessage());
        }

        // Name via display.Name (JSON component)
        try {
            if (request.name != null && request.name.text() != null && !request.name.text().isBlank()) {
                stack.setHoverName(request.name.toComponent());
            } else if (replaceMode) {
                stack.resetHoverName();
                if (stack.hasTag()) {
                    CompoundTag display = stack.getTagElement("display");
                    if (display != null) {
                        display.remove("Name");
                        if (display.isEmpty()) {
                            stack.removeTagKey("display");
                        }
                    }
                }
            }
        } catch (Exception e) {
            return Result.error("Failed to set custom name: " + e.getMessage());
        }

        // Lore via display.Lore list of JSON strings
        try {
            List<Component> lines = new ArrayList<>();
            if (request.lore != null) {
                for (StyledText line : request.lore) {
                    if (line != null && line.text() != null && !line.text().isBlank()) {
                        lines.add(line.toComponent());
                    }
                }
            }
            if (!lines.isEmpty()) {
                CompoundTag display = stack.getOrCreateTagElement("display");
                ListTag lore = new ListTag();
                for (Component line : lines) {
                    lore.add(StringTag.valueOf(Component.Serializer.toJson(line)));
                }
                display.put("Lore", lore);
            } else if (replaceMode) {
                CompoundTag display = stack.getTagElement("display");
                if (display != null) {
                    display.remove("Lore");
                    if (display.isEmpty()) {
                        stack.removeTagKey("display");
                    }
                }
            }
        } catch (Exception e) {
            return Result.error("Failed to set lore: " + e.getMessage());
        }

        // Attributes via AttributeModifiers NBT (classic format)
        try {
            if (request.attributes != null && !request.attributes.isEmpty()) {
                ListTag mods = new ListTag();
                int index = 0;
                for (ForgeRequest.AttributeEntry attr : request.attributes) {
                    if (attr == null || attr.id() == null || attr.id().isBlank()) {
                        return Result.error("Attribute entry missing id");
                    }
                    Optional<Attribute> attrOpt = ItemCatalog.findAttribute(attr.id());
                    if (attrOpt.isEmpty()) {
                        return Result.error("Unknown attribute: " + attr.id());
                    }
                    Attribute attribute = attrOpt.get();
                    ResourceLocation attrId = BuiltInRegistries.ATTRIBUTE.getKey(attribute);
                    AttributeModifier.Operation operation = parseOperation(attr.operation());
                    String slot = parseSlotNbt(attr.slot());

                    UUID uuid = UUID.nameUUIDFromBytes(
                            (EnchantMaster.MODID + ":" + index + ":" + (attrId != null ? attrId : attr.id()))
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    );
                    CompoundTag mod = new CompoundTag();
                    mod.putString("AttributeName", attrId != null ? attrId.toString() : attr.id());
                    mod.putString("Name", EnchantMaster.MODID + ".forge_" + index);
                    mod.putDouble("Amount", attr.amount());
                    mod.putInt("Operation", operation.toValue());
                    mod.putUUID("UUID", uuid);
                    if (slot != null && !slot.isEmpty()) {
                        mod.putString("Slot", slot);
                    }
                    mods.add(mod);
                    index++;
                }
                stack.getOrCreateTag().put("AttributeModifiers", mods);
            } else if (replaceMode) {
                if (stack.hasTag()) {
                    stack.getTag().remove("AttributeModifiers");
                }
            }
        } catch (Exception e) {
            return Result.error("Failed to set attributes: " + e.getMessage());
        }

        return Result.ok(stack);
    }

    private static AttributeModifier.Operation parseOperation(String raw) {
        if (raw == null) return AttributeModifier.Operation.ADDITION;
        String key = raw.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "ADD_MULTIPLIED_BASE", "MULTIPLY_BASE" -> AttributeModifier.Operation.MULTIPLY_BASE;
            case "ADD_MULTIPLIED_TOTAL", "MULTIPLY_TOTAL" -> AttributeModifier.Operation.MULTIPLY_TOTAL;
            default -> AttributeModifier.Operation.ADDITION;
        };
    }

    /** NBT Slot string, or null/empty for all slots. */
    private static String parseSlotNbt(String raw) {
        if (raw == null || raw.isBlank() || "any".equalsIgnoreCase(raw) || "all".equalsIgnoreCase(raw)) {
            return null;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getName().equalsIgnoreCase(key) || slot.name().equalsIgnoreCase(key)) {
                return slot.getName();
            }
        }
        // accept common aliases
        return switch (key) {
            case "main_hand", "mainhand" -> EquipmentSlot.MAINHAND.getName();
            case "off_hand", "offhand" -> EquipmentSlot.OFFHAND.getName();
            default -> key;
        };
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
