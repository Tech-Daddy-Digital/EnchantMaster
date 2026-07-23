package dev.enchantmaster.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.enchantmaster.forge.ItemCatalog;
import dev.enchantmaster.network.ForgeRequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Multi-step forge wizard (item → enchants → attributes → style → preview/give).
 * Client-only; only referenced from the client entrypoint.
 */
public class EnchantMasterScreen extends Screen {
    private enum Step {
        ITEM(0, "screen.enchantmaster.step.item"),
        ENCHANTS(1, "screen.enchantmaster.step.enchants"),
        ATTRIBUTES(2, "screen.enchantmaster.step.attributes"),
        STYLE(3, "screen.enchantmaster.step.style"),
        PREVIEW(4, "screen.enchantmaster.step.preview");

        final int index;
        final String titleKey;

        Step(int index, String titleKey) {
            this.index = index;
            this.titleKey = titleKey;
        }

        static Step fromIndex(int i) {
            for (Step s : values()) {
                if (s.index == i) return s;
            }
            return ITEM;
        }
    }

    private final boolean canForgeForOthers;

    // Wizard navigation state (survives rebuildWidgets)
    private int stepIndex = 0;

    private @Nullable String selectedItemId;
    private @Nullable String selectedItemName;
    private boolean overrideLimits;
    private final List<ChosenEnchant> chosenEnchants = new ArrayList<>();
    private final List<ChosenAttr> chosenAttrs = new ArrayList<>();
    private String itemNameText = "";
    private String nameColor = "#55FFFF";
    private boolean nameBold;
    private String loreText = "";
    private String loreColor = "#AAAAAA";
    private @Nullable UUID selectedPlayerUuid;
    private @Nullable String selectedPlayerName;
    private String status = "";
    private boolean statusOk = true;

    // Transient UI fields recreated each step
    private EditBox searchBox;
    private EditBox levelBox;
    private EditBox attrAmountBox;
    private EditBox nameBox;
    private EditBox nameColorBox;
    private EditBox loreBox;
    private EditBox loreColorBox;
    private Checkbox overrideBox;
    private Checkbox nameBoldBox;
    private StringListWidget mainList;
    private StringListWidget secondaryList;
    private @Nullable String selectedEnchantId;
    private @Nullable String selectedAttrId;
    private @Nullable String pendingRemoveId;

    private Button backButton;
    private Button nextButton;

    public EnchantMasterScreen(boolean canForgeForOthers) {
        super(Component.translatable("screen.enchantmaster.forge"));
        this.canForgeForOthers = canForgeForOthers;
    }

    private Step step() {
        return Step.fromIndex(this.stepIndex);
    }

