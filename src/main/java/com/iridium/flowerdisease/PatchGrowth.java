package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;

// How a creeping flower grows a patch around itself instead of standing alone (see PLANNING_STAGE2.md, "Creeper em
// mancha"). A patch is not a blob that floods every surface around the seed - that made round mats of very different
// sizes - but a few TENDRILS crawling over the surfaces, the way ivy or a crack does:
//
//  - Every seed draws a STYLE (a long streak, a branching vine or a small tuft) and a BUDGET, the number of pieces its patch
//    may still grow, from a bell curve up to the style's cap (patchMaxPieces is the cap of the longest style). The style is
//    what makes patches different from one another, and the budget is what bounds them: a patch can never have more pieces than
//    that, however lucky.
//  - The growing end of a tendril (a piece that still has budget, and the only one that keeps a block entity) carries a face
//    - the surface it is crawling on - and a HEADING along it. Each growth step it keeps the heading, sometimes turns a right
//    angle, and goes to either side when the way ahead is blocked. Around an edge or into a corner it follows the surface, with
//    vanilla's own MultifaceSpreader rules (the same as Glow Lichen), and the heading follows too: up a wall it has just met,
//    down the side it has just gone over.
//  - Now and then a tendril SPLITS, sharing its remaining budget between two arms, or grows a second piece beside the one it
//    just made (a thicker stroke, a tuft's bulk).
//  - It stops where it would be crowded: a new piece with too many other creeper pieces around it is not placed (patchCrowding),
//    which is what keeps two patches from merging into a mat.
//  - Going down a wall and reaching its lower edge, it may HANG a strand of pieces straight down instead of wrapping under the
//    edge: pieces held only by the piece above, like a vine (see CreepingFlowerBlock). Only a tendril does this, never
//    reproduction, which places every piece against something solid.
//
// Density is ignored on purpose, and the growth chance is its own knob: a patch is one creeper's body, not a population. The
// pieces are not plants of the lineage and do not reproduce (see CreeperBlockEntity). A garden that ignores the other species
// (Fermented Spider Eye) grows its patches over the plants that are not of its pool as well, destroying them without drops.
final class PatchGrowth {

    // The knobs as one value: the game tests pin them (see override), and a whole growth step reads one consistent snapshot even if
    // the config is edited in the middle of it.
    record Settings(int maxPieces, double growthChance, double turns, double branching, double thickness, int crowding, double variety, double hangChance, int hangLength) {
        static Settings fromConfig() {
            return new Settings(
                    Config.PATCH_MAX_PIECES.getAsInt(),
                    Config.PATCH_GROWTH_CHANCE.getAsDouble(),
                    Config.PATCH_TURNS.getAsDouble(),
                    Config.PATCH_BRANCHING.getAsDouble(),
                    Config.PATCH_THICKNESS.getAsDouble(),
                    Config.PATCH_CROWDING.getAsInt(),
                    Config.PATCH_STYLE_VARIETY.getAsDouble(),
                    Config.PATCH_HANG_CHANCE.getAsDouble(),
                    Config.PATCH_HANG_LENGTH.getAsInt()
            );
        }
    }

    // The three ways a seed can grow, each as what it does by itself: `budget` is its share of patchMaxPieces, `turn` the chance
    // a step changes heading, `branch` the chance a step splits the tendril, `thickness` the chance it adds a piece beside the new
    // one, `crowdExtra` how many more neighbours it puts up with. Streaks are long and nearly straight; branching vines wind and
    // fork; tufts are small, bulky and tolerate company. Measured on a flat wall (a 2D model of all this, see
    // docs/manchas-creeper.md): about 5 pieces per patch, 90% of them under 9, none over 13, and 9 in 10 clearly elongated.
    enum Style {
        STREAK(3, 1.0, 0.22, 0.12, 0.0, 0),
        BRANCHY(4, 5.0 / 6.0, 0.42, 0.40, 0.10, 0),
        TUFT(3, 0.5, 0.50, 0.50, 0.40, 2);

        final int weight;
        final double budget;
        final double turn;
        final double branch;
        final double thickness;
        final int crowdExtra;

