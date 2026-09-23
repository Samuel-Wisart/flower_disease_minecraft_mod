package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.registries.DeferredBlock;

// Shared life cycle of every Diseased plant - single-block (Poppy, Dandelion, Wither Rose, Short Grass, Fern,
// Dead Bush), two-block (Sunflower, Lilac, Rose Bush, Peony, Tall Grass, Large Fern) and creeping. One engine
// handles all shapes on purpose: the bag's species pool (GardenBagContents#speciesWeights) is never split or
// restricted by shape when a NEW plant is born - a child may be a completely different species/shape than its
// parent, and no "family" is tracked to constrain that.
//
// What one random tick does (see PLANNING_STAGE2.md, "Ciclo de vida"):
//   1. Roll the reproduction chance, which decays with how deep in the lineage the plant is. Failing it just
//      ignores the tick - nothing else happens, and in particular no settle test.
//   2. Roll the lifetime test (Rabbit's Foot): each attempt lets the plant keep going with probability N/(N+1),
//      so it reproduces N times on average before settling. Failing it settles the plant.
//   3. Look for a place to put a child (SpreadSearch) and place it. If there's none, the plant settles.
// "Settling" always keeps the plant's own species - it becomes its vanilla self where that can survive, or just
// stops ticking in place - never another pool draw.
final class DiseasedPlantLogic {

    enum Shape { SINGLE, TALL, CREEPING }

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

