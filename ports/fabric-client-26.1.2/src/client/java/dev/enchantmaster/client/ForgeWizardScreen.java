package dev.enchantmaster.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Lightweight multi-step forge wizard for Paper + Fabric clients (MC 26.x GuiGraphicsExtractor).
 */
public final class ForgeWizardScreen extends Screen {
    private final boolean canForgeForOthers;
    private int step = 0;
    private EditBox itemBox;
    private EditBox enchantBox;
    private EditBox nameBox;
    private EditBox targetBox;
    private String status = "";

    public ForgeWizardScreen(boolean canForgeForOthers) {
        super(Component.literal("Enchant Master"));
        this.canForgeForOthers = canForgeForOthers;
    }

    @Override
    protected void init() {
        clearWidgets();
        int cx = width / 2;
        int y = height / 4;
        if (step == 0) {
            itemBox = new EditBox(font, cx - 100, y, 200, 20, Component.literal("Item id"));
            itemBox.setValue("minecraft:diamond_sword");
            itemBox.setMaxLength(128);
            addRenderableWidget(itemBox);
            addRenderableWidget(Button.builder(Component.literal("Next"), b -> {
                step = 1;
                init();
            }).bounds(cx - 50, y + 40, 100, 20).build());
        } else if (step == 1) {
            enchantBox = new EditBox(font, cx - 100, y, 200, 20, Component.literal("Enchant id:level"));
            enchantBox.setValue("minecraft:sharpness:5");
            enchantBox.setMaxLength(256);
            addRenderableWidget(enchantBox);
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
                step = 0;
                init();
            }).bounds(cx - 110, y + 40, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Next"), b -> {
                step = 2;
                init();
            }).bounds(cx + 10, y + 40, 100, 20).build());
        } else {
            nameBox = new EditBox(font, cx - 100, y, 200, 20, Component.literal("Name"));
            nameBox.setValue("Forged Blade");
            nameBox.setMaxLength(128);
            addRenderableWidget(nameBox);
            targetBox = new EditBox(font, cx - 100, y + 30, 200, 20, Component.literal("Target player"));
            if (minecraft != null && minecraft.player != null) {
                targetBox.setValue(minecraft.player.getGameProfile().name());
            }
            targetBox.setMaxLength(32);
            targetBox.setEditable(canForgeForOthers);
            addRenderableWidget(targetBox);
            addRenderableWidget(Button.builder(Component.literal("Back"), b -> {
                step = 1;
                init();
            }).bounds(cx - 110, y + 70, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Forge"), b -> doForge())
                    .bounds(cx + 10, y + 70, 100, 20).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(cx - 40, height - 40, 80, 20).build());
    }

    private void doForge() {
        String item = itemBox != null ? itemBox.getValue() : "minecraft:diamond_sword";
        String ench = enchantBox != null ? enchantBox.getValue() : "";
        String name = nameBox != null ? nameBox.getValue() : "";
        String target = targetBox != null ? targetBox.getValue() : "";
        StringBuilder json = new StringBuilder();
        json.append("{\"itemId\":\"").append(esc(item)).append("\",\"overrideLimits\":true,");
        json.append("\"name\":{\"text\":\"").append(esc(name)).append("\",\"italic\":false},");
        json.append("\"lore\":[],\"enchantments\":[");
        if (ench != null && !ench.isBlank()) {
            String[] parts = ench.split(":");
            if (parts.length >= 3) {
                String id = parts[0] + ":" + parts[1];
                String level = parts[2];
                json.append("{\"id\":\"").append(esc(id)).append("\",\"level\":").append(level).append("}");
            }
        }
        json.append("],\"attributes\":[],");
        json.append("\"targetPlayerName\":\"").append(esc(target)).append("\",\"dryRun\":false}");
        status = "Sending…";
        EnchantMasterFabricClient.sendForge(json.toString());
    }

    public void onResult(boolean ok, String message) {
        status = (ok ? "OK: " : "ERR: ") + (message == null ? "" : message);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        extractBackground(graphics, mouseX, mouseY, delta);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        graphics.centeredText(font, "Enchant Master — Step " + (step + 1) + "/3", width / 2, 20, 0xFFFFFF);
        if (status != null && !status.isEmpty()) {
            graphics.centeredText(font, status, width / 2, height - 60, 0xA0FFA0);
        }
        String hint = switch (step) {
            case 0 -> "Item id (e.g. minecraft:diamond_sword)";
            case 1 -> "Enchant as namespace:path:level";
            default -> "Name + target player";
        };
        graphics.centeredText(font, hint, width / 2, height / 4 - 15, 0xAAAAAA);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
