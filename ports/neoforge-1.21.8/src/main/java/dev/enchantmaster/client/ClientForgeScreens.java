package dev.enchantmaster.client;

import dev.enchantmaster.client.screen.EnchantMasterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Client-only helpers; only referenced from {@link EnchantMasterClient}.
 * Screen open/query is version-tolerant across Minecraft 26.1 (Minecraft#setScreen)
 * and 26.2 (Minecraft#gui#setScreen / #screen()).
 */
public final class ClientForgeScreens {
    private ClientForgeScreens() {
    }

    public static void open(boolean canForgeForOthers) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        setScreen(mc, new EnchantMasterScreen(canForgeForOthers));
    }

    public static void onForgeResult(boolean success, String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Component msg = Component.literal(message).withColor(success ? 0x55FF55 : 0xFF5555);
            // 26.x: sendSystemMessage; 1.21.x LocalPlayer: displayClientMessage
            try {
                mc.player.getClass()
                        .getMethod("sendSystemMessage", Component.class)
                        .invoke(mc.player, msg);
            } catch (ReflectiveOperationException e1) {
                try {
                    mc.player.getClass()
                            .getMethod("displayClientMessage", Component.class, boolean.class)
                            .invoke(mc.player, msg, false);
                } catch (ReflectiveOperationException e2) {
                    // ignore
                }
            }
        }
        Screen current = getScreen(mc);
        if (current instanceof EnchantMasterScreen screen) {
            screen.setStatus(message, success);
        }
    }

    /** Open a screen (26.1 setScreen / 26.2 gui.setScreen). */
    static void setScreen(Minecraft mc, Screen screen) {
        // Prefer 26.2 Gui API
        try {
            Field guiField = Minecraft.class.getField("gui");
            Object gui = guiField.get(mc);
            Method set = gui.getClass().getMethod("setScreen", Screen.class);
            set.invoke(gui, screen);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        // 26.1 and earlier
        try {
            Method set = Minecraft.class.getMethod("setScreen", Screen.class);
            set.invoke(mc, screen);
            return;
        } catch (ReflectiveOperationException ignored) {
        }
        // 26.2 also has setScreenAndShow
        try {
            Method set = Minecraft.class.getMethod("setScreenAndShow", Screen.class);
            set.invoke(mc, screen);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot open screen on this Minecraft version", e);
        }
    }

    static Screen getScreen(Minecraft mc) {
        try {
            Field guiField = Minecraft.class.getField("gui");
            Object gui = guiField.get(mc);
            Method screen = gui.getClass().getMethod("screen");
            Object result = screen.invoke(gui);
            return result instanceof Screen s ? s : null;
        } catch (ReflectiveOperationException ignored) {
        }
        try {
            Field screenField = Minecraft.class.getField("screen");
            Object result = screenField.get(mc);
            return result instanceof Screen s ? s : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
