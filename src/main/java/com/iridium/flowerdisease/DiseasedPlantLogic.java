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
import net.neoforged.neoforge.registries.DeferredBlock;

// Shared spread/settle logic for every Diseased plant, both single-block (Poppy, Dandelion, Wither Rose,
// Short Grass, Fern, Dead Bush) and two-block (Sunflower, Lilac, Rose Bush, Peony, Tall Grass, Large
// Fern). One engine handles both shapes on purpose: the bag's species pool (SpreadProfileBlockEntity#
// speciesWeights) is never split or restricted by shape/family. Every single time a plant is born - a
// spreading child, or the final settle outcome of a plant that can't spread anymore - it's an independent
// weighted draw over the WHOLE pool, constrained only by what physically fits at that position (see
// spreadableOptions/fittingOptions below). No "this plant's species/family" concept is tracked or enforced
// anywhere; see PLANNING.md for why an earlier version of this mod did that and why it was wrong.
final class DiseasedPlantLogic {

    enum Shape { SINGLE, TALL }

    private DiseasedPlantLogic() {
    }

    static void randomTickSingle(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Block self,
            Block fallbackBlock
    ) {
        randomTick(state, level, pos, random, self, fallbackBlock, Shape.SINGLE);
    }

    static void randomTickTall(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Block self,
            Block fallbackBlock
    ) {
        // Only the lower half acts, so a two-block plant doesn't roll twice per tick.
        if (state.getValue(DoublePlantBlock.HALF) != DoubleBlockHalf.LOWER) {
            return;
        }
        randomTick(state, level, pos, random, self, fallbackBlock, Shape.TALL);
    }

    private static void randomTick(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Block self,
            Block fallbackBlock,
            Shape selfShape
    ) {
        SpreadProfileBlockEntity profile = profileAt(level, pos);

        double spreadChance = profile != null && profile.spreadChanceOverride() >= 0
                ? profile.spreadChanceOverride()
                : Config.FLOWER_SPREAD_CHANCE.getAsDouble();
        if (random.nextFloat() >= (float) spreadChance) {
            return;
        }

        // The bag's species grid (or the debug command) is the ONLY source of outcomes - a plant with no
        // profile/pool configured just always settles back into its own plain vanilla self. This single
        // pool mixes single- and two-block species together on purpose.
        List<SettleTable.Option> outcomePool = profile != null ? SettleTable.parse(profile.speciesWeights()) : List.of();

        long generationsLeft = profile != null && profile.generationsRemaining() != SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE
                ? profile.generationsRemaining()
                : state.getValue(SettleTable.GENERATION);

        int densityRadius = Config.FLOWER_DENSITY_RADIUS.getAsInt();
        int maxNearby = profile != null && profile.densityTargetPer16x16() >= 0
                ? SettleTable.densityTargetToMaxNearby(profile.densityTargetPer16x16(), densityRadius)
                : Config.FLOWER_MAX_NEARBY.getAsInt();

        boolean territorial = profile != null && profile.respectAllSpecies();
        boolean tooCrowded = countNearbyFieldFlowers(level, pos, densityRadius, maxNearby, self, fallbackBlock, outcomePool, territorial) >= maxNearby;

        if (!tooCrowded && generationsLeft != 0) {
            // Independent draw over the whole pool every time - may be a different shape than this plant.
            // Falls back to "spread as itself" (species = self) when nothing was picked, which is what
            // keeps a hand-planted flower with no bag profile spreading as its own species like before.
            SettleTable.Option pickedSpecies = SettleTable.pickWeighted(spreadableOptions(outcomePool), random);
            Shape childShape = pickedSpecies != null ? shapeOf(pickedSpecies.block()) : selfShape;
            Block species = pickedSpecies != null ? diseasedOf(pickedSpecies.block(), self) : self;

            BlockPos target = findSpreadTarget(level, pos, random, species, profile, childShape);
            if (target != null) {
                placeChild(level, target, random, species, childShape, generationsLeft, profile, outcomePool);
                return;
            }
        }

        // Couldn't produce a spreading child this tick (crowded, out of generation budget, or no valid
        // target found anywhere): this plant settles for good, right where it stands.
        settle(level, pos, random, fallbackBlock, selfShape, outcomePool);
    }

    private static void placeChild(
            ServerLevel level,
            BlockPos target,
            RandomSource random,
            Block species,
            Shape childShape,
            long generationsLeft,
            @Nullable SpreadProfileBlockEntity profile,
            List<SettleTable.Option> outcomePool
    ) {
        long childGenerations = generationsLeft < 0 ? generationsLeft : generationsLeft - 1;
        if (childGenerations == 0) {
            // No budget left for the child to spread itself, so it settles the instant it's created
            // instead of existing as an active Diseased Flower even briefly - still an independent draw
            // over the whole pool, same as any other settle decision.
            settle(level, target, random, species, childShape, outcomePool);
            return;
        }

        int blockstateGenerations = (int) Math.max(0, Math.min(64, childGenerations < 0 ? 64 : childGenerations));
        BlockState childState = species.defaultBlockState().setValue(SettleTable.GENERATION, blockstateGenerations);
        if (childShape == Shape.TALL) {
            DoublePlantBlock.placeAt(level, childState, target, SettleTable.PLACEMENT_FLAGS);
        } else {
            level.setBlock(target, childState, SettleTable.PLACEMENT_FLAGS);
        }

        if (profile != null && profile.hasOverride() && level.getBlockEntity(target) instanceof SpreadProfileBlockEntity childProfile) {
            childProfile.copyFrom(profile, childGenerations);
        }
    }