        Style(int weight, double budget, double turn, double branch, double thickness, int crowdExtra) {
            this.weight = weight;
            this.budget = budget;
            this.turn = turn;
            this.branch = branch;
            this.thickness = thickness;
            this.crowdExtra = crowdExtra;
        }

        static final Style[] ALL = values();

        static Style of(int index) {
            return ALL[Mth.clamp(index, 0, ALL.length - 1)];
        }
    }

    // What a style comes to under some settings: the cap of the seed's budget, the chances per step, and the crowding it tolerates.
    record Traits(int cap, double turn, double branch, double thickness, int crowd) {
    }

    // What a seed is born with: how it grows and how many pieces its patch may still grow.
    record Plan(Style style, int budget) {
    }

    private static final double AVERAGE_BUDGET;
    private static final double AVERAGE_TURN;
    private static final double AVERAGE_BRANCH;
    private static final double AVERAGE_THICKNESS;

    static {
        double weights = 0.0;
        double budget = 0.0;
        double turn = 0.0;
        double branch = 0.0;
        double thickness = 0.0;
        for (Style style : Style.ALL) {
            weights += style.weight;
            budget += style.weight * style.budget;
            turn += style.weight * style.turn;
            branch += style.weight * style.branch;
            thickness += style.weight * style.thickness;
        }
        AVERAGE_BUDGET = budget / weights;
        AVERAGE_TURN = turn / weights;
        AVERAGE_BRANCH = branch / weights;
        AVERAGE_THICKNESS = thickness / weights;
    }

    // Set by the game tests, which need patches they know; null in the real game, where the config is the source.
    @Nullable
    private static volatile Settings override;

    private PatchGrowth() {
    }

    static void override(@Nullable Settings settings) {
        override = settings;
    }

    static Settings settings() {
        Settings pinned = override;
        return pinned != null ? pinned : Settings.fromConfig();
    }

    // Each style's own numbers, pulled toward the average of all three as far as `variety` says (1 = as designed, 0 = every seed
    // the same), then scaled by the settings' multipliers.
    static Traits traits(Settings settings, Style style) {
        double variety = settings.variety();
        double budget = AVERAGE_BUDGET + (style.budget - AVERAGE_BUDGET) * variety;
        double turn = AVERAGE_TURN + (style.turn - AVERAGE_TURN) * variety;
        double branch = AVERAGE_BRANCH + (style.branch - AVERAGE_BRANCH) * variety;
        double thickness = AVERAGE_THICKNESS + (style.thickness - AVERAGE_THICKNESS) * variety;
        return new Traits(
                Math.max(0, (int) Math.round(settings.maxPieces() * Math.max(0.0, budget))),
                Mth.clamp(turn * settings.turns(), 0.0, 1.0),
                Mth.clamp(branch * settings.branching(), 0.0, 1.0),
                Mth.clamp(thickness * settings.thickness(), 0.0, 1.0),
                Math.max(1, settings.crowding() + (int) Math.round(style.crowdExtra * variety))
        );
    }

    // ---- Seeds ---------------------------------------------------------------------------------------

    // The style of a new seed (by weight) and its budget: 0 up to the style's cap with the middle values the most likely, so a
    // seed with no patch at all is rare and most are middling.
    static Plan roll(RandomSource random) {
        Settings settings = settings();
        Style style = pickStyle(random);
        return new Plan(style, rollBudget(random, traits(settings, style).cap()));
    }

    private static Style pickStyle(RandomSource random) {
        int total = 0;
        for (Style style : Style.ALL) {
            total += style.weight;
        }
        int roll = random.nextInt(total);
        for (Style style : Style.ALL) {
            roll -= style.weight;
            if (roll < 0) {
                return style;
            }
        }
        return Style.ALL[0];
    }

    // Weights rise then fall: min(budget + 1, max - budget + 1).
    static int rollBudget(RandomSource random, int max) {
        if (max <= 0) {
            return 0;
        }

        int total = 0;
        for (int budget = 0; budget <= max; budget++) {
            total += weight(budget, max);
        }

        int roll = random.nextInt(total);
        for (int budget = 0; budget <= max; budget++) {
            roll -= weight(budget, max);
            if (roll < 0) {
                return budget;
            }
        }
        return max;
    }

