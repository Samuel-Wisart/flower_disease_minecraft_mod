package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.ModConfigSpec;

// Two-block counterpart to DiseasedFlowerBlock (see that class for the general approach). Only the
// lower half acts on a random tick - the upper half is randomly ticked too since it's the same Block,
// and acting from both would double the effective spread/settle rate. Kept as its own parallel
// implementation (not shared via DiseasedFlowerLogic) since a valid spread target genuinely differs:
// it needs a free cell above too, and settling has to also clear the old upper half.
public class DiseasedTallFlowerBlock extends TallFlowerBlock implements EntityBlock {

    private final Block fallbackBlock;
    private final ModConfigSpec.ConfigValue<List<? extends String>> settleWeights;

    public DiseasedTallFlowerBlock(
            Block fallbackBlock,
            ModConfigSpec.ConfigValue<List<? extends String>> settleWeights,
            BlockBehaviour.Properties properties
    ) {
        super(properties);
        this.fallbackBlock = fallbackBlock;
        this.settleWeights = settleWeights;
        this.registerDefaultState(this.stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(SettleTable.GENERATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SettleTable.GENERATION);
    }

    // A hand-planted flower always starts with a full budget of generations (see Config.FLOWER_MAX_GENERATIONS).
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(SettleTable.GENERATION, Config.FLOWER_MAX_GENERATIONS.getAsInt());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpreadProfileBlockEntity(pos, state);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) {
            return;
        }

        SpreadProfileBlockEntity profile = level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity p ? p : null;

        double spreadChance = profile != null && profile.spreadChanceOverride() >= 0
                ? profile.spreadChanceOverride()
                : Config.FLOWER_SPREAD_CHANCE.getAsDouble();
        if (random.nextFloat() >= (float) spreadChance) {
            return;
        }

        List<SettleTable.Option> settleOptions = SettleTable.parse(settleWeights.get());
        List<SettleTable.Option> speciesOptions = compatibleSpeciesOptions(profile);

        long generationsLeft = profile != null && profile.generationsRemaining() != SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE
                ? profile.generationsRemaining()
                : state.getValue(SettleTable.GENERATION);

        int densityRadius = Config.FLOWER_DENSITY_RADIUS.getAsInt();
        int maxNearby = profile != null && profile.densityTargetPer16x16() >= 0
                ? SettleTable.densityTargetToMaxNearby(profile.densityTargetPer16x16(), densityRadius)
                : Config.FLOWER_MAX_NEARBY.getAsInt();

        List<SettleTable.Option> familyOptions = new ArrayList<>(settleOptions.size() + speciesOptions.size());
        familyOptions.addAll(settleOptions);
        familyOptions.addAll(speciesOptions);
        boolean tooCrowded = countNearbyFieldFlowers(level, pos, densityRadius, maxNearby, familyOptions) >= maxNearby;

        Block species = pickSpecies(speciesOptions, random);
        BlockPos target = (tooCrowded || generationsLeft == 0) ? null : findSpreadTarget(level, pos, random, species, profile);

