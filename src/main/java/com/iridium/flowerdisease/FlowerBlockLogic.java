package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;

// Creation and spread of the Flower Block (see PLANNING_STAGE2.md, Fase 3). Two entry points, called from two
// different places: onPlantSettled runs once for every Diseased plant at the moment it settles and has a single
// shot (its odds set by the number of Moss Blocks in the bag) at corrupting the block it grows on; randomTick is
// the Flower Block's own tick, spreading to one adjacent face at a time, slower than the plants that made it and
// entirely on its own from then on - the plant on top keeps standing on it either way.
final class FlowerBlockLogic {

    private static final Direction[] DIRECTIONS = Direction.values();

    private FlowerBlockLogic() {
    }

    // Every plant rolls exactly once, when it settles - whether that's the lifetime test failing, the search
    // finding no room, or being born already out of generations. What gets converted is the block the plant is
    // actually leaning on: below for a standing one, behind for a tilted one, the block of an active face for a
    // creeper. The Flower Block draws its own small generation cap instead of inheriting the plant's.
    static void onPlantSettled(
            ServerLevel level,
            BlockPos plantPos,
            BlockState plantState,
            DiseasedPlantLogic.Shape shape,
            Lineage lineage,
            RandomSource random
    ) {
        int mossBlocks = lineage.profile().mossBlocks();
        if (mossBlocks <= 0 || !Config.FLOWER_BLOCK_CONVERSION.getAsBoolean()) {
            return;
        }
        if (random.nextDouble() >= SpreadMath.flowerBlockChance(mossBlocks)) {
            return;
        }

        BlockPos support = supportOf(plantPos, plantState, shape, random);
        if (support == null) {
            return;
        }

        BlockState supportState = level.getBlockState(support);
        if (!PlantSupport.isConvertible(level, support, supportState)) {
            return;
        }

        place(level, support, supportState, lineage.garden(), SpreadMath.rollFlowerBlockGenerations(random), 0);
    }

    @Nullable
    private static BlockPos supportOf(BlockPos plantPos, BlockState plantState, DiseasedPlantLogic.Shape shape, RandomSource random) {
        if (shape == DiseasedPlantLogic.Shape.CREEPING) {
            List<Direction> faces = new ArrayList<>(MultifaceBlock.availableFaces(plantState));
            return faces.isEmpty() ? null : plantPos.relative(faces.get(random.nextInt(faces.size())));
        }

        Direction facing = plantState.hasProperty(PlantSupport.FACING) ? plantState.getValue(PlantSupport.FACING) : Direction.UP;
        return facing == Direction.UP ? plantPos.below() : plantPos.relative(facing.getOpposite());
    }

    // The Flower Block's own random tick: the same three steps as any Diseased plant, minus the decay and the
    // species (it only ever spreads as more of itself).
    static void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!Config.FLOWER_BLOCK_CONVERSION.getAsBoolean()) {
            // The mechanic was turned off after this block already existed - settle it in place instead of
            // silently no-op'ing forever, so it stops costing random ticks going forward.
            settle(level, pos, state);
            return;
        }

        FlowerMassBlockEntity block = level.getBlockEntity(pos) instanceof FlowerMassBlockEntity flowerBlock ? flowerBlock : null;
        GardenBagContents profile = block != null ? block.profile() : GardenBagContents.DEFAULT;
        int garden = block != null ? block.garden() : GardenRegistry.NO_GARDEN;
        int cap = block != null ? block.cap() : 0;
        long depth = block != null ? block.depth() : 0;

        double baseChance = profile.spreadChance() >= 0 ? profile.spreadChance() : Config.FLOWER_SPREAD_CHANCE.getAsDouble();
        if (random.nextDouble() >= baseChance * Config.FLOWER_BLOCK_SPREAD_FACTOR.getAsDouble()) {
            return;
        }

        int attempts = SpreadMath.resolveLifetimeAttempts(profile.lifetimeAttempts());
        if (random.nextDouble() >= SpreadMath.continueProbability(attempts)) {
            settle(level, pos, state);
            return;
        }

        if (generationsLeft(cap, depth) != 0) {
            int startIndex = random.nextInt(DIRECTIONS.length);
            for (int i = 0; i < DIRECTIONS.length; i++) {
                BlockPos neighborPos = pos.relative(DIRECTIONS[(startIndex + i) % DIRECTIONS.length]);
                BlockState neighborState = level.getBlockState(neighborPos);
                // isConvertible alone would happily tunnel the corruption straight through solid rock, fully buried
                // and never visible - hasExposedFace requires the candidate to still be touching open air or a
                // non-full block (flowers, slabs, stairs...) on at least one OTHER side, so it stays somewhere a
                // player could actually find it instead of sinking.
                if (PlantSupport.isConvertible(level, neighborPos, neighborState) && hasExposedFace(level, neighborPos)) {
                    place(level, neighborPos, neighborState, garden, cap, depth + 1);
                    return;
                }
            }
        }

        // No generation budget left, or none of the 6 neighbors were eligible: this Flower Block stops spreading
        // for good, same "give up permanently" semantics as every other Diseased plant.
        settle(level, pos, state);
    }

    // A Flower Block's budget is the cap it drew (see SpreadMath#rollFlowerBlockGenerations), never unlimited; one
    // placed by hand, with none, gets the most the server allows.
    private static long generationsLeft(int cap, long depth) {
        long total = cap > 0 ? cap : Config.FLOWER_BLOCK_MAX_GENERATIONS.getAsInt() + 1;
        return SpreadMath.generationsLeft(total, depth);
    }

    // "Exposed" means at least one of the 6 neighbors isn't a full cube - open air, or a non-full block like a
    // flower, slab or stair. The parent Flower Block doing the spreading (a full cube itself) is one of these 6
    // neighbors and never counts toward it, so this genuinely requires a DIFFERENT opening.
    private static boolean hasExposedFace(LevelReader level, BlockPos pos) {
        for (Direction direction : DIRECTIONS) {
            BlockPos neighborPos = pos.relative(direction);
            BlockState neighborState = level.getBlockState(neighborPos);
            if (!Block.isShapeFullBlock(neighborState.getCollisionShape(level, neighborPos))) {
                return true;
            }
        }
        return false;
    }

    private static void place(ServerLevel level, BlockPos pos, BlockState replaced, int garden, int cap, long depth) {
        level.setBlock(pos, FlowerDisease.FLOWER_BLOCK.get().defaultBlockState(), SettleTable.PLACEMENT_FLAGS);

        if (level.getBlockEntity(pos) instanceof FlowerMassBlockEntity flowerBlock) {
            flowerBlock.setReplacedState(replaced);
            flowerBlock.inheritFlowerBlock(garden, cap, depth);
        }

        if (generationsLeft(cap, depth) == 0) {
            // No budget left for the block to spread itself - settles the instant it's created instead of existing
            // as an active Flower Block even briefly, same as a Diseased Flower child born out of generations.
            settle(level, pos, level.getBlockState(pos));
        }
    }

    // Stops the block for good; it keeps only its garden and what it replaced.
    private static void settle(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state.setValue(SettleTable.SETTLED, true), SettleTable.PLACEMENT_FLAGS);
        if (level.getBlockEntity(pos) instanceof FlowerMassBlockEntity flowerBlock) {
            flowerBlock.settled();
        }
    }
}