    static int weight(int budget, int max) {
        return Math.min(budget + 1, max - budget + 1);
    }

    // Gives a seed its patch: the plan's style and budget, crawling on `face` (one of the faces the seed's block has) in a random
    // direction along it.
    static void begin(CreeperBlockEntity seed, Plan plan, Direction face, RandomSource random) {
        seed.startPatch(plan.budget(), plan.style().ordinal(), face, randomHeading(face, random));
    }

    // One of the four directions along the surface `face` lies on.
    private static Direction randomHeading(Direction face, RandomSource random) {
        int pick = random.nextInt(4);
        for (Direction direction : Direction.values()) {
            if (direction.getAxis() != face.getAxis() && pick-- == 0) {
                return direction;
            }
        }
        return Direction.NORTH;
    }

    // ---- Growth --------------------------------------------------------------------------------------

    // One growth step of a piece that still has budget: with the configured chance the growing end of its tendril grows one more
    // piece (or a face, or a hanging piece), and the tendril is over when it finds nowhere at all to go.
    static void grow(ServerLevel level, BlockPos pos, BlockState state, CreeperBlockEntity piece, RandomSource random) {
        Settings settings = settings();
        if (random.nextDouble() >= settings.growthChance()) {
            return;
        }

        new Step(level, pos, state, piece, settings, random).run();
    }

    // Everything one growth step works with. Throwaway: made for the step and dropped after it.
    private static final class Step {
        private final ServerLevel level;
        private final BlockPos pos;
        private final BlockState state;
        private final CreeperBlockEntity piece;
        private final Settings settings;
        private final RandomSource random;
        private final CreepingFlowerBlock block;
        private final Traits traits;
        private final Spreader config;
        private final MultifaceSpreader spreader;
        @Nullable
        private final Direction face;

        Step(ServerLevel level, BlockPos pos, BlockState state, CreeperBlockEntity piece, Settings settings, RandomSource random) {
            this.level = level;
            this.pos = pos;
            this.state = state;
            this.piece = piece;
            this.settings = settings;
            this.random = random;
            this.block = (CreepingFlowerBlock) state.getBlock();
            this.traits = traits(settings, Style.of(piece.style()));
            this.config = new Spreader(block, overgrown(piece));
            this.spreader = new MultifaceSpreader(config);
            this.face = frontFace();
        }

        // The plants this garden grows over: none unless it ignores the other species (Fermented Spider Eye).
        private java.util.function.Predicate<BlockState> overgrown(CreeperBlockEntity piece) {
            GardenBagContents profile = piece.profile();
            if (profile.respectAllSpecies()) {
                return plant -> false;
            }

            List<SettleTable.Option> pool = SettleTable.parse(profile.speciesWeights());
            return plant -> SettleTable.isForeignPlant(plant, block, block, pool);
        }

        // The surface the tendril is on: the recorded face if the block still has it, otherwise any face it has (a piece saved
        // before faces were recorded, or one whose face was taken away).
        @Nullable
        private Direction frontFace() {
            if (MultifaceBlock.hasFace(state, piece.face())) {
                return piece.face();
            }

            List<Direction> present = new ArrayList<>(6);
            for (Direction direction : Direction.values()) {
                if (MultifaceBlock.hasFace(state, direction)) {
                    present.add(direction);
                }
            }
            return present.isEmpty() ? null : present.get(random.nextInt(present.size()));
        }

