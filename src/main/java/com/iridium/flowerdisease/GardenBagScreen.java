package com.iridium.flowerdisease;

import java.util.List;

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
        this.imageHeight = 256;
        this.inventoryLabelY = 164;
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
        for (String line : previewLines(GardenBagContents.read(bagSlotItems()))) {
            guiGraphics.drawString(this.font, line, 8, y, INFO_COLOR, false);
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
        String generations = contents.generations() == SpreadProfileBlockEntity.INFINITE_GENERATIONS
                ? "infinite"
                : contents.generations() == SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE
                        ? Config.FLOWER_MAX_GENERATIONS.getAsInt() + " (default)"
                        : String.valueOf(contents.generations());

        boolean speedOverridden = contents.spreadChance() >= 0;
        double spreadChance = speedOverridden ? contents.spreadChance() : Config.FLOWER_SPREAD_CHANCE.getAsDouble();
        String speed = (speedOverridden ? "" : "~") + Math.round(spreadChance * 1000) / 10.0 + "%";

        String range = contents.spreadDistance() >= 0
                ? contents.spreadDistance() + " blocks"
                : "~" + Config.FLOWER_SPREAD_DISTANCE.getAsInt() + " (default)";

        String density = contents.densityPer16x16() >= 0 ? contents.densityPer16x16() + " / chunk" : "default";

        String respects = contents.respectAllSpecies() ? "yes" : "no - ignores others";
        String climbing = contents.climbing() ? "yes - logs/leaves/moss" : "no";
        String flowerBlocks = contents.spawnsFlowerBlocks() ? "yes - slowly, beneath itself" : "no";

        return List.of(
                "Generations: " + generations,
                "Speed: " + speed,
                "Range: " + range,
                "Density: " + density,
                "Respects other flowers: " + respects,
                "Climbs non-plantable ground: " + climbing,
                "Creates flower blocks: " + flowerBlocks,
                "Est. reproduction: ~" + estimatePerDay(spreadChance) + " new flowers/day"
        );
    }

    // Simplified estimate the player asked for: assumes this is the ONLY flower in its chunk (no
    // crowding, always finds room to spread into) - just the raw random-tick-sampling math. A block has
    // `randomTickSpeed` chances per game tick out of the 4096 positions in its 16x16x16 chunk section;
    // there are 24000 game ticks per in-game day.
    private String estimatePerDay(double spreadChance) {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return "?";
        }
        int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
        double ticksPerDay = 24000.0 * randomTickSpeed / 4096.0;
        double perDay = ticksPerDay * spreadChance;
        return perDay < 10 ? String.format("%.1f", perDay) : String.valueOf(Math.round(perDay));
    }
}
