package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

// Creation and spread of the Flower Block (Stage 2 Fase 3, see PLANNING_STAGE2.md). Two entry points,
// called from two different blocks' random ticks: maybeSpawn runs from a reproductive Diseased Flower's OWN
// tick (DiseasedPlantLogic) and has a small shot at corrupting the ground right below it; randomTick is the
// Flower Block's own tick (FlowerMassBlock), spreading to one adjacent face at a time, slower than the
// flower that made it and entirely on its own from then on - the flower above keeps living/reproducing
// independently either way (see PLANNING_STAGE2.md "Decisões travadas" #1).
final class FlowerBlockLogic {

    private FlowerBlockLogic() {
    }

    // Called once per flower random tick, independent of whether that tick's own spread roll succeeds - a
    // flower can both spread AND corrupt the ground below it on the same tick, they don't compete.
    static void maybeSpawn(ServerLevel level, BlockPos flowerPos, RandomSource random, long generationsLeft, @Nullable SpreadProfileBlockEntity profile) {
        if (!Config.FLOWER_BLOCK_CONVERSION.getAsBoolean() || profile == null || !profile.spawnsFlowerBlocks()) {
            return;
        }
        if (random.nextFloat() >= (float) Config.FLOWER_BLOCK_CHANCE.getAsDouble()) {
            return;
        }

        BlockPos below = flowerPos.below();
        BlockState belowState = level.getBlockState(below);
        if (!PlantSupport.isConvertible(level, below, belowState)) {
            return;
        }

        long childGenerations = generationsLeft < 0 ? generationsLeft : generationsLeft - 1;
        place(level, below, belowState, childGenerations, profile);
    }

    static void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!Config.FLOWER_BLOCK_CONVERSION.getAsBoolean()) {
            // The mechanic was turned off after this block already existed - settle it in place instead of
            // silently no-op'ing forever, so it stops costing random ticks going forward.
            settle(level, pos, state);
            return;
        }

        SpreadProfileBlockEntity profile = level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity p ? p : null;

        double spreadChance = (profile != null && profile.spreadChanceOverride() >= 0
                ? profile.spreadChanceOverride()
                : Config.FLOWER_SPREAD_CHANCE.getAsDouble()) * Config.FLOWER_BLOCK_SPREAD_FACTOR.getAsDouble();
        if (random.nextFloat() >= (float) spreadChance) {
            return;
        }

        long generationsLeft = profile != null && profile.generationsRemaining() != SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE
                ? profile.generationsRemaining()
                : Config.FLOWER_MAX_GENERATIONS.getAsInt();

        if (generationsLeft != 0) {
            Direction[] directions = Direction.values();
            int startIndex = random.nextInt(directions.length);
            for (int i = 0; i < directions.length; i++) {
                Direction direction = directions[(startIndex + i) % directions.length];
                BlockPos neighborPos = pos.relative(direction);
                BlockState neighborState = level.getBlockState(neighborPos);
                if (PlantSupport.isConvertible(level, neighborPos, neighborState)) {
                    long childGenerations = generationsLeft < 0 ? generationsLeft : generationsLeft - 1;
                    place(level, neighborPos, neighborState, childGenerations, profile);
                    return;
                }
            }
        }

        // No generation budget left, or none of the 6 neighbors were eligible: this Flower Block stops
        // spreading for good, same "give up permanently" semantics as every other Diseased plant.
        settle(level, pos, state);
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState replaced, long generationsLeft, @Nullable SpreadProfileBlockEntity parentProfile) {
        level.setBlock(pos, FlowerDisease.FLOWER_BLOCK.get().defaultBlockState(), SettleTable.PLACEMENT_FLAGS);

        if (level.getBlockEntity(pos) instanceof FlowerMassBlockEntity childProfile) {
            childProfile.setReplacedState(replaced);
            // Same "always persist the generation countdown, bag or not" rule as DiseasedPlantLogic#
            // placeChild - the rest of the profile only copies down when there's a real bag profile to
            // copy from (maybeSpawn's caller always has one, since spawnsFlowerBlocks() requires it, but
            // a Flower Block spreading from another Flower Block with no profile falls back the same way).
            if (parentProfile != null) {
                childProfile.configure(parentProfile.toContents(generationsLeft));
            } else {
                childProfile.setGenerationsRemaining(generationsLeft);
            }
        }

        if (generationsLeft == 0) {
            // No budget left for the child to spread itself - settles the instant it's created instead of
            // existing as an active Flower Block even briefly, same as a Diseased Flower child born at 0.
            settle(level, pos, level.getBlockState(pos));
        }
    }

    private static void settle(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state.setValue(SettleTable.SETTLED, true), SettleTable.PLACEMENT_FLAGS);
    }
}
