package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
import net.neoforged.neoforge.registries.DeferredBlock;

// Fill the bag's slots (right-click in the air to open the config screen), then lock it for good, then
// right-click a block with it to plant one root Diseased Flower configured with everything in the bag -
// see GardenBagMenu for the slots and SpreadProfileBlockEntity for what gets configured.
public class GardenBagItem extends Item {

    public GardenBagItem(Properties properties) {
        super(properties);
    }

    static boolean isLocked(ItemStack stack) {
        return stack.getOrDefault(FlowerDisease.GARDEN_BAG_LOCKED.get(), false);
    }

    static void setLocked(ItemStack stack, boolean locked) {
        stack.set(FlowerDisease.GARDEN_BAG_LOCKED.get(), locked);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(isLocked(stack) ? "item.flowerdisease.garden_bag.tooltip.locked" : "item.flowerdisease.garden_bag.tooltip.unlocked"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.generations"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.speed"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.infinite"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.density"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.range"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.territorial"));
        tooltip.add(Component.translatable("item.flowerdisease.garden_bag.tooltip.species"));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!isLocked(stack)) {
            // Not configured yet - fall through to use(), which opens the config screen instead.
            return InteractionResult.PASS;
        }

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
        if (isLocked(stack)) {
            return InteractionResultHolder.pass(stack);
        }

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
        NonNullList<ItemStack> slots = readSlots(bagStack);
        List<SettleTable.Option> species = SettleTable.parse(speciesWeightStrings(slots));
        SettleTable.Option chosen = species.isEmpty() ? null : SettleTable.pickWeighted(species, level.getRandom());
        if (chosen == null) {
            return Component.translatable("item.flowerdisease.garden_bag.error.no_species");
        }

        Block block = chosen.block();
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
            profile.configure(
                    generations(slots),
                    spreadChance(slots),
                    spreadDistance(slots),
                    densityPer16x16(slots),
                    speciesWeightStrings(slots),
                    respectAllSpecies(slots)
            );
        }
        return null;
    }

    private static NonNullList<ItemStack> readSlots(ItemStack bagStack) {
        NonNullList<ItemStack> slots = NonNullList.withSize(GardenBagMenu.BAG_SLOTS, ItemStack.EMPTY);
        bagStack.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }

    // Nether Star (any amount) means infinite; otherwise Bone Meal's count is the generation budget,
    // uncapped (the player can always plant another bag for more reach - see PLANNING.md).
    private static long generations(NonNullList<ItemStack> slots) {
        if (!slots.get(GardenBagMenu.INFINITE_SLOT).isEmpty()) {
            return SpreadProfileBlockEntity.INFINITE_GENERATIONS;
        }
        ItemStack boneMeal = slots.get(GardenBagMenu.GENERATIONS_SLOT);
        return boneMeal.isEmpty() ? SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE : boneMeal.getCount();
    }

    private static double spreadChance(NonNullList<ItemStack> slots) {
        ItemStack sculk = slots.get(GardenBagMenu.SPEED_SLOT);
        return sculk.isEmpty() ? -1 : Mth.clamp(sculk.getCount() / 64.0, 0.0, 1.0);
    }

    private static int spreadDistance(NonNullList<ItemStack> slots) {
        ItemStack feather = slots.get(GardenBagMenu.RANGE_SLOT);
        return feather.isEmpty() ? -1 : Math.min(feather.getCount(), 32);
    }

    // Any fence (any wood type, or nether brick) turns on "territorial" density counting - see
    // SettleTable.isAnyPlant.
    private static boolean respectAllSpecies(NonNullList<ItemStack> slots) {
        return !slots.get(GardenBagMenu.TERRITORIAL_SLOT).isEmpty();
    }

    private static int densityPer16x16(NonNullList<ItemStack> slots) {
        ItemStack slimeBall = slots.get(GardenBagMenu.DENSITY_SLOT);
        return slimeBall.isEmpty() ? -1 : slimeBall.getCount();
    }

    private static List<String> speciesWeightStrings(NonNullList<ItemStack> slots) {
        List<String> result = new ArrayList<>();
        for (int i = GardenBagMenu.SPECIES_SLOTS_START; i < GardenBagMenu.BAG_SLOTS; i++) {
            ItemStack stack = slots.get(i);
            if (stack.isEmpty()) {
                continue;
            }

            DeferredBlock<? extends Block> species = FlowerDisease.SPECIES_SEED_ITEMS.get(stack.getItem());
            if (species == null) {
                continue;
            }

            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(species.get());
            result.add(id + " " + stack.getCount());
        }
        return result;
    }
}
