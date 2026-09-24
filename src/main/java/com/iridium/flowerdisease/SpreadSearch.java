package com.iridium.flowerdisease;

import java.util.Arrays;
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

// Finds where a new plant may be born: the CLOSEST valid spot to the parent first (distance ring 1, then 2, and so
// on up to the maximum reach) instead of a random jump anywhere in a box, so a garden fills in gaps before it
// moves on and the maximum reach (derived from the density, see SpreadMath) only matters when the surroundings
// are full. Also owns the crowding check, which is evaluated at each candidate destination - a parent standing in
// a crowded spot can still leap past the crowd instead of giving up on the spot.
final class SpreadSearch {

    // Meaning depends on shape: for SINGLE it's the PlantSupport.FACING value to place (tilt direction, or UP for
    // standing upright - see PLANNING_STAGE2.md Fase 1); TALL ignores it (always UP, two-block species never
    // tilt); for CREEPING it's the MultifaceBlock face to activate - the direction FROM the new block TOWARD the
    // solid neighbor it's grabbing onto (MultifaceBlock's own convention, the opposite of PlantSupport.FACING's
    // "away from the support" convention).
    record Target(BlockPos pos, Direction facing) {
    }

    // The minimum spacing every plant keeps from every other (see SpreadMath#minSpacing): a spot is crowded when any
    // plant of the relevant kind sits closer than `spacing` to it. Territorial (the default) counts every plant,
    // otherwise only this species' family.
    record Crowd(
            double spacing,
            int verticalRange,
            Block self,
            Block fallbackBlock,
            List<SettleTable.Option> pool,
            boolean territorial
    ) {
        boolean isCrowdedAt(LevelReader level, BlockPos center) {
            int radius = (int) Math.ceil(spacing);
            double limit = spacing * spacing;
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -verticalRange; dy <= verticalRange; dy++) {
                        // Exactly `spacing` away is fine; only closer counts (and the spot itself never does).
                        if (dx * dx + dy * dy + dz * dz >= limit || (dx == 0 && dy == 0 && dz == 0)) {
                            continue;
                        }

                        cursor.setWithOffset(center, dx, dy, dz);
                        BlockState state = level.getBlockState(cursor);
                        boolean crowding = territorial
                                ? SettleTable.isAnyPlant(state)
                                : SettleTable.isSameSpecies(state, self, fallbackBlock, pool);
                        if (crowding) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }
    }

    private static final Direction[] HORIZONTAL_FACINGS = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    // Cached once - Direction.values() allocates a fresh array on every call.
    private static final Direction[] ALL_FACINGS = Direction.values();

    // Each candidate that passes the cheap geometric checks costs a full crowding scan, so a single search only
    // pays for a handful before giving up - only ever reached in a genuinely saturated area.
    private static final int MAX_CROWD_SCANS = 8;
    private static final int MAX_RING = SpreadMath.MAX_MANUAL_DISTANCE;
    private static final int MAX_SHELL = 16;
    // Creeping candidates are only valid next to a surface, so far fewer of a shell's cells qualify than of a ring's.
    private static final int SHELL_SAMPLE_FACTOR = 4;

    // Lazily built (offset lists per distance never change): flat [dx, dz, dx, dz...] for rings, [dx, dy, dz...]
    // for shells. Only ever touched from the server thread.
    private static final int[][] RINGS = new int[MAX_RING + 1][];
    private static final int[][] SHELLS = new int[MAX_SHELL + 1][];

    private SpreadSearch() {
    }

