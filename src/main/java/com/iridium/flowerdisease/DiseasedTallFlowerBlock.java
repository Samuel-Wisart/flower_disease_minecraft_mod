package com.iridium.flowerdisease;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.ModConfigSpec;

// Two-block counterpart to DiseasedFlowerBlock (see that class for the general approach). Only the
// lower half acts on a random tick - the upper half is randomly ticked too since it's the same Block,
// and acting from both would double the effective spread/settle rate.
public class DiseasedTallFlowerBlock extends TallFlowerBlock {

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
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (state.getValue(HALF) != DoubleBlockHalf.LOWER) {
            return;
        }

        if (random.nextFloat() >= (float) Config.FLOWER_SPREAD_CHANCE.getAsDouble()) {
            return;
        }

        List<SettleTable.Option> options = SettleTable.parse(settleWeights.get());

        int maxNearby = Config.FLOWER_MAX_NEARBY.getAsInt();
        boolean tooCrowded = countNearbyFieldFlowers(level, pos, Config.FLOWER_DENSITY_RADIUS.getAsInt(), maxNearby, options) >= maxNearby;
        BlockPos target = tooCrowded ? null : findSpreadTarget(level, pos, random);

        if (target != null) {
            DoublePlantBlock.placeAt(level, this.defaultBlockState(), target, SettleTable.PLACEMENT_FLAGS);
        } else {
            SettleTable.Option picked = SettleTable.pickWeighted(options, random);
            // The old upper half won't be overwritten unless the outcome is itself a "full" two-block
            // placement, so clear it first - otherwise it would be left floating with nothing below it.
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
            SettleTable.place(level, pos, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
        }
    }

    private int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int max, List<SettleTable.Option> options) {
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
                    if (SettleTable.isSameSpecies(level.getBlockState(cursor), this, fallbackBlock, options)) {
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
    private BlockPos findSpreadTarget(ServerLevel level, BlockPos origin, RandomSource random) {
        int maxDistance = Config.FLOWER_SPREAD_DISTANCE.getAsInt();
        int verticalRange = Config.FLOWER_SPREAD_VERTICAL_RANGE.getAsInt();
        BlockState newLowerState = this.defaultBlockState();

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