    // Creeping species have no distinct vanilla form to settle into at all (see PLANNING_STAGE2.md Fase 2.1) -
    // self doubles as its own fallback, so settle() always falls through to "settle in place" for these
    // (vanillaSpecies == diseasedSpecies is exactly the check it makes).
    static void randomTickCreeping(
            BlockState state,
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Block self
    ) {
        randomTick(state, level, pos, random, self, self, Shape.CREEPING);
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
        // /diseasedflower debug true - a settled plant stops being randomly ticked at all (see
        // SettleTable.SETTLED/isRandomlyTicking), so simply spawning a particle every time this method actually
        // runs is already exactly "still reproducing" with no extra bookkeeping - it stops the moment a plant
        // settles, on its own. Unconditional on the rolls below on purpose: this should show ANY plant still
        // eligible to spread, not just the ones about to succeed this tick.
        if (FlowerDiseaseCommands.debugParticlesEnabled()) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 1, 0.15, 0.15, 0.15, 0.0);
        }

        SpreadProfileBlockEntity plant = profileAt(level, pos);
        GardenBagContents profile = plant != null ? plant.profile() : GardenBagContents.DEFAULT;
        long depth = plant != null ? plant.depth() : 0;

        int maxDepth = Config.FLOWER_MAX_DEPTH.getAsInt();
        if (maxDepth > 0 && depth >= maxDepth) {
            settle(level, pos, self, fallbackBlock, selfShape, state, profile, random);
            return;
        }

        double baseChance = profile.spreadChance() >= 0 ? profile.spreadChance() : Config.FLOWER_SPREAD_CHANCE.getAsDouble();
        double chance = SpreadMath.reproductionChance(baseChance, depth, SpreadMath.halfGenerations(profile.decayStrength()), profile.noDecay());
        if (random.nextDouble() >= chance) {
            return;
        }

        int attempts = SpreadMath.resolveLifetimeAttempts(profile.lifetimeAttempts());
        if (random.nextDouble() >= SpreadMath.continueProbability(attempts)) {
            settle(level, pos, self, fallbackBlock, selfShape, state, profile, random);
            return;
        }

        if (generationsLeft(profile, depth) == 0
                || tryReproduce(level, pos, random, self, fallbackBlock, selfShape, profile, depth) == null) {
            // Out of generation budget, or no valid target found anywhere: this plant settles for good, right
            // where it stands, as its own species.
            settle(level, pos, self, fallbackBlock, selfShape, state, profile, random);
        }
    }

    // Unlimited (-1) unless the bag's Bone Meal, or the server default, caps it; the budget is the cap minus how
    // deep in the lineage this plant already is.
    static long generationsLeft(GardenBagContents profile, long depth) {
        long cap = profile.generations() == SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE
                ? Config.FLOWER_MAX_GENERATIONS.getAsInt()
                : profile.generations();
        return SpreadMath.generationsLeft(cap, depth);
    }

    // Tries to give this plant one child; returns where it went, or null when there was nowhere (or nothing
    // suitable) to put one. Shared by the random tick and the burst that follows a planting.
    @Nullable
    static BlockPos tryReproduce(
            ServerLevel level,
            BlockPos pos,
            RandomSource random,
            Block self,
            Block fallbackBlock,
            Shape selfShape,
            GardenBagContents profile,
            long depth
    ) {
        List<SettleTable.Option> pool = SettleTable.parse(profile.speciesWeights());
        List<SettleTable.Option> candidates = new ArrayList<>(spreadableOptions(pool));

        boolean climbing = profile.climbing();
        int density = SpreadMath.resolveDensity(profile.densityPer16x16());
        int radius = SpreadMath.windowRadius(density);
        int limit = SpreadMath.crowdLimit(density, radius);
        int maxDistance = SpreadMath.resolveMaxDistance(profile.spreadDistance(), density);
        int verticalConfig = Config.FLOWER_SPREAD_VERTICAL_RANGE.getAsInt();

        // A climbing chain grows up a trunk one block at a time and a creeping colony grows across walls, so both
        // need the crowding window to be as tall as it is wide to see their own neighbors.
        int scanVertical = climbing || selfShape == Shape.CREEPING ? radius : verticalConfig;
        SpreadSearch.Crowd crowd = new SpreadSearch.Crowd(radius, scanVertical, limit, self, fallbackBlock, pool, profile.respectAllSpecies());

        // A child placed next to this plant would count it, and everything around it, as its own neighbors: if that
        // alone reaches the limit, nothing within the window is acceptable and the search starts beyond it - which
        // is the only way a plant standing in a crowd can still leave it (and impossible when the reach is shorter
        // than the window).
        int startRing = 1;
        if (crowd.count(level, pos) + 1 >= limit) {
            if (maxDistance <= radius) {
                return null;
            }
            startRing = radius + 1;
        }

        int verticalRange = climbing ? Math.max(verticalConfig, maxDistance) : verticalConfig;

        // One draw per shape at most: when the search finds no room for the drawn species' shape (a creeper with no
        // wall in reach, say), the other shapes of the pool still get their chance before the plant gives up.
        while (true) {
            SettleTable.Option picked = pickSpecies(candidates, fallbackBlock, random);
            Shape childShape = picked != null ? shapeOf(picked.block()) : selfShape;
            Block vanillaSpecies = picked != null ? picked.block() : fallbackBlock;
            Block diseasedSpecies = picked != null ? diseasedOf(picked.block(), self) : self;

            SpreadSearch.Target target = SpreadSearch.find(level, pos, random, diseasedSpecies, childShape, startRing, maxDistance, verticalRange, climbing, crowd);
            if (target != null) {
                return placeChild(level, target, diseasedSpecies, vanillaSpecies, childShape, profile, depth, random);
            }

            if (picked == null) {
                return null;
            }
            candidates.removeIf(option -> shapeOf(option.block()) == childShape);
            if (candidates.isEmpty()) {
                return null;
            }
        }
    }

    // Either copies the parent's species (with the configured probability, when it's still in the pool) or draws
    // from the pool by weight. Copying is what gives gardens organic same-species patches instead of a uniform mix;
    // the pool weights remain the long-run average composition.
    @Nullable
    private static SettleTable.Option pickSpecies(List<SettleTable.Option> candidates, Block parentSpecies, RandomSource random) {
        if (candidates.isEmpty()) {
            return null;
        }

        if (random.nextDouble() < Config.FLOWER_SPECIES_INHERITANCE.getAsDouble()) {
            for (SettleTable.Option option : candidates) {
                if (option.block() == parentSpecies) {
                    return option;
                }
            }
        }
        return SettleTable.pickWeighted(candidates, random);
    }

    private static BlockPos placeChild(
            ServerLevel level,
            SpreadSearch.Target target,
            Block diseasedSpecies,
            Block vanillaSpecies,
            Shape childShape,
            GardenBagContents profile,
            long parentDepth,
            RandomSource random
    ) {
        long childDepth = parentDepth + 1;
        // See SpreadSearch.Target for what target.facing() means per shape.
        BlockState childState = switch (childShape) {
            case SINGLE -> diseasedSpecies.defaultBlockState().setValue(PlantSupport.FACING, target.facing());
            case CREEPING -> diseasedSpecies.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(target.facing()), true);
            case TALL -> diseasedSpecies.defaultBlockState();
        };

        if (generationsLeft(profile, childDepth) == 0) {
            // No budget left for the child to spread itself, so it settles the instant it's created instead of
            // existing as an active Diseased Flower even briefly - as its own (just-picked) species, same as any
            // other settle decision.
            settle(level, target.pos(), diseasedSpecies, vanillaSpecies, childShape, childState, profile, random);
            return target.pos();
        }

        if (childShape == Shape.TALL) {
            DoublePlantBlock.placeAt(level, childState, target.pos(), SettleTable.PLACEMENT_FLAGS);
        } else {
            level.setBlock(target.pos(), childState, SettleTable.PLACEMENT_FLAGS);
        }

        if (level.getBlockEntity(target.pos()) instanceof SpreadProfileBlockEntity child) {
            child.inherit(profile, childDepth);
        }
        return target.pos();
    }

    // A plant that can't spread anymore isn't being "born" again - it just stabilizes. If its own vanilla species
    // can actually survive right here, it becomes that (the common case: a flower settling on ordinary ground).
    // Otherwise it keeps its EXACT current block/shape/facing and just gets marked SETTLED, instead of trying to
    // become something that can't exist at this position - this covers a species with no distinct vanilla form at
    // all (vanillaSpecies == diseasedSpecies, the creeping species) and a species whose vanilla form can't survive
    // exactly here (a flower climbing a tree trunk). No pool draw either way. Every plant gets exactly one shot at
    // corrupting the block it grows on when it settles (see FlowerBlockLogic#onPlantSettled).
    private static void settle(
            ServerLevel level,
            BlockPos pos,
            Block diseasedSpecies,
            Block vanillaSpecies,
            Shape atShape,
            BlockState inPlaceState,
            GardenBagContents profile,
            RandomSource random
    ) {
        if (vanillaSpecies != diseasedSpecies && vanillaSpecies.defaultBlockState().canSurvive(level, pos)) {
            if (atShape == Shape.TALL) {
                // The old upper half won't be overwritten unless the target is a real two-block plant, so clear it
                // first - otherwise it'd be left floating with nothing below it.
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                DoublePlantBlock.placeAt(level, vanillaSpecies.defaultBlockState(), pos, SettleTable.PLACEMENT_FLAGS);
            } else {
                level.setBlock(pos, vanillaSpecies.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
            }
        } else {
            BlockState settledState = inPlaceState.setValue(SettleTable.SETTLED, true);
            if (atShape == Shape.TALL) {
                // A brand-new tall child born already out of generations reaches this branch with pos still
                // completely empty, so a plain setBlock would only ever write the LOWER half and leave the upper
                // cell as air forever. placeAt always writes both halves, and for an existing plant settling in
                // place it's an idempotent no-op on the upper half, which is already correctly there.
                DoublePlantBlock.placeAt(level, settledState, pos, SettleTable.PLACEMENT_FLAGS);
            } else {
                level.setBlock(pos, settledState, SettleTable.PLACEMENT_FLAGS);
            }
        }

        FlowerBlockLogic.onPlantSettled(level, pos, inPlaceState, atShape, profile, random);
    }

    // ---- Planting burst ------------------------------------------------------------------------------

    // Right after the bag plants a root, forces a couple of generations of children straight away - ignoring the
    // reproduction chance and the lifetime test, but not the generation budget, density or terrain - so the player
    // gets a taste of the garden instead of one lonely flower.
    static void burst(ServerLevel level, BlockPos rootPos, RandomSource random) {
        int generations = Config.FLOWER_BURST_GENERATIONS.getAsInt();
        int maxPlants = Config.FLOWER_BURST_MAX_PLANTS.getAsInt();
        if (generations > 0 && maxPlants > 0) {
            burstFrom(level, rootPos, random, 1, generations, new int[]{maxPlants});
        }
    }

    private static void burstFrom(ServerLevel level, BlockPos pos, RandomSource random, int generation, int maxGenerations, int[] budget) {
        if (generation > maxGenerations) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        Block fallback = FlowerDisease.fallbackByDiseased().get(state.getBlock());
        if (fallback == null || isSettled(state)) {
            // Not one of our plants (a child that settled straight into its vanilla self, say), or one that's done.
            return;
        }

        SpreadProfileBlockEntity plant = profileAt(level, pos);
        if (plant == null) {
            return;
        }

        int children = generation == 1 ? 2 + random.nextInt(3) : generation == 2 ? 1 + random.nextInt(3) : 1 + random.nextInt(2);
        for (int i = 0; i < children && budget[0] > 0; i++) {
            if (generationsLeft(plant.profile(), plant.depth()) == 0) {
                return;
            }

            BlockPos child = tryReproduce(level, pos, random, state.getBlock(), fallback, shapeOf(state.getBlock()), plant.profile(), plant.depth());
            if (child == null) {
                return;
            }

            budget[0]--;
            burstFrom(level, child, random, generation + 1, maxGenerations, budget);
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    static boolean isSettled(BlockState state) {
        return state.hasProperty(SettleTable.SETTLED) && state.getValue(SettleTable.SETTLED);
    }

    // Package-visible so GardenBagItem can figure out how to build a placement state for the root plant - same
    // shape-detection rule spreading children already use, so the two can never disagree about what a given
    // species' Block instance means.
    static Shape shapeOf(Block block) {
        if (block instanceof DoublePlantBlock) {
            return Shape.TALL;
        }
        return block instanceof MultifaceBlock ? Shape.CREEPING : Shape.SINGLE;
    }

    @Nullable
    static SpreadProfileBlockEntity profileAt(LevelReader level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity profile ? profile : null;
    }

    // Only entries that actually have a Diseased counterpart to spread as are valid here - a decorative top/bottom
    // outcome has none, it's settle-only.
    static List<SettleTable.Option> spreadableOptions(List<SettleTable.Option> outcomePool) {
        List<SettleTable.Option> spreadable = new ArrayList<>();
        for (SettleTable.Option option : outcomePool) {
            if (FlowerDisease.diseasedByFallback().containsKey(option.block())) {
                spreadable.add(option);
            }
        }
        return spreadable;
    }

    // Translates an already-picked pool entry into the Diseased block a new child should actually be placed as;
    // falls back to "same species as the parent" when nothing was picked (empty pool - a hand-planted flower with
    // no bag, or a debug profile with no species configured).
    private static Block diseasedOf(Block vanillaSpecies, Block self) {
        DeferredBlock<? extends Block> diseased = FlowerDisease.diseasedByFallback().get(vanillaSpecies);
        return diseased != null ? diseased.get() : self;
    }
}
