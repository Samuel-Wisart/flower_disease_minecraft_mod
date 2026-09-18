package com.iridium.flowerdisease;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Right-click in the air to open the config screen (can be reconfigured any time, no locking - that may
// come back later as an opt-in), right-click a block with it to plant one root Diseased Flower configured
// with whatever is currently in the bag - see GardenBagMenu for the slots and SpreadProfileBlockEntity for
// what gets configured. The bag isn't consumed either way, so it's reusable for repeated testing.
public class GardenBagItem extends Item {

    public GardenBagItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.usage"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.mix"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.generations"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.speed"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.infinite"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.density"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.range"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.ignore_others"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.climbing"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.flower_block"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.species"));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }

        BlockPos target = context.getClickedPos().relative(context.getClickedFace());
        Component failureReason = plant(level, target, stack);
        if (failureReason != null) {
            Player player = context.getPlayer();
            if (player != null) {
                player.displayClientMessage(failureReason, true);
            }
            return InteractionResult.FAIL;
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            player.openMenu(
                    new SimpleMenuProvider(
                            (windowId, inventory, p) -> new GardenBagMenu(windowId, inventory, hand),
                            Component.translatable("item.flowerdisease.garden_bag")
                    ),
                    buf -> buf.writeEnum(hand)
            );
        }
        return InteractionResultHolder.success(stack);
    }

    // Returns null on success, or the reason it failed (shown to the player) otherwise - planting used
    // to fail completely silently, which made it impossible to tell "no species configured" apart from
    // "bad spot" apart from "nothing actually got saved into the bag".
    @Nullable
    private Component plant(ServerLevel level, BlockPos target, ItemStack bagStack) {
        GardenBagContents contents = GardenBagContents.read(readSlots(bagStack));

        // The root must be an actual growing plant, so only entries with a spreadable Diseased
        // counterpart are eligible here - a bag with nothing but modifier items has nothing plantable,
        // same as an empty bag. Drawn from the whole pool regardless of shape, same as every child/settle
        // draw - see DiseasedPlantLogic.
        List<SettleTable.Option> spreadable = DiseasedPlantLogic.spreadableOptions(SettleTable.parse(contents.speciesWeights()));
        SettleTable.Option chosen = SettleTable.pickWeighted(spreadable, level.getRandom());
        if (chosen == null) {
            return Component.translatable("item.flowerdisease.garden_bag.error.no_species");
        }

        Block block = FlowerDisease.diseasedByFallback().get(chosen.block()).get();
        boolean tall = block instanceof DoublePlantBlock;
        BlockState lowerState = tall ? block.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER) : block.defaultBlockState();

        if (!level.isEmptyBlock(target) || (tall && !level.isEmptyBlock(target.above()))) {
            return Component.translatable("item.flowerdisease.garden_bag.error.occupied");
        }
        if (!lowerState.canSurvive(level, target)) {
            return Component.translatable("item.flowerdisease.garden_bag.error.bad_ground");
        }

        if (tall) {
            DoublePlantBlock.placeAt(level, block.defaultBlockState(), target, Block.UPDATE_ALL);
        } else {
            level.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL);
        }

        if (level.getBlockEntity(target) instanceof SpreadProfileBlockEntity profile) {
            profile.configure(contents);
        }
        return null;
    }

    private static NonNullList<ItemStack> readSlots(ItemStack bagStack) {
        NonNullList<ItemStack> slots = NonNullList.withSize(GardenBagMenu.BAG_SLOTS, ItemStack.EMPTY);
        bagStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }
}
