package com.iridium.flowerdisease;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.MultifaceBlock;
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
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.decay"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.no_decay"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.lifetime"));
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
        Component failureReason = plant(level, target, stack, context.getClickedFace());
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
    // "bad spot" apart from "nothing actually got saved into the bag". Package-visible and static so the game tests
    // can plant exactly the way a right-click does.
    @Nullable
    static Component plant(ServerLevel level, BlockPos target, ItemStack bagStack, Direction clickedFace) {
        GardenBagContents contents = GardenBagContents.read(readSlots(bagStack));

        // The root must be an actual growing plant, so only entries with a spreadable Diseased
        // counterpart are eligible here - a bag with nothing but modifier items has nothing plantable,
        // same as an empty bag. Drawn from the whole pool regardless of shape, same as every child/settle
        // draw - see DiseasedPlantLogic.
        List<SettleTable.Option> spreadable = DiseasedPlantLogic.spreadableOptions(SettleTable.parse(contents.speciesWeights()));
        // Where the bag is used, the species fields (see Variation) may favour one of the pool over the others.
        SettleTable.Option chosen = SettleTable.pickWeighted(spreadable, Variation.weights(level, target, spreadable), level.getRandom());
        if (chosen == null) {
            return Component.translatable("item.flowerdisease.garden_bag.error.no_species");
        }

        Block block = FlowerDisease.diseasedByFallback().get(chosen.block()).get();
        DiseasedPlantLogic.Shape shape = DiseasedPlantLogic.shapeOf(block);

        // What actually gets placed/checked at `target`, one per shape - a creeping species has no
        // upright/default form at all (its defaultBlockState() has every face off, which never
        // canSurvive()s - see MultifaceBlock#canSurvive), so it needs the face it's clinging to set just
        // like a spreading child does (DiseasedPlantLogic#placeChild). That face is simply the opposite of
        // whatever face the player clicked: clicking the TOP of a block and placing in the air cell above
        // it means the new block grabs onto the block BELOW itself, i.e. Direction.DOWN.
        BlockState placementState = switch (shape) {
            case TALL -> block.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
            case CREEPING -> block.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(clickedFace.getOpposite()), true);
            case SINGLE -> block.defaultBlockState();
        };

        if (!level.isEmptyBlock(target) || (shape == DiseasedPlantLogic.Shape.TALL && !level.isEmptyBlock(target.above()))) {
            return Component.translatable("item.flowerdisease.garden_bag.error.occupied");
        }
        if (!placementState.canSurvive(level, target)) {
            return Component.translatable("item.flowerdisease.garden_bag.error.bad_ground");
        }

        // Same placement flags every other piece of this mod's own world-editing uses (DiseasedPlantLogic/
        // FlowerBlockLogic) - UPDATE_ALL also fires UPDATE_NEIGHBORS, which the rest of the mod deliberately
        // avoids (see SettleTable.PLACEMENT_FLAGS) since an immediate neighbor update while placing a
        // two-part plant can make the engine think a half-built pair looks invalid and destroy it, drop and
        // all. This is a fresh placement rather than a settle/spread rewrite, so that exact failure mode is
        // unlikely here, but there's no reason to be the one place in the mod that risks it.
        if (shape == DiseasedPlantLogic.Shape.TALL) {
            DoublePlantBlock.placeAt(level, block.defaultBlockState(), target, SettleTable.PLACEMENT_FLAGS);
        } else {
            level.setBlock(target, placementState, SettleTable.PLACEMENT_FLAGS);
        }

        // Every planting is a garden of its own: the plant remembers just the garden's id (see GardenRegistry).
        if (level.getBlockEntity(target) instanceof SpreadProfileBlockEntity root) {
            root.startGarden(level, contents, target);
            if (root instanceof CreeperBlockEntity creeper) {
                creeper.setEnergy(PatchGrowth.rollEnergy(level.getRandom()));
            }
        }

        // The garden starts with its second generation already around the root instead of one lonely flower (or, with
        // Bone Meal x1, as exactly that one flower).
        DiseasedPlantLogic.onPlanted(level, target, level.getRandom());
        return null;
    }

    private static NonNullList<ItemStack> readSlots(ItemStack bagStack) {
        NonNullList<ItemStack> slots = NonNullList.withSize(GardenBagMenu.BAG_SLOTS, ItemStack.EMPTY);
        bagStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }
}
