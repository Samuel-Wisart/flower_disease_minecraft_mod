package com.iridium.flowerdisease;

import java.util.function.Predicate;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

// The Garden Bag's configuration screen: one big "cauldron" inventory (27 slots, chest-sized) where every
// item - modifiers (Bone Meal/Sculk/Nether Star/Slime Ball/Feather/Fermented Spider Eye, see
// GardenBagContents) and species alike - gets thrown in together, no dedicated slot per role. Backed by
// the bag ItemStack's own CONTAINER component (same one vanilla's Bundle uses) instead of a block entity -
// the bag is the inventory. GardenBagContents reads whatever ends up in here into actual spread
// parameters; GardenBagScreen shows a live computed preview above the grid.
public class GardenBagMenu extends AbstractContainerMenu {
    static final int BAG_SLOTS = 27;
    private static final int GRID_COLUMNS = 9;
    // Leaves room above for 8 lines of preview text (see GardenBagScreen) - grows if more knobs are added.
    static final int GRID_TOP_Y = 104;

    private final ItemStack bagStack;
    private final InteractionHand hand;
    private final Player owner;

    public GardenBagMenu(int containerId, Inventory playerInventory, InteractionHand hand) {
        super(FlowerDisease.GARDEN_BAG_MENU.get(), containerId);
        this.owner = playerInventory.player;
        this.hand = hand;
        this.bagStack = this.owner.getItemInHand(hand);

        Container bag = new BagContainer(this.bagStack);

        for (int i = 0; i < BAG_SLOTS; i++) {
            int col = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            addSlot(new FilteredSlot(bag, i, 8 + col * 18, GRID_TOP_Y + row * 18, GardenBagMenu::isBagItem));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 172 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 230));
        }
    }

    // Accepts anything the bag actually knows what to do with - a modifier item or a species item -
    // rejecting anything else so the bag doesn't double as generic storage.
    private static boolean isBagItem(ItemStack stack) {
        return stack.is(GardenBagContents.GENERATIONS_ITEM)
                || stack.is(GardenBagContents.INFINITE_GENERATIONS_ITEM)
                || stack.is(GardenBagContents.SPEED_ITEM)
                || stack.is(GardenBagContents.DENSITY_ITEM)
                || stack.is(GardenBagContents.RANGE_ITEM)
                || stack.is(GardenBagContents.IGNORE_OTHERS_ITEM)
                || stack.is(GardenBagContents.CLIMBING_ITEM)
                || stack.is(GardenBagContents.FLOWER_BLOCK_ITEM)
                || FlowerDisease.bagOutcomeItems().containsKey(stack.getItem());
    }

    @Override
    public boolean stillValid(Player player) {
        return player == owner && player.getItemInHand(hand) == bagStack;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack moved = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasItem()) {
            ItemStack stackInSlot = slot.getItem();
            moved = stackInSlot.copy();
            if (index < BAG_SLOTS) {
                if (!this.moveItemStackTo(stackInSlot, BAG_SLOTS, this.slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!this.moveItemStackTo(stackInSlot, 0, BAG_SLOTS, false)) {
                return ItemStack.EMPTY;
            }

            if (stackInSlot.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }
        return moved;
    }

    // Loads its starting contents from the bag's CONTAINER component and writes any change straight
    // back into that same ItemStack - the bag itself is the source of truth, not this menu.
    private static final class BagContainer extends SimpleContainer {
        private final ItemStack bagStack;

        BagContainer(ItemStack bagStack) {
            super(BAG_SLOTS);
            this.bagStack = bagStack;

            NonNullList<ItemStack> items = NonNullList.withSize(BAG_SLOTS, ItemStack.EMPTY);
            bagStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(items);
            for (int i = 0; i < BAG_SLOTS; i++) {
                this.setItem(i, items.get(i));
            }
        }

        @Override
        public void setChanged() {
            super.setChanged();
            NonNullList<ItemStack> items = NonNullList.withSize(BAG_SLOTS, ItemStack.EMPTY);
            for (int i = 0; i < BAG_SLOTS; i++) {
                items.set(i, this.getItem(i));
            }
            bagStack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
        }
    }

    // Only accepts the one item type it's meant for - unlike a vanilla slot, which accepts anything.
    private static final class FilteredSlot extends Slot {
        private final Predicate<ItemStack> itemFilter;

        FilteredSlot(Container container, int index, int x, int y, Predicate<ItemStack> itemFilter) {
            super(container, index, x, y);
            this.itemFilter = itemFilter;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return itemFilter.test(stack);
        }
    }
}
