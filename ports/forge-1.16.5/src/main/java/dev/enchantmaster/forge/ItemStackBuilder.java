package dev.enchantmaster.forge;

import dev.enchantmaster.EnchantMaster;
import dev.enchantmaster.config.EnchantMasterConfig;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.entity.ai.attributes.Attribute;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.EnchantmentData;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Builds / patches ItemStacks via 1.16.5 NBT (Enchantments, display, AttributeModifiers).
 */
public final class ItemStackBuilder {
    private ItemStackBuilder() {
    }

    public static Result build(MinecraftServer server, ForgeRequest request) {
        if (request.itemId == null || request.itemId.trim().isEmpty()) {
            return Result.error("itemId is required");
        }

        ResourceLocation itemId = ResourceLocation.tryParse(request.itemId);
        if (itemId == null) {
            return Result.error("Invalid item id: " + request.itemId);
        }

        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) {
            return Result.error("Unknown item: " + request.itemId);
        }

        if (item == Items.BOOK && request.enchantments != null && !request.enchantments.isEmpty()) {
            item = Items.ENCHANTED_BOOK;
        }

        ItemStack stack;
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
        int maxOverride = EnchantMasterConfig.MAX_OVERRIDE_LEVEL.get();

        Map<Enchantment, Integer> enchants = new HashMap<Enchantment, Integer>();
        List<Enchantment> applied = new ArrayList<Enchantment>();

        if (request.enchantments != null) {
            for (ForgeRequest.EnchantEntry entry : request.enchantments) {
                if (entry == null || entry.id == null || entry.id.trim().isEmpty()) {
                    return Result.error("Enchantment entry missing id");
                }
                Enchantment enchantment = ItemCatalog.findEnchantment(entry.id);
                if (enchantment == null) {
                    return Result.error("Unknown enchantment: " + entry.id);
                }

                if (!override && !ItemCatalog.canApply(enchantment, stack) && !ItemCatalog.isBookLike(item)) {
                    ResourceLocation key = ForgeRegistries.ITEMS.getKey(item);
                    return Result.error("Enchantment " + entry.id + " cannot be applied to "
                            + (key != null ? key.toString() : "?")
                            + " (enable override to force)");
                }

                int level = entry.level;
                int max = 1;
                try {
                    max = Math.max(1, enchantment.getMaxLevel());
                } catch (Exception ignored) {
                }
                if (!override) {
                    if (level < 1 || level > max) {
                        return Result.error("Level " + level + " out of range for " + entry.id
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
                            ResourceLocation a = ForgeRegistries.ENCHANTMENTS.getKey(existing);
                            return Result.error("Enchantments conflict: "
                                    + (a != null ? a.toString() : "?")
                                    + " and " + entry.id + " (enable override to force)");
                        }
                    }
                }

                enchants.put(enchantment, Integer.valueOf(level));
                applied.add(enchantment);
            }
        }

