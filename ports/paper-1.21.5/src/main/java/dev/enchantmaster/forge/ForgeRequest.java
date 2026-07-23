package dev.enchantmaster.forge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ForgeRequest {
    public final String itemId;
    public final boolean overrideLimits;
    public final StyledText name;
    public final List<StyledText> lore;
    public final List<EnchantEntry> enchantments;
    public final List<AttributeEntry> attributes;
    public final UUID targetPlayerUuid;
    public final String targetPlayerName;
    public final boolean dryRun;

    public ForgeRequest(
            String itemId,
            boolean overrideLimits,
            StyledText name,
            List<StyledText> lore,
            List<EnchantEntry> enchantments,
            List<AttributeEntry> attributes,
            UUID targetPlayerUuid,
            String targetPlayerName,
            boolean dryRun
    ) {
        this.itemId = itemId;
        this.overrideLimits = overrideLimits;
        this.name = name;
        this.lore = lore;
        this.enchantments = enchantments;
        this.attributes = attributes;
        this.targetPlayerUuid = targetPlayerUuid;
        this.targetPlayerName = targetPlayerName;
        this.dryRun = dryRun;
    }

    public static ForgeRequest fromJson(JsonObject json) {
        String itemId = json.get("itemId").getAsString();
        boolean overrideLimits = json.has("overrideLimits") && json.get("overrideLimits").getAsBoolean();
        StyledText name = StyledText.fromJson(json.has("name") && json.get("name").isJsonObject()
                ? json.getAsJsonObject("name") : null);

        List<StyledText> lore = new ArrayList<>();
        if (json.has("lore") && json.get("lore").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("lore")) {
                if (el.isJsonObject()) {
                    lore.add(StyledText.fromJson(el.getAsJsonObject()));
                }
            }
        }

        List<EnchantEntry> enchantments = new ArrayList<>();
        if (json.has("enchantments") && json.get("enchantments").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("enchantments")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                enchantments.add(new EnchantEntry(o.get("id").getAsString(), o.get("level").getAsInt()));
            }
        }

        List<AttributeEntry> attributes = new ArrayList<>();
        if (json.has("attributes") && json.get("attributes").isJsonArray()) {
            for (JsonElement el : json.getAsJsonArray("attributes")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                attributes.add(new AttributeEntry(
                        o.get("id").getAsString(),
                        o.get("amount").getAsDouble(),
                        o.has("operation") ? o.get("operation").getAsString() : "ADD_VALUE",
                        o.has("slot") ? o.get("slot").getAsString() : "ANY"
                ));
            }
        }

        UUID uuid = null;
        if (json.has("targetPlayerUuid") && !json.get("targetPlayerUuid").isJsonNull()) {
            uuid = UUID.fromString(json.get("targetPlayerUuid").getAsString());
        }
        String nameTarget = json.has("targetPlayerName") && !json.get("targetPlayerName").isJsonNull()
                ? json.get("targetPlayerName").getAsString() : null;
        boolean dryRun = json.has("dryRun") && json.get("dryRun").getAsBoolean();

        return new ForgeRequest(itemId, overrideLimits, name, lore, enchantments, attributes, uuid, nameTarget, dryRun);
    }

    public record EnchantEntry(String id, int level) {
    }

    public record AttributeEntry(String id, double amount, String operation, String slot) {
    }
}
