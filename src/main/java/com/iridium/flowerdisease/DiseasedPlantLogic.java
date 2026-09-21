package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
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
// speciesWeights) is never split or restricted by shape when a NEW plant is born - every spreading child
// is an independent weighted draw over the WHOLE pool (see spreadableOptions below), which may be a
// completely different species/shape than its parent. No "family" is tracked to constrain that. Settling
// is different, though: a plant that gives up spreading isn't being "born" again, it's just stabilizing -
// it becomes its own vanilla species (fallbackBlock) where that can actually survive, or otherwise just
// stops ticking in place (SettleTable.SETTLED) - see settle() below and PLANNING.md for why an earlier
// version of this mod (wrongly) turned settling into another pool draw entirely.
final class DiseasedPlantLogic {

    enum Shape { SINGLE, TALL }

    // Facing is only meaningful for Shape.SINGLE (two-block species never tilt - see
    // PLANNING_STAGE2.md Fase 1); UP always accompanies TALL targets and is otherwise ignored.
    private record SpreadTarget(BlockPos pos, Direction facing) {
    }

    private static final Direction[] HORIZONTAL_FACINGS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};

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
        // /diseasedflower debug true - a settled plant stops being randomly ticked at all (see
        // SettleTable.SETTLED/isRandomlyTicking), so simply spawning a particle every time this method
        // actually runs is already exactly "still reproducing" with no extra bookkeeping - it stops the
        // moment a plant settles, on its own. Unconditional on spreadChance/generations on purpose: this
        // should show ANY plant still eligible to spread, not just the ones about to succeed this tick.
        if (FlowerDiseaseCommands.debugParticlesEnabled()) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, 1, 0.15, 0.15, 0.15, 0.0);
        }

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
                : Config.FLOWER_MAX_GENERATIONS.getAsInt();

        // Climbing widens how far UP a spread target may land (see findSpreadTarget) - the crowding scan
        // below has to widen by the exact same amount, or a chain climbing a tall trunk quickly grows
        // taller than the density check can see, never counts its own siblings as crowding, and keeps
        // climbing indefinitely (reported after testing: with "ignore other species" on, a climbing
        // planting never seemed to settle at all). Computed once here and threaded into both the crowding
        // scan and findSpreadTarget so the two can never drift apart like this again.
        boolean climbing = profile != null && profile.climbing();
        int maxSpreadDistance = profile != null && profile.spreadDistanceOverride() >= 0
                ? profile.spreadDistanceOverride()
                : Config.FLOWER_SPREAD_DISTANCE.getAsInt();
        int verticalRange = climbing
                ? Math.max(Config.FLOWER_SPREAD_VERTICAL_RANGE.getAsInt(), maxSpreadDistance)
                : Config.FLOWER_SPREAD_VERTICAL_RANGE.getAsInt();

        int densityRadius = Config.FLOWER_DENSITY_RADIUS.getAsInt();
        int maxNearby = profile != null && profile.densityTargetPer16x16() >= 0
                ? SettleTable.densityTargetToMaxNearby(profile.densityTargetPer16x16(), densityRadius)
                : Config.FLOWER_MAX_NEARBY.getAsInt();

        boolean territorial = profile != null && profile.respectAllSpecies();
        boolean tooCrowded = countNearbyFieldFlowers(level, pos, densityRadius, verticalRange, maxNearby, self, fallbackBlock, outcomePool, territorial) >= maxNearby;

        if (!tooCrowded && generationsLeft != 0) {
            // Independent draw over the whole pool every time - may be a different shape than this plant.
            // Falls back to "spread as itself" (species = self) when nothing was picked, which is what
            // keeps a hand-planted flower with no bag profile spreading as its own species like before.
            SettleTable.Option pickedSpecies = SettleTable.pickWeighted(spreadableOptions(outcomePool), random);
            Shape childShape = pickedSpecies != null ? shapeOf(pickedSpecies.block()) : selfShape;
            Block vanillaSpecies = pickedSpecies != null ? pickedSpecies.block() : fallbackBlock;
            Block diseasedSpecies = pickedSpecies != null ? diseasedOf(pickedSpecies.block(), self) : self;

            SpreadTarget target = findSpreadTarget(level, pos, random, diseasedSpecies, maxSpreadDistance, verticalRange, climbing, childShape);
            if (target != null) {
                placeChild(level, target, diseasedSpecies, vanillaSpecies, childShape, generationsLeft, profile);
                return;
            }
        }

        // Couldn't produce a spreading child this tick (crowded, out of generation budget, or no valid
        // target found anywhere): this plant settles for good, right where it stands, as its own species.
        settle(level, pos, self, fallbackBlock, selfShape, state);
    }

    private static void placeChild(
            ServerLevel level,
            SpreadTarget target,
            Block diseasedSpecies,
            Block vanillaSpecies,
            Shape childShape,
            long generationsLeft,
            @Nullable SpreadProfileBlockEntity profile
    ) {
        long childGenerations = generationsLeft < 0 ? generationsLeft : generationsLeft - 1;
        // Facing only applies to Shape.SINGLE - see PlantSupport.FACING and the SpreadTarget comment.
        BlockState childState = childShape == Shape.SINGLE
                ? diseasedSpecies.defaultBlockState().setValue(PlantSupport.FACING, target.facing())
                : diseasedSpecies.defaultBlockState();

        if (childGenerations == 0) {
            // No budget left for the child to spread itself, so it settles the instant it's created
            // instead of existing as an active Diseased Flower even briefly - as its own (just-picked)
            // species, same as any other settle decision.
            settle(level, target.pos(), diseasedSpecies, vanillaSpecies, childShape, childState);
            return;
        }

        if (childShape == Shape.TALL) {
            DoublePlantBlock.placeAt(level, childState, target.pos(), SettleTable.PLACEMENT_FLAGS);
        } else {
            level.setBlock(target.pos(), childState, SettleTable.PLACEMENT_FLAGS);
        }

        if (level.getBlockEntity(target.pos()) instanceof SpreadProfileBlockEntity childProfile) {
            // Always persists the generation countdown, bag or not - it's the only field that's tracked
            // regardless of whether a bag profile is active (see SpreadProfileBlockEntity). The rest of
            // the profile only gets copied down when there actually is one to copy.
            if (profile != null) {
                childProfile.configure(profile.toContents(childGenerations));
            } else {
                childProfile.setGenerationsRemaining(childGenerations);
            }
        }
    }

    // A plant that can't spread anymore isn't being "born" again - it just stabilizes. If its own vanilla
    // species can actually survive right here, it becomes that (unchanged from before - the common case:
    // a flower settling on ordinary ground). Otherwise it keeps its EXACT current block/shape/facing and
    // just gets marked SETTLED, instead of trying to become something that can't exist at this position -
    // this covers a species with no distinct vanilla form at all (vanillaSpecies == diseasedSpecies, the
    // creeping species from Stage 2) and a species whose vanilla form can't survive exactly here (e.g. a
    // flower climbing a tree trunk, also Stage 2). No pool draw either way - see class comment.
    private static void settle(ServerLevel level, BlockPos pos, Block diseasedSpecies, Block vanillaSpecies, Shape atShape, BlockState inPlaceState) {
        if (vanillaSpecies != diseasedSpecies && vanillaSpecies.defaultBlockState().canSurvive(level, pos)) {
            if (atShape == Shape.TALL) {
                // The old upper half won't be overwritten unless the target is a real two-block plant, so
                // clear it first - otherwise it'd be left floating with nothing below it.
                level.setBlock(pos.above(), Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                DoublePlantBlock.placeAt(level, vanillaSpecies.defaultBlockState(), pos, SettleTable.PLACEMENT_FLAGS);
            } else {
                level.setBlock(pos, vanillaSpecies.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
            }
            return;
        }

        BlockState settledState = inPlaceState.setValue(SettleTable.SETTLED, true);
        if (atShape == Shape.TALL) {
            // placeChild's "child born with 0 generations left" call reaches this branch with pos still
            // completely empty (nothing placed yet at all) whenever the picked species can't survive as
            // vanilla right where it landed (e.g. on top of a climbable block) - a plain setBlock(pos, ...)
            // here only ever wrote the LOWER half, leaving the upper cell as air forever (bug reported
            // after testing: a tall species "climbing" a tree showed only its lower half). placeAt always
            // writes both halves, and for the OTHER call site (an existing active plant settling in place)
            // it's an idempotent no-op on the upper half, which is already correctly there.
            DoublePlantBlock.placeAt(level, settledState, pos, SettleTable.PLACEMENT_FLAGS);
        } else {
            level.setBlock(pos, settledState, SettleTable.PLACEMENT_FLAGS);
        }
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
            if (FlowerDisease.diseasedByFallback().containsKey(option.block())) {
                spreadable.add(option);
            }
        }
        return spreadable;
    }

    // Translates an already-picked pool entry into the Diseased block a new child should actually be
    // placed as; falls back to "same species as the parent" when nothing was picked (empty pool - a
    // hand-planted flower with no bag, or a debug profile with no species configured).
    private static Block diseasedOf(Block vanillaSpecies, Block self) {
        DeferredBlock<? extends Block> diseased = FlowerDisease.diseasedByFallback().get(vanillaSpecies);
        return diseased != null ? diseased.get() : self;
    }

    private static int countNearbyFieldFlowers(LevelReader level, BlockPos center, int radius, int verticalRange, int max, Block self, Block fallbackBlock, List<SettleTable.Option> outcomePool, boolean territorial) {
        int count = 0;
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

    // maxDistance/verticalRange/climbing are computed once in randomTick (shared with the crowding scan -
    // see the comment there) rather than re-derived here, so the two can never disagree about how far a
    // climbing planting is allowed to reach.
    @Nullable
    private static SpreadTarget findSpreadTarget(ServerLevel level, BlockPos origin, RandomSource random, Block species, int maxDistance, int verticalRange, boolean climbing, Shape shape) {
        BlockState baseState = species.defaultBlockState();

        for (int attempt = 0; attempt < Config.FLOWER_SPREAD_ATTEMPTS.getAsInt(); attempt++) {
            int dx = random.nextInt(maxDistance * 2 + 1) - maxDistance;
            int dz = random.nextInt(maxDistance * 2 + 1) - maxDistance;
            if (dx == 0 && dz == 0) {
                continue;
            }

            SpreadTarget candidate = followTerrain(level, origin.offset(dx, 0, dz), baseState, verticalRange, shape, climbing, random);
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
    private static SpreadTarget followTerrain(ServerLevel level, BlockPos column, BlockState baseState, int verticalRange, Shape shape, boolean climbing, RandomSource random) {
        SpreadTarget direct = tryFacings(level, column, baseState, shape, climbing, random);
        if (direct != null) {
            return direct;
        }

        for (int dy = 1; dy <= verticalRange; dy++) {
            SpreadTarget up = tryFacings(level, column.above(dy), baseState, shape, climbing, random);
            if (up != null) {
                return up;
            }

            SpreadTarget down = tryFacings(level, column.below(dy), baseState, shape, climbing, random);
            if (down != null) {
                return down;
            }
        }

        return null;
    }

    // TALL never tilts, so it's just the one (UP) check. SINGLE always tries standing upright first;
    // only when this plant is actually configured to climb does it also try each horizontal direction,
    // starting from a random one so a trunk with climbable wood on every side doesn't always end up
    // tilting the same way.
    @Nullable
    private static SpreadTarget tryFacings(ServerLevel level, BlockPos pos, BlockState baseState, Shape shape, boolean climbing, RandomSource random) {
        if (shape == Shape.TALL) {
            return isValidUpSpot(level, pos, baseState, shape, climbing) ? new SpreadTarget(pos, Direction.UP) : null;
        }

        BlockState upState = baseState.setValue(PlantSupport.FACING, Direction.UP);
        if (isValidUpSpot(level, pos, upState, shape, climbing)) {
            return new SpreadTarget(pos, Direction.UP);
        }
        if (!climbing) {
            return null;
        }

        int startIndex = random.nextInt(HORIZONTAL_FACINGS.length);
        for (int i = 0; i < HORIZONTAL_FACINGS.length; i++) {
            Direction facing = HORIZONTAL_FACINGS[(startIndex + i) % HORIZONTAL_FACINGS.length];
            BlockState tiltedState = baseState.setValue(PlantSupport.FACING, facing);
            if (isValidSpot(level, pos, tiltedState, shape)) {
                return new SpreadTarget(pos, facing);
            }
        }

        return null;
    }

    // Standing upright is unconditionally allowed by canSurvive on top of a climbable block too (see
    // PlantSupport), since an already-existing plant there must never lose canSurvive. But that same
    // leniency would let a bag WITHOUT Twisting Vines incidentally start growing on trees just because the
    // random search happened to land there - the player explicitly asked for "no Twisting Vines = ground
    // only, with Twisting Vines = top AND side of organic blocks". So the SEARCH additionally requires
    // climbing to be on before it'll accept a spot that's ONLY valid because of the climbable tag.
    // Ordinary ground is never gated - only ground that needed the climbable exception is.
    private static boolean isValidUpSpot(ServerLevel level, BlockPos pos, BlockState upState, Shape shape, boolean climbing) {
        if (!isValidSpot(level, pos, upState, shape)) {
            return false;
        }
        boolean viaClimbableOnly = PlantSupport.isClimbable(level.getBlockState(pos.below()));
        return climbing || !viaClimbableOnly;
    }

    private static boolean isValidSpot(ServerLevel level, BlockPos pos, BlockState newState, Shape shape) {
        if (!level.isEmptyBlock(pos) || !newState.canSurvive(level, pos)) {
            return false;
        }
        return shape == Shape.SINGLE || level.isEmptyBlock(pos.above());
    }
}