        void run() {
            if (face == null) {
                piece.setEnergy(0);
                return;
            }

            if (piece.hang() > 0) {
                // A strand that has begun hanging goes on down.
                if (!hangOne(piece.hang())) {
                    end();
                }
                return;
            }

            Direction heading = piece.heading();
            if (heading.getAxis() == face.getAxis()) {
                heading = randomHeading(face, random);
            }
            if (random.nextDouble() < traits.turn()) {
                heading = turned(heading);
            }

            if (heading == Direction.DOWN && face.getAxis().isHorizontal() && settings.hangChance() > 0.0 && atLowerEdge()
                    && random.nextDouble() < settings.hangChance() && hangOne(1 + random.nextInt(settings.hangLength()))) {
                return;
            }

            // Straight on - or to either side, when that is blocked. Never back.
            Direction side = turned(heading);
            for (Direction travel : new Direction[]{heading, side, side.getOpposite()}) {
                Optional<MultifaceSpreader.SpreadPos> next = spreader.getSpreadFromFaceTowardDirection(state, level, pos, face, travel, this::canSpreadInto);
                if (next.isPresent()) {
                    advance(next.get(), travel);
                    return;
                }
            }
            end();
        }

        // The direction a quarter turn from `direction` along the surface, either way.
        private Direction turned(Direction direction) {
            return random.nextBoolean() ? direction.getClockWise(face.getAxis()) : direction.getCounterClockWise(face.getAxis());
        }

        // Vanilla's rules for where a multiface block may spread, and on top of them the crowding: a piece that would have too many
        // creeper pieces around it is not placed. Another face of the piece itself adds no crowding.
        private boolean canSpreadInto(BlockGetter getter, BlockPos source, MultifaceSpreader.SpreadPos next) {
            return config.canSpreadInto(getter, source, next) && (next.pos().equals(source) || !crowded(next.pos(), source, traits.crowd()));
        }

