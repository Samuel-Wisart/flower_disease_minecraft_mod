package com.iridium.flowerdisease;

import java.util.function.Predicate;

import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;

// The Garden Bag's configuration screen: 5 single-purpose slots (bone meal/sculk/nether star/slime
// ball/feather) plus a 3x3 area for flower species, backed by the bag ItemStack's own CONTAINER
// component (the same one vanilla's Bundle uses) instead of a block entity - the bag is the inventory.
public class GardenBagMenu extends AbstractContainerMenu {
    static final int GENERATIONS_SLOT = 0;
    static final int SPEED_SLOT = 1;
    static final int INFINITE_SLOT = 2;
    static final int DENSITY_SLOT = 3;
    static final int RANGE_SLOT = 4;
    static final int TERRITORIAL_SLOT = 5;
    static final int SPECIES_SLOTS_START = 6;
    static final int SPECIES_SLOT_COUNT = 9;
    static final int BAG_SLOTS = SPECIES_SLOTS_START + SPECIES_SLOT_COUNT;
    static final int LOCK_BUTTON_ID = 0;

    private final ItemStack bagStack;
    private final InteractionHand hand;
    private final Player owner;

    public GardenBagMenu(int containerId, Inventory playerInventory, InteractionHand hand) {
        super(FlowerDisease.GARDEN_BAG_MENU.get(), containerId);
        this.owner = playerInventory.player;
        this.hand = hand;
        this.bagStack = this.owner.getItemInHand(hand);

        Container bag = new BagContainer(this.bagStack);

        addSlot(new RestrictedSlot(bag, this.bagStack, GENERATIONS_SLOT, 8, 20, stack -> stack.is(Items.BONE_MEAL)));
        addSlot(new RestrictedSlot(bag, this.bagStack, SPEED_SLOT, 30, 20, stack -> stack.is(Items.SCULK)));
        addSlot(new RestrictedSlot(bag, this.bagStack, INFINITE_SLOT, 52, 20, stack -> stack.is(Items.NETHER_STAR)));
        addSlot(new RestrictedSlot(bag, this.bagStack, DENSITY_SLOT, 74, 20, stack -> stack.is(Items.SLIME_BALL)));
        addSlot(new RestrictedSlot(bag, this.bagStack, RANGE_SLOT, 96, 20, stack -> stack.is(Items.FEATHER)));
        addSlot(new RestrictedSlot(bag, this.bagStack, TERRITORIAL_SLOT, 118, 20, stack -> stack.is(ItemTags.FENCES)));

        for (int i = 0; i < SPECIES_SLOT_COUNT; i++) {
            int col = i % 3;
            int row = i / 3;
            addSlot(new RestrictedSlot(
                    bag, this.bagStack, SPECIES_SLOTS_START + i, 62 + col * 18, 60 + row * 18,
                    stack -> FlowerDisease.SPECIES_SEED_ITEMS.containsKey(stack.getItem())
            ));
        }

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 154 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 212));
        }
    }

    boolean isLocked() {
        return GardenBagItem.isLocked(bagStack);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id == LOCK_BUTTON_ID && !isLocked()) {
            GardenBagItem.setLocked(bagStack, true);
            return true;
        }
        return super.clickMenuButton(player, id);
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

    // Both placing into and taking out of a bag slot are refused once the bag is locked - "isLocked" is
    // read live from the bag stack (not captured once), so the button click takes effect immediately.
    private static final class RestrictedSlot extends Slot {
        private final ItemStack bagStack;
        private final Predicate<ItemStack> itemFilter;

        RestrictedSlot(Container container, ItemStack bagStack, int index, int x, int y, Predicate<ItemStack> itemFilter) {
            super(container, index, x, y);
            this.bagStack = bagStack;
            this.itemFilter = itemFilter;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return !GardenBagItem.isLocked(bagStack) && itemFilter.test(stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return !GardenBagItem.isLocked(bagStack);
        }
    }
}
