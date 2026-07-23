package dev.enchantmaster.forge;

import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.jspecify.annotations.Nullable;

/** Name or lore line with optional color and formatting. */
public record StyledText(String text, @Nullable String color, boolean bold, boolean italic) {

    public static StyledText fromJson(@Nullable JsonObject obj) {
        if (obj == null) {
            return new StyledText("", null, false, true);
        }
        String text = obj.has("text") && !obj.get("text").isJsonNull() ? obj.get("text").getAsString() : "";
        String color = obj.has("color") && !obj.get("color").isJsonNull() ? obj.get("color").getAsString() : null;
        boolean bold = obj.has("bold") && obj.get("bold").getAsBoolean();
        boolean italic = !obj.has("italic") || obj.get("italic").getAsBoolean();
        return new StyledText(text, color, bold, italic);
    }

    /**
     * Best-effort conversion of a chat component into editable styled text.
     * @param defaultItalic used when the component does not specify italic (lore defaults true; names false)
     */
    public static StyledText fromComponent(@Nullable Component component, boolean defaultItalic) {
        if (component == null) {
            return new StyledText("", null, false, defaultItalic);
        }
        String text;
        try {
            text = component.getString();
        } catch (Exception e) {
            text = "";
        }
        Style style;
        try {
            style = component.getStyle();
        } catch (Exception e) {
            style = Style.EMPTY;
        }
        String color = null;
        try {
            TextColor textColor = style.getColor();
            if (textColor != null) {
                color = String.format("#%06X", textColor.getValue() & 0xFFFFFF);
            }
        } catch (Exception ignored) {
        }
        boolean bold = Boolean.TRUE.equals(style.isBold());
        Boolean italicFlag = style.isItalic();
        boolean italic = italicFlag != null ? italicFlag : defaultItalic;
        return new StyledText(text, color, bold, italic);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("text", text == null ? "" : text);
        if (color != null && !color.isBlank()) {
            o.addProperty("color", color);
        }
        o.addProperty("bold", bold);
        o.addProperty("italic", italic);
        return o;
    }

    public Component toComponent() {
        MutableComponent component = Component.literal(text == null ? "" : text);
        Style style = Style.EMPTY.withItalic(italic).withBold(bold);
        if (color != null && !color.isBlank()) {
            String colorStr = color.startsWith("#") ? color : "#" + color;
            DataResult<TextColor> parsed = TextColor.parseColor(colorStr);
            TextColor textColor = parsed.result().orElse(null);
            if (textColor != null) {
                style = style.withColor(textColor);
            }
        }
        return component.setStyle(style);
    }
}
