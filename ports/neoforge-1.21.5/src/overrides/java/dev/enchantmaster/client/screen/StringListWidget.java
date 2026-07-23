package dev.enchantmaster.client.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Consumer;

/** 1.21.1 ObjectSelectionList entry render API. */
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
        this.setScrollAmount(0);
        this.clearEntries();
        Entry restore = null;
        for (EntryData d : data) {
            Entry entry = new Entry(d);
            this.addEntry(entry);
            if (previousId != null && previousId.equals(d.id())) restore = entry;
        }
        if (restore != null) super.setSelected(restore);
        this.suppressSelectCallback = false;
    }

    public void position(int x, int y, int width, int height) {
        this.setX(x);
        this.setY(y);
        this.setWidth(width);
        this.setHeight(height);
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

    // scrollbar position API varies by version; omit override when absent
    protected int getScrollbarPosition() {
        return this.getX() + this.width - 6;
    }

    public record EntryData(String id, String label, String meta, @Nullable ItemStack icon) {
        public EntryData(String id, String label) { this(id, label, "", null); }
        public EntryData(String id, String label, String meta) { this(id, label, meta, null); }
    }

    public class Entry extends ObjectSelectionList.Entry<Entry> {
        private final EntryData data;

        public Entry(EntryData data) { this.data = data; }
        public EntryData data() { return this.data; }

        @Override
        public Component getNarration() {
            return Component.literal(this.data.label());
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int width, int height,
                          int mouseX, int mouseY, boolean hovering, float partialTick) {
            boolean selected = StringListWidget.this.getSelected() == this;
            if (selected) {
                graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0x80FFFFFF);
            } else if (hovering) {
                graphics.fill(left - 1, top - 1, left + width + 1, top + height + 1, 0x40FFFFFF);
            }
            int textX = left + 2;
            ItemStack icon = this.data.icon();
            if (icon != null && !icon.isEmpty()) {
                try {
                    graphics.renderItem(icon, textX, top + Math.max(0, (height - 16) / 2));
                } catch (Exception ignored) {
                }
                textX += 18;
            }
            int color = selected || hovering ? 0xFFFFFFFF : 0xFFE0E0E0;
            String label = this.data.label();
            int maxWidth = Math.max(10, width - (textX - left) - 4);
            if (Minecraft.getInstance().font.width(label) > maxWidth) {
                label = Minecraft.getInstance().font.plainSubstrByWidth(label, maxWidth - 6) + "…";
            }
            graphics.drawString(Minecraft.getInstance().font, label, textX, top + (height - 8) / 2, color, false);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            StringListWidget.this.setSelected(this);
            return true;
        }
    }
}