    @Override
    protected void init() {
        // Default target = self
        if (this.selectedPlayerUuid == null && this.minecraft.player != null) {
            this.selectedPlayerUuid = this.minecraft.player.getUUID();
            this.selectedPlayerName = this.minecraft.player.getGameProfile().name();
        }

        int pad = 10;
        int contentTop = 36;
        int footerY = this.height - 28;
        int contentBottom = footerY - 18;
        int contentH = Math.max(60, contentBottom - contentTop);
        int contentW = this.width - pad * 2;

        // Footer navigation always present
        this.backButton = Button.builder(Component.translatable("screen.enchantmaster.back"), b -> goBack())
                .bounds(pad, footerY, 90, 20)
                .build();
        this.addRenderableWidget(this.backButton);
        this.backButton.active = this.stepIndex > 0;

        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> this.onClose())
                .bounds(pad + 96, footerY, 90, 20)
                .build());

        String nextLabel = this.step() == Step.PREVIEW
                ? "screen.enchantmaster.confirm_give"
                : "screen.enchantmaster.next";
        this.nextButton = Button.builder(Component.translatable(nextLabel), b -> goNext())
                .bounds(this.width - pad - 120, footerY, 120, 20)
                .build();
        this.addRenderableWidget(this.nextButton);

        switch (this.step()) {
            case ITEM -> initItemStep(pad, contentTop, contentW, contentH);
            case ENCHANTS -> initEnchantsStep(pad, contentTop, contentW, contentH);
            case ATTRIBUTES -> initAttributesStep(pad, contentTop, contentW, contentH);
            case STYLE -> initStyleStep(pad, contentTop, contentW, contentH);
            case PREVIEW -> initPreviewStep(pad, contentTop, contentW, contentH);
        }

        updateNextEnabled();
    }

    private void initItemStep(int pad, int top, int w, int h) {
        this.searchBox = new EditBox(this.font, pad, top, w, 18, Component.translatable("screen.enchantmaster.search"));
        this.searchBox.setHint(Component.translatable("screen.enchantmaster.search_hint"));
        this.searchBox.setResponder(s -> refreshItemList());
        this.addRenderableWidget(this.searchBox);

        // 20px rows leave room for 16px item icons
        this.mainList = new StringListWidget(this.minecraft, w, h - 24, top + 22, 20, data -> {
            this.selectedItemId = data.id();
            this.selectedItemName = data.label();
            updateNextEnabled();
            setStatus(Component.translatable("screen.enchantmaster.selected", data.label()).getString(), true);
        });
        this.mainList.position(pad, top + 22, w, h - 24);
        this.addRenderableWidget(this.mainList);
        refreshItemList();
    }

    private void initEnchantsStep(int pad, int top, int w, int h) {
        this.overrideBox = Checkbox.builder(Component.translatable("screen.enchantmaster.override"), this.font)
                .pos(pad, top)
                .selected(this.overrideLimits)
                .onValueChange((box, val) -> {
                    this.overrideLimits = val;
                    refreshEnchantList();
                })
                .build();
        this.addRenderableWidget(this.overrideBox);

        int half = (w - 8) / 2;
        int listTop = top + 22;
        int listH = h - 50;

        this.mainList = new StringListWidget(this.minecraft, half, listH, listTop, 18, data -> {
            this.selectedEnchantId = data.id();
            // meta holds max level when present
            if (data.meta() != null && !data.meta().isBlank()) {
                try {
                    this.levelBox.setValue(data.meta());
                } catch (Exception ignored) {
                }
            }
        });
        this.mainList.position(pad, listTop, half, listH);
        this.addRenderableWidget(this.mainList);

        this.secondaryList = new StringListWidget(this.minecraft, half, listH, listTop, 18, data -> {
            // click removes
            this.chosenEnchants.removeIf(e -> e.id().equals(data.id()));
            refreshChosenEnchants();
        });
        this.secondaryList.position(pad + half + 8, listTop, half, listH);
        this.addRenderableWidget(this.secondaryList);

        int barY = listTop + listH + 4;
        this.levelBox = new EditBox(this.font, pad, barY, 40, 18, Component.literal("lvl"));
        this.levelBox.setValue("1");
        this.levelBox.setMaxLength(3);
        this.addRenderableWidget(this.levelBox);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.enchantmaster.add_enchant"), b -> addEnchant())
                .bounds(pad + 44, barY, 100, 18)
                .build());

        refreshEnchantList();
        refreshChosenEnchants();
    }

    private void initAttributesStep(int pad, int top, int w, int h) {
        this.searchBox = new EditBox(this.font, pad, top, w, 18, Component.translatable("screen.enchantmaster.search_attr"));
        this.searchBox.setHint(Component.translatable("screen.enchantmaster.search_attr_hint"));
        this.searchBox.setResponder(s -> refreshAttrList());
        this.addRenderableWidget(this.searchBox);

        int half = (w - 8) / 2;
        int listTop = top + 22;
        int listH = h - 50;

        this.mainList = new StringListWidget(this.minecraft, half, listH, listTop, 18, data -> {
            this.selectedAttrId = data.id();
            // Pre-fill amount from item/enchant default when selecting
            if (data.meta() != null && !data.meta().isBlank() && this.attrAmountBox != null) {
                try {
                    double amt = Double.parseDouble(data.meta());
                    this.attrAmountBox.setValue(formatAmt(amt));
                } catch (Exception ignored) {
                }
            }
        });
        this.mainList.position(pad, listTop, half, listH);
        this.addRenderableWidget(this.mainList);

        this.secondaryList = new StringListWidget(this.minecraft, half, listH, listTop, 18, data -> {
            this.chosenAttrs.removeIf(a -> a.id().equals(data.id()));
            refreshChosenAttrs();
        });
        this.secondaryList.position(pad + half + 8, listTop, half, listH);
        this.addRenderableWidget(this.secondaryList);

        int barY = listTop + listH + 4;
        this.attrAmountBox = new EditBox(this.font, pad, barY, 50, 18, Component.literal("amt"));
        this.attrAmountBox.setValue("1");
        this.attrAmountBox.setMaxLength(8);
        this.addRenderableWidget(this.attrAmountBox);

        this.addRenderableWidget(Button.builder(Component.translatable("screen.enchantmaster.add_attr"), b -> addAttr())
                .bounds(pad + 54, barY, 100, 18)
                .build());

        refreshAttrList();
        refreshChosenAttrs();
    }

    private void initStyleStep(int pad, int top, int w, int h) {
        int y = top;
        int fieldW = Math.min(300, w - 80);

        this.nameBox = new EditBox(this.font, pad, y, fieldW, 18, Component.translatable("screen.enchantmaster.name"));
        this.nameBox.setValue(this.itemNameText);
        this.nameBox.setMaxLength(100);
        this.nameBox.setResponder(s -> this.itemNameText = s);
        this.addRenderableWidget(this.nameBox);

        this.nameColorBox = new EditBox(this.font, pad + fieldW + 6, y, 70, 18, Component.literal("#"));
        this.nameColorBox.setValue(this.nameColor);
        this.nameColorBox.setMaxLength(7);
        this.nameColorBox.setResponder(s -> this.nameColor = s);
        this.addRenderableWidget(this.nameColorBox);

        y += 24;
        this.nameBoldBox = Checkbox.builder(Component.translatable("screen.enchantmaster.bold"), this.font)
                .pos(pad, y)
                .selected(this.nameBold)
                .onValueChange((box, val) -> this.nameBold = val)
                .build();
        this.addRenderableWidget(this.nameBoldBox);

        y += 28;
        this.loreBox = new EditBox(this.font, pad, y, fieldW, 18, Component.translatable("screen.enchantmaster.lore"));
        this.loreBox.setValue(this.loreText);
        this.loreBox.setMaxLength(200);
        this.loreBox.setResponder(s -> this.loreText = s);
        this.addRenderableWidget(this.loreBox);

        this.loreColorBox = new EditBox(this.font, pad + fieldW + 6, y, 70, 18, Component.literal("#"));
        this.loreColorBox.setValue(this.loreColor);
        this.loreColorBox.setMaxLength(7);
        this.loreColorBox.setResponder(s -> this.loreColor = s);
        this.addRenderableWidget(this.loreColorBox);
    }

    private void initPreviewStep(int pad, int top, int w, int h) {
        // Left: player list (if OP). Right side is drawn in render as preview text.
        if (this.canForgeForOthers) {
            int listW = Math.min(180, w / 3);
            this.mainList = new StringListWidget(this.minecraft, listW, h, top, 18, data -> {
                try {
                    this.selectedPlayerUuid = UUID.fromString(data.id());
                    this.selectedPlayerName = data.label();
                } catch (Exception e) {
                    this.selectedPlayerUuid = null;
                    this.selectedPlayerName = data.label();
                }
                updateNextEnabled();
            });
            this.mainList.position(pad, top, listW, h);
            this.addRenderableWidget(this.mainList);
            refreshPlayers();
        }
    }

    private HolderLookup.Provider registries() {
        if (this.minecraft.player != null) {
            return this.minecraft.player.registryAccess();
        }
        if (this.minecraft.level != null) {
            return this.minecraft.level.registryAccess();
        }
        throw new IllegalStateException("No registry access");
    }

    private void refreshItemList() {
        if (this.mainList == null) return;
        try {
            String q = this.searchBox != null ? this.searchBox.getValue() : "";
            JsonArray arr = ItemCatalog.listItems(registries(), q, null, 5000, 0);
            List<StringListWidget.EntryData> data = new ArrayList<>();
            arr.forEach(el -> {
                JsonObject o = el.getAsJsonObject();
                String id = o.get("id").getAsString();
                // Already disambiguated as "Name - Mod" when needed by ItemCatalog
                String label = o.get("name").getAsString();
                ItemStack icon = ItemStack.EMPTY;
                try {
                    Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                    if (item != null) {
                        icon = new ItemStack(item);
                    }
                } catch (Exception ignored) {
                }
                data.add(new StringListWidget.EntryData(id, label, id, icon.isEmpty() ? null : icon));
            });
            this.mainList.setEntries(data);
            if (data.isEmpty()) {
                setStatus(Component.translatable("screen.enchantmaster.no_items").getString(), false);
            } else {
                setStatus(Component.translatable("screen.enchantmaster.item_count", data.size()).getString(), true);
            }
        } catch (Exception e) {
            setStatus("Failed to list items: " + e.getMessage(), false);
        }
    }

    private void refreshEnchantList() {
        if (this.mainList == null) return;
        try {
            JsonArray arr = ItemCatalog.listEnchantments(
                    registries(), null, this.selectedItemId, this.overrideLimits);
            List<StringListWidget.EntryData> data = new ArrayList<>();
            arr.forEach(el -> {
                JsonObject o = el.getAsJsonObject();
                if (!this.overrideLimits && o.has("compatible") && !o.get("compatible").getAsBoolean()) {
                    return;
                }
                int max = o.get("maxLevel").getAsInt();
                data.add(new StringListWidget.EntryData(
                        o.get("id").getAsString(),
                        o.get("name").getAsString() + " (max " + max + ")",
                        String.valueOf(Math.max(1, max))
                ));
            });
            data.sort(Comparator.comparing(StringListWidget.EntryData::label, String.CASE_INSENSITIVE_ORDER));
            this.mainList.setEntries(data);
            setStatus(Component.translatable("screen.enchantmaster.enchant_count", data.size()).getString(), true);
        } catch (Exception e) {
            setStatus("Failed to list enchants: " + e.getMessage(), false);
        }
    }

    private void refreshChosenEnchants() {
        if (this.secondaryList == null) return;
        List<StringListWidget.EntryData> data = new ArrayList<>();
        for (ChosenEnchant e : this.chosenEnchants) {
            data.add(new StringListWidget.EntryData(e.id(), e.displayName() + " " + e.level() + "  ✕", ""));
        }
        this.secondaryList.setEntries(data);
    }

    private void refreshAttrList() {
        if (this.mainList == null) return;
        try {
            String q = this.searchBox != null ? this.searchBox.getValue() : "";
            List<ItemCatalog.EnchantLevel> enchants = new ArrayList<>();
            for (ChosenEnchant e : this.chosenEnchants) {
                enchants.add(new ItemCatalog.EnchantLevel(e.id(), e.level()));
            }
            JsonArray arr = ItemCatalog.listRelevantAttributes(
                    registries(),
                    this.selectedItemId,
                    enchants,
                    q
            );
            List<StringListWidget.EntryData> data = new ArrayList<>();
            arr.forEach(el -> {
                JsonObject o = el.getAsJsonObject();
                String id = o.get("id").getAsString();
                String label = o.has("label") ? o.get("label").getAsString() : o.get("name").getAsString();
                // meta stores default amount for pre-fill
                String amountMeta = o.has("amount") ? String.valueOf(o.get("amount").getAsDouble()) : "1";
                data.add(new StringListWidget.EntryData(id, label, amountMeta));
            });
            this.mainList.setEntries(data);
            if (data.isEmpty()) {
                setStatus(Component.translatable("screen.enchantmaster.no_relevant_attrs").getString(), true);
            } else {
                setStatus(Component.translatable("screen.enchantmaster.attr_count", data.size()).getString(), true);
            }
        } catch (Exception e) {
            setStatus("Failed to list attributes: " + e.getMessage(), false);
        }
    }

    private void refreshChosenAttrs() {
        if (this.secondaryList == null) return;
        List<StringListWidget.EntryData> data = new ArrayList<>();
        for (ChosenAttr a : this.chosenAttrs) {
            String sign = a.amount() >= 0 ? "+" : "";
            String name = a.displayName() != null ? a.displayName() : a.id();
            data.add(new StringListWidget.EntryData(a.id(), name + " " + sign + formatAmt(a.amount()) + "  ✕", ""));
        }
        this.secondaryList.setEntries(data);
    }

    private static String formatAmt(double amount) {
        if (amount == (long) amount) return Long.toString((long) amount);
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    private void refreshPlayers() {
        if (this.mainList == null) return;
        List<StringListWidget.EntryData> data = new ArrayList<>();
        if (this.minecraft.getConnection() != null) {
            for (PlayerInfo info : this.minecraft.getConnection().getListedOnlinePlayers()) {
                data.add(new StringListWidget.EntryData(info.getProfile().id().toString(), info.getProfile().name()));
            }
        }
        data.sort(Comparator.comparing(StringListWidget.EntryData::label, String.CASE_INSENSITIVE_ORDER));
        this.mainList.setEntries(data);
    }

    private void addEnchant() {
        if (this.selectedEnchantId == null) {
            setStatus(Component.translatable("screen.enchantmaster.pick_enchant").getString(), false);
            return;
        }
        int level;
        try {
            level = Integer.parseInt(this.levelBox.getValue().trim());
        } catch (Exception e) {
            setStatus(Component.translatable("screen.enchantmaster.invalid_level").getString(), false);
            return;
        }
        if (level < 1) {
            setStatus(Component.translatable("screen.enchantmaster.invalid_level").getString(), false);
            return;
        }
        String display = this.selectedEnchantId;
        // Prefer the selected row label without max suffix
        if (this.mainList != null && this.mainList.getSelected() != null) {
            String label = this.mainList.getSelected().data().label();
            int idx = label.indexOf(" (max");
            display = idx > 0 ? label.substring(0, idx) : label;
        }
        final int lvl = level;
        this.chosenEnchants.removeIf(e -> e.id().equals(this.selectedEnchantId));
        this.chosenEnchants.add(new ChosenEnchant(this.selectedEnchantId, display, lvl));
        refreshChosenEnchants();
        setStatus(Component.translatable("screen.enchantmaster.added_enchant", display, lvl).getString(), true);
    }

    private void addAttr() {
        if (this.selectedAttrId == null) {
            setStatus(Component.translatable("screen.enchantmaster.pick_attr").getString(), false);
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(this.attrAmountBox.getValue().trim());
        } catch (Exception e) {
            setStatus(Component.translatable("screen.enchantmaster.invalid_amount").getString(), false);
            return;
        }
        String display = this.selectedAttrId;
        if (this.mainList != null && this.mainList.getSelected() != null) {
            String label = this.mainList.getSelected().data().label();
            // Strip amount/source suffix for clean display name
            int cut = label.indexOf(" (");
            display = cut > 0 ? label.substring(0, cut) : label;
            int dash = display.indexOf(" — ");
            if (dash > 0) display = display.substring(0, dash);
        }
        this.chosenAttrs.removeIf(a -> a.id().equals(this.selectedAttrId));
        this.chosenAttrs.add(new ChosenAttr(this.selectedAttrId, display, amount));
        refreshChosenAttrs();
        setStatus(Component.translatable("screen.enchantmaster.added_attr", display).getString(), true);
    }

    private void goBack() {
        if (this.stepIndex <= 0) {
            return;
        }
        // Persist edit fields before rebuild
        captureStyleFields();
        this.stepIndex--;
        this.status = "";
        this.rebuildWidgets();
    }

    private void goNext() {
        captureStyleFields();
        if (!validateCurrentStep()) {
            return;
        }
        if (this.step() == Step.PREVIEW) {
            submitForge();
            return;
        }
        this.stepIndex++;
        this.status = "";
        this.rebuildWidgets();
    }

    private void captureStyleFields() {
        if (this.nameBox != null) this.itemNameText = this.nameBox.getValue();
        if (this.nameColorBox != null) this.nameColor = this.nameColorBox.getValue();
        if (this.loreBox != null) this.loreText = this.loreBox.getValue();
        if (this.loreColorBox != null) this.loreColor = this.loreColorBox.getValue();
        if (this.nameBoldBox != null) this.nameBold = this.nameBoldBox.selected();
        if (this.overrideBox != null) this.overrideLimits = this.overrideBox.selected();
    }

    private boolean validateCurrentStep() {
        return switch (this.step()) {
            case ITEM -> {
                if (this.selectedItemId == null) {
                    setStatus(Component.translatable("screen.enchantmaster.need_item").getString(), false);
                    yield false;
                }
                yield true;
            }
            case ENCHANTS, ATTRIBUTES, STYLE -> true;
            case PREVIEW -> {
                if (this.selectedPlayerUuid == null && this.minecraft.player != null) {
                    this.selectedPlayerUuid = this.minecraft.player.getUUID();
                    this.selectedPlayerName = this.minecraft.player.getGameProfile().name();
                }
                if (this.selectedPlayerUuid == null) {
                    setStatus(Component.translatable("screen.enchantmaster.need_player").getString(), false);
                    yield false;
                }
                yield true;
            }
        };
    }

    private void updateNextEnabled() {
        if (this.nextButton == null) return;
        if (this.step() == Step.ITEM) {
            this.nextButton.active = this.selectedItemId != null;
        } else {
            this.nextButton.active = true;
        }
    }

    private void submitForge() {
        if (this.selectedItemId == null) {
            setStatus(Component.translatable("screen.enchantmaster.need_item").getString(), false);
            return;
        }
        if (this.selectedPlayerUuid == null && this.minecraft.player != null) {
            this.selectedPlayerUuid = this.minecraft.player.getUUID();
            this.selectedPlayerName = this.minecraft.player.getGameProfile().name();
        }

        JsonObject body = new JsonObject();
        body.addProperty("itemId", this.selectedItemId);
        body.addProperty("overrideLimits", this.overrideLimits);
        body.addProperty("dryRun", false);

        JsonObject name = new JsonObject();
        name.addProperty("text", this.itemNameText);
        name.addProperty("color", normalizeColor(this.nameColor));
        name.addProperty("bold", this.nameBold);
        name.addProperty("italic", false);
        body.add("name", name);

        JsonArray lore = new JsonArray();
        if (this.loreText != null && !this.loreText.isBlank()) {
            JsonObject line = new JsonObject();
            line.addProperty("text", this.loreText);
            line.addProperty("color", normalizeColor(this.loreColor));
            line.addProperty("italic", true);
            line.addProperty("bold", false);
            lore.add(line);
        }
        body.add("lore", lore);

        JsonArray enchants = new JsonArray();
        for (ChosenEnchant e : this.chosenEnchants) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("level", e.level());
            enchants.add(o);
        }
        body.add("enchantments", enchants);

        JsonArray attrs = new JsonArray();
        for (ChosenAttr a : this.chosenAttrs) {
            JsonObject o = new JsonObject();
            o.addProperty("id", a.id());
            o.addProperty("amount", a.amount());
            o.addProperty("operation", "ADD_VALUE");
            o.addProperty("slot", "mainhand");
            attrs.add(o);
        }
        body.add("attributes", attrs);

        if (this.selectedPlayerUuid != null) {
            body.addProperty("targetPlayerUuid", this.selectedPlayerUuid.toString());
        }
        if (this.selectedPlayerName != null) {
            body.addProperty("targetPlayerName", this.selectedPlayerName);
        }

        ClientPacketDistributor.sendToServer(new ForgeRequestPayload(body.toString()));
        setStatus(Component.translatable("screen.enchantmaster.sending").getString(), true);
    }

    private static String normalizeColor(String raw) {
        if (raw == null || raw.isBlank()) return "#FFFFFF";
        String c = raw.trim();
        if (!c.startsWith("#")) c = "#" + c;
        return c.toUpperCase(Locale.ROOT);
    }

    public void setStatus(String message, boolean ok) {
        this.status = message == null ? "" : message;
        this.statusOk = ok;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float a) {
        super.render(graphics, mouseX, mouseY, a);

        // Header
        String stepTitle = Component.translatable(this.step().titleKey).getString();
        String header = Component.translatable("screen.enchantmaster.forge").getString()
                + "  —  " + (this.stepIndex + 1) + "/5  " + stepTitle;
        graphics.drawString(this.font, header, 10, 12, CommonColors.WHITE, true);

        // Step-specific labels
        int pad = 10;
        int contentTop = 36;
        int half = (this.width - pad * 2 - 8) / 2;

        switch (this.step()) {
            case ENCHANTS -> {
                graphics.drawString(this.font, Component.translatable("screen.enchantmaster.available"),
                        pad, contentTop + 12, 0xFFAAAAAA, false);
                graphics.drawString(this.font, Component.translatable("screen.enchantmaster.chosen"),
                        pad + half + 8, contentTop + 12, 0xFFAAAAAA, false);
                if (this.selectedItemName != null) {
                    graphics.drawString(this.font,
                            Component.translatable("screen.enchantmaster.for_item", this.selectedItemName).getString(),
                            pad + 160, contentTop + 4, 0xFF55FF55, false);
                }
            }
            case ATTRIBUTES -> {
                graphics.drawString(this.font, Component.translatable("screen.enchantmaster.available"),
                        pad, contentTop + 12, 0xFFAAAAAA, false);
                graphics.drawString(this.font, Component.translatable("screen.enchantmaster.chosen"),
                        pad + half + 8, contentTop + 12, 0xFFAAAAAA, false);
            }
            case STYLE -> {
                graphics.drawString(this.font, Component.translatable("screen.enchantmaster.name"),
                        pad, contentTop - 10, 0xFFAAAAAA, false);
            }
            case PREVIEW -> drawPreview(graphics, pad);
            default -> {
            }
        }

        if (!this.status.isBlank()) {
            graphics.drawString(this.font, this.status, 10, this.height - 42,
                    this.statusOk ? 0xFF55FF55 : 0xFFFF5555, false);
        }
    }

    private void drawPreview(GuiGraphics graphics, int pad) {
        int x = this.canForgeForOthers ? pad + Math.min(180, (this.width - pad * 2) / 3) + 12 : pad;
        int y = 40;

        if (this.canForgeForOthers) {
            graphics.drawString(this.font, Component.translatable("screen.enchantmaster.pick_player"),
                    pad, 36 - 10, 0xFFAAAAAA, false);
        }

        // Minecraft-ish tooltip box
        int boxW = Math.min(280, this.width - x - pad);
        int lines = 2 + this.chosenEnchants.size() + (this.loreText.isBlank() ? 0 : 1) + this.chosenAttrs.size();
        int boxH = 12 + lines * 11;
        graphics.fill(x - 3, y - 3, x + boxW + 3, y + boxH + 3, 0xF0100010);
        graphics.renderOutline(x - 3, y - 3, boxW + 6, boxH + 6, 0xFF5000B0);

        String displayName = this.itemNameText.isBlank()
                ? (this.selectedItemName != null ? this.selectedItemName : "?")
                : this.itemNameText;
        int nameCol = parseColor(this.nameColor, 0xFF55FFFF);
        if (this.nameBold) {
            graphics.drawString(this.font, displayName, x + 1, y, nameCol, false);
            graphics.drawString(this.font, displayName, x, y, nameCol, false);
        } else {
            graphics.drawString(this.font, displayName, x, y, nameCol, false);
        }
        y += 12;

        graphics.drawString(this.font, this.selectedItemId != null ? this.selectedItemId : "", x, y, 0xFF808080, false);
        y += 12;

        for (ChosenEnchant e : this.chosenEnchants) {
            graphics.drawString(this.font, e.displayName() + " " + toRoman(e.level()), x, y, 0xFFAAAAAA, false);
            y += 11;
        }
        if (!this.loreText.isBlank()) {
            graphics.drawString(this.font, this.loreText, x, y, parseColor(this.loreColor, 0xFFAAAAAA), false);
            y += 11;
        }
        for (ChosenAttr a : this.chosenAttrs) {
            String sign = a.amount() >= 0 ? "+" : "";
            String name = a.displayName() != null ? a.displayName() : a.id();
            graphics.drawString(this.font, sign + formatAmt(a.amount()) + " " + name, x, y, 0xFF55FF55, false);
            y += 11;
        }

        y += 16;
        String target = this.selectedPlayerName != null ? this.selectedPlayerName : "?";
        if (!this.canForgeForOthers) {
            target += " (" + Component.translatable("screen.enchantmaster.self_only").getString() + ")";
        }
        graphics.drawString(this.font,
                Component.translatable("screen.enchantmaster.will_give", target).getString(),
                x, y, 0xFFFFFF55, false);
    }

    private static int parseColor(String hex, int fallback) {
        try {
            String c = hex.trim();
            if (c.startsWith("#")) c = c.substring(1);
            int rgb = Integer.parseInt(c, 16);
            return 0xFF000000 | rgb;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String toRoman(int n) {
        if (n <= 0) return String.valueOf(n);
        int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] syms = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        int num = Math.min(n, 3999);
        for (int i = 0; i < vals.length; i++) {
            while (num >= vals[i]) {
                sb.append(syms[i]);
                num -= vals[i];
            }
        }
        return sb.toString();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private record ChosenEnchant(String id, String displayName, int level) {
    }

    private record ChosenAttr(String id, String displayName, double amount) {
    }
}