        try {
            if (!enchants.isEmpty()) {
                if (ItemCatalog.isBookLike(item)) {
                    // Clear then re-add stored enchantments
                    CompoundNBT tag = stack.getOrCreateTag();
                    tag.remove("StoredEnchantments");
                    for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                        EnchantedBookItem.addEnchantment(stack,
                                new EnchantmentData(e.getKey(), e.getValue().intValue()));
                    }
                    // Ensure regular Enchantments list is empty on books
                    tag.remove("Enchantments");
                } else {
                    EnchantmentHelper.setEnchantments(enchants, stack);
                    CompoundNBT tag = stack.getTag();
                    if (tag != null) {
                        tag.remove("StoredEnchantments");
                    }
                }
            } else if (replaceMode) {
                CompoundNBT tag = stack.getTag();
                if (tag != null) {
                    tag.remove("Enchantments");
                    tag.remove("StoredEnchantments");
                }
            }
        } catch (Exception e) {
            return Result.error("Failed to write enchantments: " + e.getMessage());
        }

        // Name
        try {
            if (request.name != null && request.name.text != null && !request.name.text.trim().isEmpty()) {
                stack.setHoverName(request.name.toComponent());
            } else if (replaceMode) {
                stack.resetHoverName();
            }
        } catch (Exception e) {
            return Result.error("Failed to set custom name: " + e.getMessage());
        }

        // Lore via display.Lore (JSON component strings)
        try {
            ListNBT lore = new ListNBT();
            if (request.lore != null) {
                for (StyledText line : request.lore) {
                    if (line != null && line.text != null && !line.text.trim().isEmpty()) {
                        ITextComponent c = line.toComponent();
                        lore.add(StringNBT.valueOf(ITextComponent.Serializer.toJson(c)));
                    }
                }
            }
            if (!lore.isEmpty()) {
                CompoundNBT display = stack.getOrCreateTagElement("display");
                display.put("Lore", lore);
            } else if (replaceMode) {
                CompoundNBT display = stack.getTagElement("display");
                if (display != null) {
                    display.remove("Lore");
                }
            }
        } catch (Exception e) {
            return Result.error("Failed to set lore: " + e.getMessage());
        }

        // AttributeModifiers via ItemStack API / NBT
        try {
            if (request.attributes != null && !request.attributes.isEmpty()) {
                // Clear existing AttributeModifiers NBT so we fully replace
                CompoundNBT tag = stack.getOrCreateTag();
                tag.remove("AttributeModifiers");

                int index = 0;
                for (ForgeRequest.AttributeEntry attr : request.attributes) {
                    if (attr == null || attr.id == null || attr.id.trim().isEmpty()) {
                        return Result.error("Attribute entry missing id");
                    }
                    Attribute attribute = ItemCatalog.findAttribute(attr.id);
                    if (attribute == null) {
                        return Result.error("Unknown attribute: " + attr.id);
                    }
                    AttributeModifier.Operation operation = parseOperation(attr.operation);
                    EquipmentSlotType slot = parseSlot(attr.slot);
                    UUID id = UUID.nameUUIDFromBytes(
                            (EnchantMaster.MODID + ":" + index + ":" + attr.id).getBytes(java.nio.charset.StandardCharsets.UTF_8)
                    );
                    AttributeModifier modifier = new AttributeModifier(
                            id,
                            "enchantmaster_" + index,
                            attr.amount,
                            operation
                    );
                    // null slot = any slot in 1.16 when writing NBT; API requires a slot
                    if (slot != null) {
                        stack.addAttributeModifier(attribute, modifier, slot);
                    } else {
                        // Apply to all relevant slots by writing NBT Slot omitted (ANY)
                        ListNBT list = tag.getList("AttributeModifiers", 10);
                        CompoundNBT entry = modifier.save();
                        ResourceLocation attrId = ForgeRegistries.ATTRIBUTES.getKey(attribute);
                        if (attrId != null) {
                            entry.putString("AttributeName", attrId.toString());
                        }
                        // omit Slot for any
                        list.add(entry);
                        tag.put("AttributeModifiers", list);
                    }
                    index++;
                }
            } else if (replaceMode) {
                CompoundNBT tag = stack.getTag();
                if (tag != null) {
                    tag.remove("AttributeModifiers");
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
        if ("ADD_MULTIPLIED_BASE".equals(key) || "MULTIPLY_BASE".equals(key)) {
            return AttributeModifier.Operation.MULTIPLY_BASE;
        }
        if ("ADD_MULTIPLIED_TOTAL".equals(key) || "MULTIPLY_TOTAL".equals(key)) {
            return AttributeModifier.Operation.MULTIPLY_TOTAL;
        }
        // ADD_VALUE / ADDITION
        return AttributeModifier.Operation.ADDITION;
    }

    /**
     * @return null for ANY (all slots)
     */
    private static EquipmentSlotType parseSlot(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if ("any".equals(key) || "all".equals(key)) return null;
        try {
            return EquipmentSlotType.byName(key);
        } catch (Exception ignored) {
        }
        for (EquipmentSlotType s : EquipmentSlotType.values()) {
            if (s.name().equalsIgnoreCase(key) || s.getName().equalsIgnoreCase(key)) {
                return s;
            }
        }
        return null;
    }

    public static final class Result {
        public final boolean success;
        public final ItemStack stack;
        public final String error;

        private Result(boolean success, ItemStack stack, String error) {
            this.success = success;
            this.stack = stack;
            this.error = error;
        }

        public static Result ok(ItemStack stack) {
            return new Result(true, stack, null);
        }

        public static Result error(String message) {
            return new Result(false, ItemStack.EMPTY, message);
        }

        public boolean success() {
            return success;
        }

        public ItemStack stack() {
            return stack;
        }

        public String error() {
            return error;
        }
    }
}