    @Nullable
    static Target find(
            ServerLevel level,
            BlockPos origin,
            RandomSource random,
            Block species,
            DiseasedPlantLogic.Shape shape,
            int startRing,
            int maxDistance,
            int verticalRange,
            boolean climbing,
            @Nullable Crowd crowd
    ) {
        boolean creeping = shape == DiseasedPlantLogic.Shape.CREEPING;
        int lastRing = Math.min(maxDistance, creeping ? MAX_SHELL : MAX_RING);
        int samples = Config.FLOWER_SPREAD_ATTEMPTS.getAsInt() * (creeping ? SHELL_SAMPLE_FACTOR : 1);
        int stride = creeping ? 3 : 2;
        BlockState baseState = species.defaultBlockState();
        int scans = 0;

        for (int distance = Math.max(1, startRing); distance <= lastRing; distance++) {
            int[] offsets = creeping ? shell(distance) : ring(distance);
            int count = offsets.length / stride;
            if (count == 0) {
                continue;
            }

            // The two closest rings are always checked completely; farther ones are sampled. A random start plus a
            // step coprime with the count visits distinct cells in a scrambled order without allocating.
            int tries = distance <= 2 ? count : Math.min(count, samples);
            int start = random.nextInt(count);
            int step = coprimeStep(count, random);
            for (int i = 0; i < tries; i++) {
                int index = (int) ((start + (long) i * step) % count) * stride;
                Target candidate = creeping
                        ? creepingAt(level, origin.offset(offsets[index], offsets[index + 1], offsets[index + 2]), random)
                        : followTerrain(level, origin.offset(offsets[index], 0, offsets[index + 1]), baseState, verticalRange, shape, climbing, random);
                if (candidate == null) {
                    continue;
                }

                if (crowd != null) {
                    // The parent is one of the plants the child has to keep its distance from, and that costs nothing
                    // to check - unlike the scan, which is what MAX_CROWD_SCANS is there to bound.
                    if (candidate.pos().distSqr(origin) < crowd.spacing() * crowd.spacing()) {
                        continue;
                    }
                    if (scans >= MAX_CROWD_SCANS) {
                        return null;
                    }
                    scans++;
                    if (crowd.isCrowdedAt(level, candidate.pos())) {
                        continue;
                    }
                }
                return candidate;
            }
        }

        return null;
    }

    // ---- Ring/shell offsets --------------------------------------------------------------------------

    // Cells whose euclidean distance from the origin rounds to `distance`, so the front is round rather than square.
    private static int[] ring(int distance) {
        int[] cached = RINGS[distance];
        if (cached == null) {
            int limit = distance + 1;
            int[] buffer = new int[(2 * limit + 1) * (2 * limit + 1) * 2];
            int size = 0;
            for (int dx = -limit; dx <= limit; dx++) {
                for (int dz = -limit; dz <= limit; dz++) {
                    if (Math.round(Math.sqrt(dx * dx + dz * dz)) == distance) {
                        buffer[size++] = dx;
                        buffer[size++] = dz;
                    }
                }
            }
            cached = Arrays.copyOf(buffer, size);
            RINGS[distance] = cached;
        }
        return cached;
    }

