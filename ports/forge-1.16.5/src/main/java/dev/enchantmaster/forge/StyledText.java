package dev.enchantmaster.forge;

import com.google.gson.JsonObject;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;

/** Name or lore line with optional color and formatting. */
public final class StyledText {
    public final String text;
    public final String color;
    public final boolean bold;
    public final boolean italic;

    public StyledText(String text, String color, boolean bold, boolean italic) {
        this.text = text;
        this.color = color;
        this.bold = bold;
        this.italic = italic;
    }

    public String text() {
        return text;
    }

    public static StyledText fromJson(JsonObject obj) {
        if (obj == null) {
            return new StyledText("", null, false, true);
        }
        String text = obj.has("text") && !obj.get("text").isJsonNull() ? obj.get("text").getAsString() : "";
        String color = obj.has("color") && !obj.get("color").isJsonNull() ? obj.get("color").getAsString() : null;
        boolean bold = obj.has("bold") && obj.get("bold").getAsBoolean();
        boolean italic = !obj.has("italic") || obj.get("italic").getAsBoolean();
        return new StyledText(text, color, bold, italic);
    }

    public static StyledText fromComponent(ITextComponent component, boolean defaultItalic) {
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
            Color textColor = style.getColor();
            if (textColor != null) {
                color = String.format("#%06X", textColor.getValue() & 0xFFFFFF);
            }
        } catch (Exception ignored) {
        }
        boolean bold = Boolean.TRUE.equals(style.isBold());
        Boolean italicFlag = style.isItalic();
        boolean italic = italicFlag != null ? italicFlag.booleanValue() : defaultItalic;
        return new StyledText(text, color, bold, italic);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("text", text == null ? "" : text);
        if (color != null && !color.trim().isEmpty()) {
            o.addProperty("color", color);
        }
        o.addProperty("bold", bold);
        o.addProperty("italic", italic);
        return o;
    }

    public IFormattableTextComponent toComponent() {
        IFormattableTextComponent component = new StringTextComponent(text == null ? "" : text);
        Style style = Style.EMPTY.withItalic(Boolean.valueOf(italic)).withBold(Boolean.valueOf(bold));
        if (color != null && !color.trim().isEmpty()) {
            String colorStr = color.startsWith("#") ? color : "#" + color;
            Color textColor = Color.parseColor(colorStr);
            if (textColor != null) {
                style = style.withColor(textColor);
            }
        }
        return component.setStyle(style);
    }
}
