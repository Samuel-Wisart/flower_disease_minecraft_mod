package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

// Spreads via vanilla's random-tick sampler instead of a custom scheduler, so idle flowers cost nothing.
public class DiseasedFlowerBlock extends FlowerBlock {

    public DiseasedFlowerBlock(Holder<MobEffect> suspiciousStewEffect, float effectSeconds, BlockBehaviour.Properties properties) {
        super(suspiciousStewEffect, effectSeconds, properties);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Vanilla random ticks alone already fire ~15-20 times/day per block; this extra roll is what
        // actually controls the "many in-game days per field" pacing. Tune via Config.FLOWER_SPREAD_CHANCE.
        if (random.nextFloat() >= (float) Config.FLOWER_SPREAD_CHANCE.getAsDouble()) {
            return;
        }

        int maxNearby = Config.FLOWER_MAX_NEARBY.getAsInt();
        if (countNearbyFieldFlowers(level, pos, Config.FLOWER_DENSITY_RADIUS.getAsInt(), maxNearby) >= maxNearby) {
            level.setBlock(pos, Blocks.CORNFLOWER.defaultBlockState(), Block.UPDATE_CLIENTS);
            return;
        }

        BlockPos target = findSpreadTarget(level, pos, random);
        if (target != null) {
            level.setBlock(target, this.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        // No free spot this attempt is not the same as "crowded": just wait for the next random tick.
    }

    private int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int max) {
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
                    BlockState state = level.getBlockState(cursor);
                    if (state.is(this) || state.is(Blocks.CORNFLOWER)) {
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
        BlockState newState = this.defaultBlockState();

        for (int attempt = 0; attempt < Config.FLOWER_SPREAD_ATTEMPTS.getAsInt(); attempt++) {
            int dx = random.nextInt(maxDistance * 2 + 1) - maxDistance;
            int dz = random.nextInt(maxDistance * 2 + 1) - maxDistance;
            if (dx == 0 && dz == 0) {
                continue;
            }

            BlockPos candidate = followTerrain(level, origin.offset(dx, 0, dz), newState, verticalRange);
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    // Slopes/steps mean the target column often isn't level with the parent flower, so this checks
    // nearby heights too (closest to the parent's Y first) instead of only the exact same Y.
    @Nullable
    private BlockPos followTerrain(ServerLevel level, BlockPos column, BlockState newState, int verticalRange) {
        if (level.isEmptyBlock(column) && newState.canSurvive(level, column)) {
            return column;
        }

        for (int dy = 1; dy <= verticalRange; dy++) {
            BlockPos up = column.above(dy);
            if (level.isEmptyBlock(up) && newState.canSurvive(level, up)) {
                return up;
            }

            BlockPos down = column.below(dy);
            if (level.isEmptyBlock(down) && newState.canSurvive(level, down)) {
                return down;
            }
        }

        return null;
    }
}