        private boolean crowded(BlockPos center, BlockPos except, int limit) {
            int count = 0;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        cursor.setWithOffset(center, dx, dy, dz);
                        if (!cursor.equals(except) && level.getBlockState(cursor).getBlock() instanceof CreepingFlowerBlock && ++count >= limit) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        // The end of the tendril has grown into `next`, moving in the direction `travel`: pay for the piece, work out which way the
        // tendril goes on from it, and place it (see the three kinds of step below).
        private void advance(MultifaceSpreader.SpreadPos next, Direction travel) {
            BlockPos target = next.pos();
            Direction targetFace = next.face();
            int budget = piece.energy() - 1;

            if (target.equals(pos)) {
                // Another face of this very block: a wall stood in the way, and the tendril goes on up it.
                level.setBlock(pos, state.setValue(MultifaceBlock.getFaceProperty(targetFace), true), SettleTable.PLACEMENT_FLAGS);
                piece.setFront(budget, targetFace, face.getOpposite(), 0);
                settleIfDone();
                return;
            }

            // The same surface goes on across the next block, or - around an edge - the tendril follows the surface onto the side of
            // the block it was on, and heads the way it was clinging.
            boolean sameSurface = targetFace == face;
            Direction nextHeading = sameSurface ? travel : face;

            BlockState existing = level.getBlockState(target);
            // Another face of a piece that is already there: it keeps its own life, and this tendril ends against it.
            boolean merges = existing.is(block);
            BlockState placed = config.getStateForPlacement(existing, level, target, targetFace);
            if (placed == null) {
                end();
                return;
            }

            boolean flank = !merges && budget >= 1 && random.nextDouble() < traits.thickness();
            if (flank) {
                budget--;
            }
            int keep = 0;
            if (!merges && budget >= 2 && random.nextDouble() < traits.branch()) {
                keep = budget / 2;
                budget -= keep;
            }
            int childBudget = merges ? 0 : budget;

            if (!merges) {
                if (!existing.isAir()) {
                    SettleTable.clearPlant(level, target);
                }
                if (childBudget <= 0) {
                    placed = placed.setValue(SettleTable.SETTLED, true);
                }
            }
            level.setBlock(target, placed, SettleTable.PLACEMENT_FLAGS);
            if (childBudget > 0 && level.getBlockEntity(target) instanceof CreeperBlockEntity child) {
                child.startPiece(childBudget, piece.garden(), piece.style(), targetFace, nextHeading, 0);
            }

            if (flank) {
                growFlank(target, targetFace, nextHeading);
            }

            if (keep > 0) {
                piece.setFront(keep, face, turned(travel), 0);
            } else {
                piece.setEnergy(0);
            }
            settleIfDone();
        }

        // A second piece beside the one just made, on the same surface and to either side of the way the tendril goes: settled at
        // birth, it is only bulk. It may be another face of the new piece's own block, around a corner.
        private void growFlank(BlockPos target, Direction targetFace, Direction nextHeading) {
            BlockState grown = level.getBlockState(target);
            Direction first = nextHeading.getClockWise(targetFace.getAxis());
            boolean firstFirst = random.nextBoolean();
            for (Direction side : new Direction[]{firstFirst ? first : first.getOpposite(), firstFirst ? first.getOpposite() : first}) {
                Optional<MultifaceSpreader.SpreadPos> next = spreader.getSpreadFromFaceTowardDirection(grown, level, target, targetFace, side, config::canSpreadInto);
                if (next.isEmpty()) {
                    continue;
                }

                BlockPos flankPos = next.get().pos();
                BlockState existing = level.getBlockState(flankPos);
                BlockState placed = config.getStateForPlacement(existing, level, flankPos, next.get().face());
                if (placed == null) {
                    return;
                }
                if (!existing.is(block)) {
                    if (!existing.isAir()) {
                        SettleTable.clearPlant(level, flankPos);
                    }
                    placed = placed.setValue(SettleTable.SETTLED, true);
                }
                level.setBlock(flankPos, placed, SettleTable.PLACEMENT_FLAGS);
                return;
            }
        }

        // ---- Hanging strands ----

        // Whether the wall this tendril is going down ends just below: the cell under the piece is free, and has nothing to hold a
        // piece on the same wall.
        private boolean atLowerEdge() {
            BlockPos below = pos.below();
            BlockPos wall = below.relative(face);
            return level.isEmptyBlock(below) && !MultifaceBlock.canAttachTo(level, face, wall, level.getBlockState(wall));
        }

        // Hangs one piece under this one, on the same face and held only by this piece, with `left` pieces of the strand still to go
        // (this one included). The strand ends with a piece that settles at birth; false if nothing could be placed.
        private boolean hangOne(int left) {
            BlockPos below = pos.below();
            if (piece.energy() <= 0 || !level.isEmptyBlock(below) || crowded(below, pos, traits.crowd())) {
                return false;
            }

            int budget = piece.energy() - 1;
            boolean last = left <= 1 || budget <= 0;
            BlockState hung = block.defaultBlockState().setValue(MultifaceBlock.getFaceProperty(face), true);
            level.setBlock(below, last ? hung.setValue(SettleTable.SETTLED, true) : hung, SettleTable.PLACEMENT_FLAGS);
            if (!last && level.getBlockEntity(below) instanceof CreeperBlockEntity next) {
                next.startPiece(budget, piece.garden(), piece.style(), face, Direction.DOWN, left - 1);
            }

            piece.setEnergy(0);
            settleIfDone();
            return true;
        }

        // ---- Ending ----

        // The tendril has nowhere to go: this is where it stops.
        private void end() {
            piece.setEnergy(0);
            settleIfDone();
        }

        // A piece of a patch with nothing left to do settles at once and gives its block entity up - only the growing end of each
        // tendril keeps one. The seed does not: it may still have a life as a member of the lineage (see DiseasedPlantLogic).
        private void settleIfDone() {
            if (piece.energy() <= 0 && piece.lineageDone()) {
                DiseasedPlantLogic.settleInPlace(level, pos, level.getBlockState(pos));
            }
        }
    }

    // Vanilla's rules for where a multiface block may spread, except that a piece may also take the place of a plant the garden grows
    // over (nothing else: water, notably, is left alone).
    private static final class Spreader extends MultifaceSpreader.DefaultSpreaderConfig {
        private final java.util.function.Predicate<BlockState> overgrown;

        Spreader(CreepingFlowerBlock block, java.util.function.Predicate<BlockState> overgrown) {
            super(block);
            this.overgrown = overgrown;
        }

        @Override
        protected boolean stateCanBeReplaced(BlockGetter level, BlockPos pos, BlockPos spreadPos, Direction direction, BlockState state) {
            return state.isAir() || state.is(block) || overgrown.test(state);
        }
    }
}
