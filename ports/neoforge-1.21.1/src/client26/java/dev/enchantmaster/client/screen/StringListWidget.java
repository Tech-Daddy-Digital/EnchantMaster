package dev.enchantmaster.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.util.CommonColors;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/**
 * Simple selectable string list for the forge wizard.
 * Client-only; only loaded via the client entrypoint.
 */
public class StringListWidget extends ObjectSelectionList<StringListWidget.Entry> {
    private final Consumer<EntryData> onSelect;
    private boolean suppressSelectCallback;

    public StringListWidget(Minecraft mc, int width, int height, int y, int itemHeight, Consumer<EntryData> onSelect) {
        super(mc, width, height, y, itemHeight);
        this.onSelect = onSelect;
        this.centerListVertically = false;
    }

    public void setEntries(List<EntryData> data) {
        this.suppressSelectCallback = true;
        String previousId = this.getSelected() != null ? this.getSelected().data().id() : null;
        // Reset scroll before repopulating so entry Y positions are calculated from list top
        this.setScrollAmount(0);
        this.clearEntries();
        Entry restore = null;
        for (EntryData d : data) {
            Entry entry = new Entry(d);
            this.addEntry(entry);
            if (previousId != null && previousId.equals(d.id())) {
                restore = entry;
            }
        }
        if (restore != null) {
            super.setSelected(restore);
        }
        this.suppressSelectCallback = false;
    }

    public void position(int x, int y, int width, int height) {
        this.updateSizeAndPosition(width, height, x, y);
    }

    @Override
    public void setSelected(@Nullable Entry selected) {
        super.setSelected(selected);
        if (!this.suppressSelectCallback && selected != null) {
            this.onSelect.accept(selected.data());
        }
    }

    @Override
    public int getRowWidth() {
        return Math.max(20, this.width - 6);
    }

    @Override
    protected int scrollBarX() {
        return this.getX() + this.width - 6;
    }

    public record EntryData(String id, String label, String meta, @Nullable ItemStack icon) {
        public EntryData(String id, String label) {
            this(id, label, "", null);
        }

        public EntryData(String id, String label, String meta) {
            this(id, label, meta, null);
        }
    }

    public class Entry extends ObjectSelectionList.Entry<Entry> {
        private final EntryData data;

        public Entry(EntryData data) {
            this.data = data;
        }

        public EntryData data() {
            return this.data;
        }

        @Override
        public Component getNarration() {
            return Component.literal(this.data.label());
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float a) {
            boolean selected = StringListWidget.this.getSelected() == this;
            int x = this.getContentX();
            int y = this.getContentY();
            int w = this.getContentWidth();
            int h = this.getContentHeight();

            if (selected) {
                graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x80FFFFFF);
            } else if (hovered) {
                graphics.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x40FFFFFF);
            }

            int textX = x + 2;
            ItemStack icon = this.data.icon();
            if (icon != null && !icon.isEmpty()) {
                int iconY = y + Math.max(0, (h - 16) / 2);
                try {
                    graphics.item(icon, textX, iconY);
                } catch (Exception ignored) {
                    // Icon render is best-effort
                }
                textX += 18;
            }

            int color = selected || hovered ? CommonColors.WHITE : 0xFFE0E0E0;
            String label = this.data.label();
            int maxWidth = Math.max(10, w - (textX - x) - 4);
            if (Minecraft.getInstance().font.width(label) > maxWidth) {
                label = Minecraft.getInstance().font.plainSubstrByWidth(label, maxWidth - 6) + "…";
            }
            graphics.text(Minecraft.getInstance().font, label, textX, y + (h - 8) / 2, color, false);
        }

        @Override
        public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
            StringListWidget.this.setSelected(this);
            return true;
        }
    }
}
