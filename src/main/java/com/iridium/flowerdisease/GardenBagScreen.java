package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

// No custom texture (none of us can draw pixel art for this mod) - the background/slots are drawn as
// plain rectangles instead of a chest-style image. Above the 27-slot "cauldron" grid (see GardenBagMenu),
// this renders a live preview of what the current pile of items actually means (GardenBagContents),
// recomputed every frame straight from the menu's synced slot contents - throw items in, watch the
// numbers update, instead of reading fixed per-slot labels. Slot layout here must match GardenBagMenu's
// addSlot coordinates exactly.
public class GardenBagScreen extends AbstractContainerScreen<GardenBagMenu> {
    private static final int SLOT_BORDER = 0xFF000000;
    private static final int SLOT_FILL = 0x77000000;
    private static final int PANEL_FILL = 0xC0101010;
    private static final int PANEL_BORDER = 0xFF3F3F3F;
    private static final int LABEL_COLOR = 0xFFE0E0E0;
    private static final int INFO_COLOR = 0xFFFFD880;
    private static final int INFO_TOP_Y = 16;
    private static final int LINE_HEIGHT = 10;

    public GardenBagScreen(GardenBagMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = GardenBagMenu.IMAGE_HEIGHT;
        this.inventoryLabelY = GardenBagMenu.INVENTORY_TOP_Y - 8;
    }

    // AbstractContainerScreen never draws the tooltip of the hovered slot by itself - each subclass asks for it, the way
    // vanilla's chest screen does - so without this the items in the bag showed no name at all.
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    // The item's usual tooltip, plus - for the modifier items - what the bag makes of it.
    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = new ArrayList<>(super.getTooltipFromContainerItem(stack));
        String effect = effectKey(stack);
        if (effect != null) {
            lines.add(Component.translatable("item.flowerdisease.garden_bag.tooltip." + effect).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static String effectKey(ItemStack stack) {
        if (stack.is(GardenBagContents.GENERATIONS_ITEM)) {
            return "generations";
        }
        if (stack.is(GardenBagContents.DECAY_ITEM)) {
            return "decay";
        }
        if (stack.is(GardenBagContents.NO_DECAY_ITEM)) {
            return "no_decay";
        }
        if (stack.is(GardenBagContents.LIFETIME_ITEM)) {
            return "lifetime";
        }
        if (stack.is(GardenBagContents.DENSITY_ITEM)) {
            return "density";
        }
        if (stack.is(GardenBagContents.RANGE_ITEM)) {
            return "range";
        }
        if (stack.is(GardenBagContents.IGNORE_OTHERS_ITEM)) {
            return "ignore_others";
        }
        if (stack.is(GardenBagContents.CLIMBING_ITEM)) {
            return "climbing";
        }
        if (stack.is(GardenBagContents.FLOWER_BLOCK_ITEM)) {
            return "flower_block";
        }
        return FlowerDisease.bagOutcomeItems().containsKey(stack.getItem()) ? "species" : null;
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_FILL);
        guiGraphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, PANEL_BORDER);
        guiGraphics.renderOutline(leftPos + 6, topPos + INFO_TOP_Y - 4, imageWidth - 12, GardenBagMenu.GRID_TOP_Y - INFO_TOP_Y - 4, PANEL_BORDER);

        for (Slot slot : this.menu.slots) {
            int x = leftPos + slot.x - 1;
            int y = topPos + slot.y - 1;
            guiGraphics.fill(x, y, x + 18, y + 18, SLOT_BORDER);
            guiGraphics.fill(x + 1, y + 1, x + 17, y + 17, SLOT_FILL);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        guiGraphics.drawString(this.font, this.title, this.titleLabelX, this.titleLabelY, LABEL_COLOR, false);

        int y = INFO_TOP_Y;
        int maxWidth = imageWidth - 16;
        for (String line : previewLines(GardenBagContents.read(bagSlotItems()))) {
            // Never let a long value spill past the panel edge.
            String shown = this.font.width(line) > maxWidth ? this.font.plainSubstrByWidth(line, maxWidth - this.font.width("...")) + "..." : line;
            guiGraphics.drawString(this.font, shown, 8, y, INFO_COLOR, false);
            y += LINE_HEIGHT;
        }

        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, LABEL_COLOR, false);
    }

    private List<ItemStack> bagSlotItems() {
        NonNullList<ItemStack> items = NonNullList.withSize(GardenBagMenu.BAG_SLOTS, ItemStack.EMPTY);
        for (int i = 0; i < GardenBagMenu.BAG_SLOTS; i++) {
            items.set(i, this.menu.slots.get(i).getItem());
        }
        return items;
    }

    // Short lines summarizing exactly what GardenBagItem#plant would configure right now, so throwing
    // items in feels like reading a cauldron rather than filling out a form.
    private List<String> previewLines(GardenBagContents contents) {
        long cap = contents.generations() == SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE
                ? Config.FLOWER_MAX_GENERATIONS.getAsInt()
                : contents.generations();
        String generations = cap < 0 ? "unlimited" : String.valueOf(cap);

        String decay = contents.noDecay()
                ? "none"
                : "halves at gen " + number(SpreadMath.halfGenerations(contents.decayStrength()));

        String lifetime = "~" + SpreadMath.resolveLifetimeAttempts(contents.lifetimeAttempts()) + " children each";

        int density = SpreadMath.resolveDensity(contents.densityPer16x16());
        int maxDistance = SpreadMath.resolveMaxDistance(contents.spreadDistance(), density);
        String distance = maxDistance
                + (contents.spreadDistance() < 0 ? " (auto)" : maxDistance < SpreadMath.minSpacing(density) ? " (too short!)" : "");

        String respects = contents.respectAllSpecies() ? "respected" : "ignored";
        String climbing = contents.climbing() ? "yes" : "no";

        String flowerBlock = !Config.FLOWER_BLOCK_CONVERSION.getAsBoolean()
                ? "disabled by config"
                : contents.mossBlocks() > 0
                        ? percent(SpreadMath.flowerBlockChance(contents.mossBlocks())) + " per plant"
                        : "off";

        return List.of(
                "Generations: " + generations,
                "Decay: " + decay,
                "Lifetime: " + lifetime,
                "Density: " + density + " per 16x16",
                "Max distance: " + distance,
                "Other flowers: " + respects,
                "Climbing: " + climbing,
                "Flower block: " + flowerBlock,
                "Pace: ~" + triesPerDay(Config.FLOWER_SPREAD_CHANCE.getAsDouble()) + " tries/day at start"
        );
    }

    // Reproduction attempts per day for a plant at generation 0 - assumes nothing but the raw random-tick sampling
    // maths: a block gets `randomTickSpeed` chances per game tick out of the 4096 positions in its 16x16x16 chunk
    // section, and there are 24000 game ticks per in-game day. Whether an attempt finds room is a separate matter.
    private String triesPerDay(double spreadChance) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return "?";
        }
        int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        return number(24000.0 * randomTickSpeed / 4096.0 * spreadChance);
    }

    private static String number(double value) {
        return value >= 10 ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String percent(double fraction) {
        return number(fraction * 100.0) + "%";
    }
}
