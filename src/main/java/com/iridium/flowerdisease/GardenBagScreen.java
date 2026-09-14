package com.iridium.flowerdisease;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

// No custom texture (none of us can draw pixel art for this mod) - the background/slots are drawn as
// plain rectangles instead of a chest-style image. Purely functional; the item's own tooltip explains
// what each slot does in full, this screen only shows short reminders. Slot layout here must match
// GardenBagMenu's addSlot coordinates exactly.
public class GardenBagScreen extends AbstractContainerScreen<GardenBagMenu> {
    private static final int SLOT_BORDER = 0xFF000000;
    private static final int SLOT_FILL = 0x77000000;
    private static final int PANEL_FILL = 0xC0101010;
    private static final int PANEL_BORDER = 0xFF3F3F3F;
    private static final int LABEL_COLOR = 0xFFE0E0E0;
    private static final int LOCKED_COLOR = 0xFFFF5555;

    public GardenBagScreen(GardenBagMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 176;
        this.imageHeight = 238;
        this.inventoryLabelY = 144;
    }

    @Override
    protected void init() {
        super.init();
        int buttonWidth = 90;
        this.addRenderableWidget(Button.builder(lockButtonLabel(), button -> {
            if (this.menu.clickMenuButton(this.minecraft.player, GardenBagMenu.LOCK_BUTTON_ID)) {
                this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, GardenBagMenu.LOCK_BUTTON_ID);
                this.rebuildWidgets();
            }
        }).bounds(this.leftPos + (imageWidth - buttonWidth) / 2, this.topPos + 118, buttonWidth, 16).build());
    }

    private Component lockButtonLabel() {
        return this.menu.isLocked()
                ? Component.translatable("gui.flowerdisease.bag_locked")
                : Component.translatable("gui.flowerdisease.lock_bag");
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        guiGraphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, PANEL_FILL);
        guiGraphics.renderOutline(leftPos, topPos, imageWidth, imageHeight, PANEL_BORDER);

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

        drawCentered(guiGraphics, "Gens", 8, 40);
        drawCentered(guiGraphics, "Speed", 30, 40);
        drawCentered(guiGraphics, "Inf.", 52, 40);
        drawCentered(guiGraphics, "Density", 74, 40);
        drawCentered(guiGraphics, "Range", 96, 40);
        drawCentered(guiGraphics, "Fence", 118, 40);
        drawCentered(guiGraphics, "Species", 62, 49);

        if (this.menu.isLocked()) {
            guiGraphics.drawString(this.font, "Locked", 8, 40 - 9, LOCKED_COLOR, false);
        }

        guiGraphics.drawString(this.font, this.playerInventoryTitle, this.inventoryLabelX, this.inventoryLabelY, LABEL_COLOR, false);
    }

    // Centers a short label under/over an 18px-wide slot column, tolerating a little overflow into the
    // gap on either side since there's no room otherwise at this scale.
    private void drawCentered(GuiGraphics guiGraphics, String text, int slotX, int y) {
        int width = this.font.width(text);
        int x = slotX + 9 - width / 2;
        guiGraphics.drawString(this.font, text, x, y, LABEL_COLOR, false);
    }
}