    // Picks this plant's final resting place from the WHOLE bag pool - never restricted to its own
    // species - keeping only outcomes that actually fit at `pos`: a single-block plant can only host a
    // two-block "full" outcome if the cell above is free too, and every outcome needs the right ground
    // (canSurvive) since the pool may now mix species with different ground requirements (e.g. Dead Bush
    // needs sand, Wither Rose accepts netherrack/soul sand/soul soil too).
    private static void settle(ServerLevel level, BlockPos pos, RandomSource random, Block fallbackBlock, Shape atShape, List<SettleTable.Option> outcomePool) {
        List<SettleTable.Option> fitting = fittingOptions(level, pos, atShape, outcomePool);
        SettleTable.Option picked = fitting.isEmpty() ? null : SettleTable.pickWeighted(fitting, random);

        if (atShape == Shape.TALL) {
            // The old upper half won't be overwritten unless the outcome is itself a "full" two-block
            // placement, so clear it first - otherwise it'd be left floating with nothing below it.
            level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
        }
        SettleTable.place(level, pos, picked != null ? picked : new SettleTable.Option(fallbackBlock, 1, SettleTable.Half.FULL));
    }

    private static List<SettleTable.Option> fittingOptions(ServerLevel level, BlockPos pos, Shape atShape, List<SettleTable.Option> outcomePool) {
        List<SettleTable.Option> fitting = new ArrayList<>();
        for (SettleTable.Option option : outcomePool) {
            if (atShape == Shape.SINGLE && shapeOf(option.block()) == Shape.TALL && !level.isEmptyBlock(pos.above())) {
                continue;
            }
            if (!option.block().defaultBlockState().canSurvive(level, pos)) {
                continue;
            }
            fitting.add(option);
        }
        return fitting;
    }

    private static Shape shapeOf(Block block) {
        return block instanceof DoublePlantBlock ? Shape.TALL : Shape.SINGLE;
    }

    @Nullable
    private static SpreadProfileBlockEntity profileAt(LevelReader level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity profile ? profile : null;
    }

    // Only entries that actually have a Diseased counterpart to spread as are valid here - a decorative
    // top/bottom outcome has none, it's settle-only.
    static List<SettleTable.Option> spreadableOptions(List<SettleTable.Option> outcomePool) {
        List<SettleTable.Option> spreadable = new ArrayList<>();
        for (SettleTable.Option option : outcomePool) {
            if (FlowerDisease.DISEASED_BY_FALLBACK.containsKey(option.block())) {
                spreadable.add(option);
            }
        }
        return spreadable;
    }

    // Translates an already-picked pool entry into the Diseased block a new child should actually be
    // placed as; falls back to "same species as the parent" when nothing was picked (empty pool - a
    // hand-planted flower with no bag, or a debug profile with no species configured).
    private static Block diseasedOf(Block vanillaSpecies, Block self) {
        DeferredBlock<? extends Block> diseased = FlowerDisease.DISEASED_BY_FALLBACK.get(vanillaSpecies);
        return diseased != null ? diseased.get() : self;
    }

    private static int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int max, Block self, Block fallbackBlock, List<SettleTable.Option> outcomePool, boolean territorial) {
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
                    boolean crowding = territorial ? SettleTable.isAnyPlant(state) : SettleTable.isSameSpecies(state, self, fallbackBlock, outcomePool);
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
    private static BlockPos findSpreadTarget(ServerLevel level, BlockPos origin, RandomSource random, Block species, @Nullable SpreadProfileBlockEntity profile, Shape shape) {
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

            BlockPos candidate = followTerrain(level, origin.offset(dx, 0, dz), newState, verticalRange, shape);
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    // Slopes/steps mean the target column often isn't level with the parent flower, so this checks nearby
    // heights too (closest to the parent's Y first) instead of only the exact same Y. A TALL shape also
    // needs the cell above free for the second half.
    @Nullable
    private static BlockPos followTerrain(ServerLevel level, BlockPos column, BlockState newState, int verticalRange, Shape shape) {
        if (isValidSpot(level, column, newState, shape)) {
            return column;
        }

        for (int dy = 1; dy <= verticalRange; dy++) {
            BlockPos up = column.above(dy);
            if (isValidSpot(level, up, newState, shape)) {
                return up;
            }

            BlockPos down = column.below(dy);
            if (isValidSpot(level, down, newState, shape)) {
                return down;
            }
        }

        return null;
    }

    private static boolean isValidSpot(ServerLevel level, BlockPos pos, BlockState newState, Shape shape) {
        if (!level.isEmptyBlock(pos) || !newState.canSurvive(level, pos)) {
            return false;
        }
        return shape == Shape.SINGLE || level.isEmptyBlock(pos.above());
    }
}
