package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.ModConfigSpec;

// Shared spread/settle logic for two-block Diseased plants (DiseasedTallFlowerBlock extends
// TallFlowerBlock for the bonemeal-harvest behavior; DiseasedTallGrassBlock extends plain
// DoublePlantBlock directly for Tall Grass/Large Fern - they can't share a common superclass, so this
// mirrors DiseasedFlowerLogic's role for the single-block classes). Kept separate from
// DiseasedFlowerLogic since a valid target here genuinely differs: it needs a free cell above too, and
// settling has to also clear the old upper half.
final class DiseasedTallPlantLogic {

    private DiseasedTallPlantLogic() {
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
        if (state.getValue(DoublePlantBlock.HALF) != DoubleBlockHalf.LOWER) {
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

        // A bag profile's species list is the whole recipe for that planting - see DiseasedFlowerLogic
        // for the full explanation of why this replaces (not just supplements) the class's own settleWeights.
        List<SettleTable.Option> effectiveSettleOptions = speciesOptions.isEmpty() ? settleOptions : speciesOptions;

        long generationsLeft = profile != null && profile.generationsRemaining() != SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE
                ? profile.generationsRemaining()
                : state.getValue(SettleTable.GENERATION);

        int densityRadius = Config.FLOWER_DENSITY_RADIUS.getAsInt();
        int maxNearby = profile != null && profile.densityTargetPer16x16() >= 0
                ? SettleTable.densityTargetToMaxNearby(profile.densityTargetPer16x16(), densityRadius)
                : Config.FLOWER_MAX_NEARBY.getAsInt();

        boolean territorial = profile != null && profile.respectAllSpecies();
        List<SettleTable.Option> familyOptions = new ArrayList<>(settleOptions.size() + speciesOptions.size());
        familyOptions.addAll(settleOptions);
        familyOptions.addAll(speciesOptions);
        boolean tooCrowded = countNearbyFieldFlowers(level, pos, densityRadius, maxNearby, self, fallbackBlock, familyOptions, territorial) >= maxNearby;

        Block species = pickSpecies(self, speciesOptions, random);
        BlockPos target = (tooCrowded || generationsLeft == 0) ? null : findSpreadTarget(level, pos, random, species, profile);

        if (target != null) {
            long childGenerations = generationsLeft < 0 ? generationsLeft : generationsLeft - 1;
            if (childGenerations == 0) {
                // No budget left for the child to spread itself, so it settles the instant it's created
                // instead of existing as an active (randomly-ticking) Diseased Flower even briefly.
                SettleTable.Option picked = SettleTable.pickWeighted(effectiveSettleOptions, random);
                SettleTable.place(level, target, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
            } else {
                int blockstateGenerations = (int) Math.max(0, Math.min(64, childGenerations < 0 ? 64 : childGenerations));
                DoublePlantBlock.placeAt(level, species.defaultBlockState().setValue(SettleTable.GENERATION, blockstateGenerations), target, SettleTable.PLACEMENT_FLAGS);
                if (profile != null && profile.hasOverride() && level.getBlockEntity(target) instanceof SpreadProfileBlockEntity childProfile) {
                    childProfile.copyFrom(profile, childGenerations);
                }
            }
        } else {
            SettleTable.Option picked = SettleTable.pickWeighted(effectiveSettleOptions, random);
            // The old upper half won't be overwritten unless the outcome is itself a "full" two-block
            // placement, so clear it first - otherwise it would be left floating with nothing below it.
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
            SettleTable.place(level, pos, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
        }
    }

    // Only two-block species are valid here - a bag's species ratio might also list single-block
    // flowers, meant for DiseasedFlowerBlock/DiseasedWitherRoseBlock's own spreading instead.
    private static List<SettleTable.Option> compatibleSpeciesOptions(@Nullable SpreadProfileBlockEntity profile) {
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

    private static Block pickSpecies(Block self, List<SettleTable.Option> speciesOptions, RandomSource random) {
        if (speciesOptions.isEmpty()) {
            return self;
        }

        SettleTable.Option picked = SettleTable.pickWeighted(speciesOptions, random);
        return picked != null ? picked.block() : self;
    }

    private static int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int max, Block self, Block fallbackBlock, List<SettleTable.Option> familyOptions, boolean territorial) {
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
                    boolean crowding = territorial ? SettleTable.isAnyPlant(state) : SettleTable.isSameSpecies(state, self, fallbackBlock, familyOptions);
                    if (crowding) {
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
    private static BlockPos followTerrain(ServerLevel level, BlockPos column, BlockState newLowerState, int verticalRange) {
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

    private static boolean isValidSpot(ServerLevel level, BlockPos pos, BlockState newLowerState) {
        return level.isEmptyBlock(pos) && level.isEmptyBlock(pos.above()) && newLowerState.canSurvive(level, pos);
    }
}