        if (target != null) {
            long childGenerations = generationsLeft < 0 ? generationsLeft : generationsLeft - 1;
            if (childGenerations == 0) {
                // No budget left for the child to spread itself, so it settles the instant it's created
                // instead of existing as an active (randomly-ticking) Diseased Flower even briefly.
                SettleTable.Option picked = SettleTable.pickWeighted(settleOptions, random);
                SettleTable.place(level, target, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
            } else {
                int blockstateGenerations = (int) Math.max(0, Math.min(64, childGenerations < 0 ? 64 : childGenerations));
                DoublePlantBlock.placeAt(level, species.defaultBlockState().setValue(SettleTable.GENERATION, blockstateGenerations), target, SettleTable.PLACEMENT_FLAGS);
                if (profile != null && profile.hasOverride() && level.getBlockEntity(target) instanceof SpreadProfileBlockEntity childProfile) {
                    childProfile.copyFrom(profile, childGenerations);
                }
            }
        } else {
            SettleTable.Option picked = SettleTable.pickWeighted(settleOptions, random);
            // The old upper half won't be overwritten unless the outcome is itself a "full" two-block
            // placement, so clear it first - otherwise it would be left floating with nothing below it.
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
            SettleTable.place(level, pos, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
        }
    }

    // Only two-block species are valid here - a bag's species ratio might also list single-block
    // flowers, meant for DiseasedFlowerBlock/DiseasedWitherRoseBlock's own spreading instead.
    private List<SettleTable.Option> compatibleSpeciesOptions(@Nullable SpreadProfileBlockEntity profile) {
        if (profile == null || profile.speciesWeights().isEmpty()) {
            return List.of();
        }

        List<SettleTable.Option> compatible = new ArrayList<>();
        for (SettleTable.Option option : SettleTable.parse(profile.speciesWeights())) {
            if (option.block() instanceof DoublePlantBlock) {
                compatible.add(option);
            }
        }
        return compatible;
    }

    private Block pickSpecies(List<SettleTable.Option> speciesOptions, RandomSource random) {
        if (speciesOptions.isEmpty()) {
            return this;
        }

        SettleTable.Option picked = SettleTable.pickWeighted(speciesOptions, random);
        return picked != null ? picked.block() : this;
    }

    private int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int max, List<SettleTable.Option> familyOptions) {
        int count = 0;
        int verticalRange = Config.FLOWER_SPREAD_VERTICAL_RANGE.getAsInt();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -verticalRange; dy <= verticalRange; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }

                    cursor.setWithOffset(center, dx, dy, dz);
                    if (SettleTable.isSameSpecies(level.getBlockState(cursor), this, fallbackBlock, familyOptions)) {
                        count++;
                        if (count >= max) {
                            return count;
                        }
                    }
                }
            }
        }
        return count;
    }

    @Nullable
    private BlockPos findSpreadTarget(ServerLevel level, BlockPos origin, RandomSource random, Block species, @Nullable SpreadProfileBlockEntity profile) {
        int maxDistance = profile != null && profile.spreadDistanceOverride() >= 0
                ? profile.spreadDistanceOverride()
                : Config.FLOWER_SPREAD_DISTANCE.getAsInt();
        int verticalRange = Config.FLOWER_SPREAD_VERTICAL_RANGE.getAsInt();
        BlockState newLowerState = species.defaultBlockState();

        for (int attempt = 0; attempt < Config.FLOWER_SPREAD_ATTEMPTS.getAsInt(); attempt++) {
            int dx = random.nextInt(maxDistance * 2 + 1) - maxDistance;
            int dz = random.nextInt(maxDistance * 2 + 1) - maxDistance;
            if (dx == 0 && dz == 0) {
                continue;
            }

            BlockPos candidate = followTerrain(level, origin.offset(dx, 0, dz), newLowerState, verticalRange);
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    // Slopes/steps mean the target column often isn't level with the parent flower, so this checks
    // nearby heights too (closest to the parent's Y first) instead of only the exact same Y. Unlike the
    // single-block flower, a valid spot also needs its ABOVE cell free for the second half.
    @Nullable
    private BlockPos followTerrain(ServerLevel level, BlockPos column, BlockState newLowerState, int verticalRange) {
        if (isValidSpot(level, column, newLowerState)) {
            return column;
        }

        for (int dy = 1; dy <= verticalRange; dy++) {
            BlockPos up = column.above(dy);
            if (isValidSpot(level, up, newLowerState)) {
                return up;
            }

            BlockPos down = column.below(dy);
            if (isValidSpot(level, down, newLowerState)) {
                return down;
            }
        }

        return null;
    }

    private boolean isValidSpot(ServerLevel level, BlockPos pos, BlockState newLowerState) {
        return level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above()) && newLowerState.canSurvive(level, pos);
    }
}
