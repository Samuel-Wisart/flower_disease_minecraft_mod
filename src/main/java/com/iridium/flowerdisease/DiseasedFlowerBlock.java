package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;

// Spreads via vanilla's random-tick sampler instead of a custom scheduler, so idle flowers cost nothing.
// One class is shared by every diseased flower type; what it settles into is entirely config-driven
// (see Config.java), which is what lets each flower species have its own weighted outcome table.
public class DiseasedFlowerBlock extends FlowerBlock {

    private final Block fallbackBlock;
    private final ModConfigSpec.ConfigValue<List<? extends String>> settleWeights;

    public DiseasedFlowerBlock(
            Holder<MobEffect> suspiciousStewEffect,
            float effectSeconds,
            Block fallbackBlock,
            ModConfigSpec.ConfigValue<List<? extends String>> settleWeights,
            BlockBehaviour.Properties properties
    ) {
        super(suspiciousStewEffect, effectSeconds, properties);
        this.fallbackBlock = fallbackBlock;
        this.settleWeights = settleWeights;
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // Vanilla random ticks alone already fire ~15-20 times/day per block; this extra roll is what
        // actually controls the "many in-game days per field" pacing. Tune via Config.FLOWER_SPREAD_CHANCE.
        if (random.nextFloat() >= (float) Config.FLOWER_SPREAD_CHANCE.getAsDouble()) {
            return;
        }

        List<SettleOption> options = parseSettleTable(settleWeights.get());

        int maxNearby = Config.FLOWER_MAX_NEARBY.getAsInt();
        boolean tooCrowded = countNearbyFieldFlowers(level, pos, Config.FLOWER_DENSITY_RADIUS.getAsInt(), maxNearby, options) >= maxNearby;
        BlockPos target = tooCrowded ? null : findSpreadTarget(level, pos, random);

        if (target != null) {
            level.setBlock(target, this.defaultBlockState(), Block.UPDATE_CLIENTS);
        } else {
            // Either crowded or structurally stuck (e.g. a steep cave with no reachable ground within
            // spreadVerticalRange): either way it can't reproduce here, so it settles for good instead
            // of retrying forever on terrain that will never change.
            Block result = pickWeighted(options, random);
            level.setBlock(pos, (result != null ? result : fallbackBlock).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    private int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int max, List<SettleOption> options) {
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
                    if (isSameSpecies(level.getBlockState(cursor), options)) {
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

    // "Same species" = this diseased block itself, its configured fallback, or any of its settle outcomes.
    // Different diseased flower types are intentionally independent, so a mixed garden doesn't self-limit
    // against other species' density.
    private boolean isSameSpecies(BlockState state, List<SettleOption> options) {
        if (state.is(this) || state.is(fallbackBlock)) {
            return true;
        }
        for (SettleOption option : options) {
            if (state.is(option.block())) {
                return true;
            }
        }
        return false;
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

    private record SettleOption(Block block, int weight) {
    }

    // Entries look like "minecraft:rose_bush 30" (validated in Config, but re-checked here defensively
    // since the raw list is user-editable text).
    private static List<SettleOption> parseSettleTable(List<? extends String> entries) {
        List<SettleOption> options = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length != 2) {
                continue;
            }

            try {
                ResourceLocation id = ResourceLocation.parse(parts[0]);
                if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                    continue;
                }

                Block block = BuiltInRegistries.BLOCK.get(id);
                int weight = Integer.parseInt(parts[1]);
                if (block != Blocks.AIR && weight > 0) {
                    options.add(new SettleOption(block, weight));
                }
            } catch (Exception ignored) {
                // Malformed entry; skip it rather than crash the server on a typo in the config.
            }
        }
        return options;
    }

    @Nullable
    private static Block pickWeighted(List<SettleOption> options, RandomSource random) {
        int total = 0;
        for (SettleOption option : options) {
            total += option.weight();
        }
        if (total <= 0) {
            return null;
        }

        int roll = random.nextInt(total);
        for (SettleOption option : options) {
            roll -= option.weight();
            if (roll < 0) {
                return option.block();
            }
        }
        return null;
    }
}
