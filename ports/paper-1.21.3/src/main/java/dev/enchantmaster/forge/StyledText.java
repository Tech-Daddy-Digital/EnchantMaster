package dev.enchantmaster.forge;

import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

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

    public static StyledText fromComponent(@Nullable Component component, boolean defaultItalic) {
        if (component == null) {
            return new StyledText("", null, false, defaultItalic);
        }
        String text;
        try {
            text = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
        } catch (Exception e) {
            text = "";
        }
        String color = null;
        TextColor tc = component.color();
        if (tc != null) {
            color = String.format("#%06X", tc.value() & 0xFFFFFF);
        }
        boolean bold = component.decoration(TextDecoration.BOLD) == TextDecoration.State.TRUE;
        TextDecoration.State is = component.decoration(TextDecoration.ITALIC);
        boolean italic = is == TextDecoration.State.TRUE
                || (is == TextDecoration.State.NOT_SET && defaultItalic);
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
        Component c = Component.text(text == null ? "" : text);
        if (color != null && !color.isBlank()) {
            String colorStr = color.startsWith("#") ? color : "#" + color;
            TextColor tc = TextColor.fromHexString(colorStr);
            if (tc == null) {
                NamedTextColor named = NamedTextColor.NAMES.value(colorStr.toLowerCase().replace("#", ""));
                if (named != null) c = c.color(named);
            } else {
                c = c.color(tc);
            }
        }
        c = c.decoration(TextDecoration.BOLD, bold);
        c = c.decoration(TextDecoration.ITALIC, italic);
        return c;
    }
}
