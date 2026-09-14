package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.ModConfigSpec;

// Shared spread/settle logic for single-block Diseased Flowers (DiseasedFlowerBlock and
// DiseasedWitherRoseBlock extend different vanilla base classes, so they can't share a common
// superclass, but the actual spreading behavior is identical). The two-block counterpart
// (DiseasedTallFlowerBlock) keeps its own parallel implementation since a valid target for it
// genuinely differs (needs a free cell above too).
final class DiseasedFlowerLogic {

    private DiseasedFlowerLogic() {
    }

    static void randomTick(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Block self,
            Block fallbackBlock,
            ModConfigSpec.ConfigValue<List<? extends String>> settleWeights
    ) {
        SpreadProfileBlockEntity profile = profileAt(level, pos);

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
        boolean tooCrowded = countNearbyFieldFlowers(level, pos, densityRadius, maxNearby, self, fallbackBlock, familyOptions) >= maxNearby;

        Block species = pickSpecies(self, speciesOptions, random);
        BlockPos target = (tooCrowded || generationsLeft == 0) ? null : findSpreadTarget(level, pos, random, species, profile);

        if (target != null) {
            long childGenerations = generationsLeft < 0 ? generationsLeft : generationsLeft - 1;
            if (childGenerations == 0) {
                // No budget left for the child to spread itself, so it settles the instant it's
                // created instead of existing as an active Diseased Flower even briefly.
                SettleTable.Option picked = SettleTable.pickWeighted(settleOptions, random);
                SettleTable.place(level, target, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
            } else {
                int blockstateGenerations = (int) Math.max(0, Math.min(64, childGenerations < 0 ? 64 : childGenerations));
                level.setBlock(target, species.defaultBlockState().setValue(SettleTable.GENERATION, blockstateGenerations), SettleTable.PLACEMENT_FLAGS);
                if (profile != null && profile.hasOverride() && level.getBlockEntity(target) instanceof SpreadProfileBlockEntity childProfile) {
                    childProfile.copyFrom(profile, childGenerations);
                }
            }
        } else {
            // Either crowded, out of generations, or structurally stuck (e.g. a steep cave with no
            // reachable ground within spreadVerticalRange): either way it can't reproduce here, so it
            // settles for good instead of retrying forever on terrain that will never change.
            SettleTable.Option picked = SettleTable.pickWeighted(settleOptions, random);
            SettleTable.place(level, pos, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
        }
    }

    @Nullable
    private static SpreadProfileBlockEntity profileAt(LevelReader level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity profile ? profile : null;
    }

    // Only single-block species are valid here (a bag's species ratio might list a two-block plant too,
    // meant for DiseasedTallFlowerBlock's own spreading) - anything else is silently skipped.
    private static List<SettleTable.Option> compatibleSpeciesOptions(@Nullable SpreadProfileBlockEntity profile) {
        if (profile == null || profile.speciesWeights().isEmpty()) {
            return List.of();
        }

        List<SettleTable.Option> compatible = new ArrayList<>();
        for (SettleTable.Option option : SettleTable.parse(profile.speciesWeights())) {
            if (!(option.block() instanceof DoublePlantBlock)) {
                compatible.add(option);
            }
        }
        return compatible;
    }

    private static Block pickSpecies(Block self, List<SettleTable.Option> speciesOptions, RandomSource random) {
        if (speciesOptions.isEmpty()) {
            return self;
        }

        SettleTable.Option picked = SettleTable.pickWeighted(speciesOptions, random);
        return picked != null ? picked.block() : self;
    }

    private static int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int max, Block self, Block fallbackBlock, List<SettleTable.Option> familyOptions) {
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
                    if (SettleTable.isSameSpecies(level.getBlockState(cursor), self, fallbackBlock, familyOptions)) {
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
    private static BlockPos findSpreadTarget(ServerLevel level, BlockPos origin, RandomSource random, Block species, @Nullable SpreadProfileBlockEntity profile) {
        int maxDistance = profile != null && profile.spreadDistanceOverride() >= 0
                ? profile.spreadDistanceOverride()
                : Config.FLOWER_SPREAD_DISTANCE.getAsInt();
        int verticalRange = Config.FLOWER_SPREAD_VERTICAL_RANGE.getAsInt();
        BlockState newState = species.defaultBlockState();

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
    private static BlockPos followTerrain(ServerLevel level, BlockPos column, BlockState newState, int verticalRange) {
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