    private static int[] shell(int distance) {
        int[] cached = SHELLS[distance];
        if (cached == null) {
            int limit = distance + 1;
            int side = 2 * limit + 1;
            int[] buffer = new int[side * side * side * 3];
            int size = 0;
            for (int dx = -limit; dx <= limit; dx++) {
                for (int dy = -limit; dy <= limit; dy++) {
                    for (int dz = -limit; dz <= limit; dz++) {
                        if (Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz)) == distance) {
                            buffer[size++] = dx;
                            buffer[size++] = dy;
                            buffer[size++] = dz;
                        }
                    }
                }
            }
            cached = Arrays.copyOf(buffer, size);
            SHELLS[distance] = cached;
        }
        return cached;
    }

    private static int coprimeStep(int count, RandomSource random) {
        if (count <= 2) {
            return 1;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            int step = 1 + random.nextInt(count - 1);
            if (gcd(step, count) == 1) {
                return step;
            }
        }
        return 1;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    // ---- Creeping ------------------------------------------------------------------------------------

    // A creeping plant grabs onto whichever of a free cell's 6 faces finds a solid neighbor.
    @Nullable
    private static Target creepingAt(ServerLevel level, BlockPos cell, RandomSource random) {
        if (!level.isEmptyBlock(cell)) {
            return null;
        }

        Direction face = pickAttachableFace(level, cell, random);
        return face != null ? new Target(cell, face) : null;
    }

    // Random start index, same reasoning as tryFacings below - a block surrounded by solid neighbors on every side
    // shouldn't always end up grabbing the same one.
    @Nullable
    private static Direction pickAttachableFace(ServerLevel level, BlockPos pos, RandomSource random) {
        int startIndex = random.nextInt(ALL_FACINGS.length);
        for (int i = 0; i < ALL_FACINGS.length; i++) {
            Direction direction = ALL_FACINGS[(startIndex + i) % ALL_FACINGS.length];
            BlockPos neighborPos = pos.relative(direction);
            if (MultifaceBlock.canAttachTo(level, direction, neighborPos, level.getBlockState(neighborPos))) {
                return direction;
            }
        }
        return null;
    }

    // ---- Single/tall plants --------------------------------------------------------------------------

    // Slopes/steps mean the target column often isn't level with the parent flower, so this checks nearby heights
    // too (closest to the parent's Y first) instead of only the exact same Y. A TALL shape also needs the cell
    // above free for the second half.
    @Nullable
    private static Target followTerrain(ServerLevel level, BlockPos column, BlockState baseState, int verticalRange, DiseasedPlantLogic.Shape shape, boolean climbing, RandomSource random) {
        Target direct = tryFacings(level, column, baseState, shape, climbing, random);
        if (direct != null) {
            return direct;
        }

        for (int dy = 1; dy <= verticalRange; dy++) {
            Target up = tryFacings(level, column.above(dy), baseState, shape, climbing, random);
            if (up != null) {
                return up;
            }

            Target down = tryFacings(level, column.below(dy), baseState, shape, climbing, random);
            if (down != null) {
                return down;
            }
        }

        return null;
    }

    // TALL never tilts, so it's just the one (UP) check. SINGLE always tries standing upright first; only when this
    // plant is actually configured to climb does it also try each horizontal direction, starting from a random one
    // so a trunk with climbable wood on every side doesn't always end up tilting the same way.
    @Nullable
    private static Target tryFacings(ServerLevel level, BlockPos pos, BlockState baseState, DiseasedPlantLogic.Shape shape, boolean climbing, RandomSource random) {
        if (shape == DiseasedPlantLogic.Shape.TALL) {
            return isValidUpSpot(level, pos, baseState, shape, climbing) ? new Target(pos, Direction.UP) : null;
        }

        BlockState upState = baseState.setValue(PlantSupport.FACING, Direction.UP);
        if (isValidUpSpot(level, pos, upState, shape, climbing)) {
            return new Target(pos, Direction.UP);
        }
        if (!climbing) {
            return null;
        }

        int startIndex = random.nextInt(HORIZONTAL_FACINGS.length);
        for (int i = 0; i < HORIZONTAL_FACINGS.length; i++) {
            Direction facing = HORIZONTAL_FACINGS[(startIndex + i) % HORIZONTAL_FACINGS.length];
            BlockState tiltedState = baseState.setValue(PlantSupport.FACING, facing);
            if (isValidSpot(level, pos, tiltedState, shape)) {
                return new Target(pos, facing);
            }
        }

        return null;
    }

    // Standing upright is unconditionally allowed by canSurvive on top of a climbable block too (see PlantSupport),
    // since an already-existing plant there must never lose canSurvive. But that same leniency would let a bag
    // WITHOUT Twisting Vines incidentally start growing on trees just because the search happened to land there -
    // the player explicitly asked for "no Twisting Vines = ground only, with Twisting Vines = top AND side of
    // organic blocks". So the SEARCH additionally requires climbing to be on before it'll accept a spot that's ONLY
    // valid because of the climbable tag. Ordinary ground is never gated - only ground that needed the climbable
    // exception is.
    private static boolean isValidUpSpot(ServerLevel level, BlockPos pos, BlockState upState, DiseasedPlantLogic.Shape shape, boolean climbing) {
        if (!isValidSpot(level, pos, upState, shape)) {
            return false;
        }
        boolean viaClimbableOnly = PlantSupport.isClimbable(level.getBlockState(pos.below()));
        return climbing || !viaClimbableOnly;
    }

    private static boolean isValidSpot(ServerLevel level, BlockPos pos, BlockState newState, DiseasedPlantLogic.Shape shape) {
        if (!level.isEmptyBlock(pos) || !newState.canSurvive(level, pos)) {
            return false;
        }
        return shape == DiseasedPlantLogic.Shape.SINGLE || level.isEmptyBlock(pos.above());
    }
}
