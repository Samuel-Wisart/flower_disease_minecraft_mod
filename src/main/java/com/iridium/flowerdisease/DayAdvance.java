package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Fast-forwards this mod's plants by whole in-game days without touching the rest of the world: instead of raising
// randomTickSpeed for everything (which would also speed up crops, leaves, fire...), only the plants get their
// random ticks, delivered at exactly the rate vanilla would have delivered them. A day is cut into STEPS_PER_DAY
// sub-steps so that children born early in the day are themselves ticking later in it, the way they would in real
// time. The work is time-sliced to a fixed budget per server tick, so even a huge garden never freezes the game.
// Meant as debug tooling now and as the engine of a future "skip a day" item.
//
// One job at a time (a static, since there's nothing sensible about two overlapping fast-forwards), only ever
// touched from the server thread.
final class DayAdvance {
    static final int STEPS_PER_DAY = 120;
    static final int MAX_DAYS = 30;

    private static final long BUDGET_NANOS = 40_000_000L;
    private static final int PROGRESS_INTERVAL_TICKS = 20;
    private static final int MAX_ERRORS = 20;
    // Above this many expected ticks per sub-step a Poisson draw is replaced by its normal approximation.
    private static final double POISSON_LIMIT = 20.0;

    interface Listener {
        void progress(int step, int totalSteps, int activePlants);

        void finished(Result result);
    }

    record Result(GardenStats before, GardenStats after, int days, long plantTicks, int errors, long workMillis, boolean cancelled) {
    }

    @Nullable
    private static Job job;

    private DayAdvance() {
    }

    static boolean isRunning() {
        return job != null;
    }

    // Returns false when another fast-forward is still in progress. `chunks` is where plants get ticked (unloaded ones
    // are skipped), so callers choose between "around the players" and an explicit area.
    static boolean start(ServerLevel level, List<ChunkPos> chunks, int days, Listener listener) {
        if (job != null) {
            return false;
        }
        job = new Job(level, chunks, days, listener);
        return true;
    }

    static void cancel() {
        if (job != null) {
            job.finish(true);
        }
    }

    // Called once per server tick.
    static void tick() {
        if (job != null) {
            job.run();
        }
    }

    // A stale job would hold on to a level that no longer exists (an integrated server can be restarted in the same
    // JVM), so it's dropped when the server stops.
    static void reset() {
        job = null;
    }

    private static final class Job {
        private final ServerLevel level;
        private final List<ChunkPos> chunks;
        private final Listener listener;
        private final int days;
        private final int totalSteps;
        private final double ticksPerStep;
        private final double poissonLimit;
        private final GardenStats before;
        private final List<BlockPos> active = new ArrayList<>();

        private int step;
        private boolean scanning = true;
        private int chunkCursor;
        private int plantCursor;
        private int ticksSinceReport;
        private long plantTicks;
        private int errors;
        private long workNanos;

        Job(ServerLevel level, List<ChunkPos> chunks, int days, Listener listener) {
            this.level = level;
            this.chunks = chunks;
            this.listener = listener;
            this.days = days;
            this.totalSteps = days * STEPS_PER_DAY;

            // Per block position and per day: the same figure the Garden Bag preview shows. Vanilla gives every
            // block `randomTickSpeed` chances per game tick out of the 4096 positions of its section.
            int randomTickSpeed = level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING);
            this.ticksPerStep = 24000.0 * randomTickSpeed / 4096.0 / STEPS_PER_DAY;
            this.poissonLimit = Math.exp(-ticksPerStep);
            this.before = GardenStats.collect(level, chunks);
        }

        void run() {
            long start = System.nanoTime();
            long deadline = start + BUDGET_NANOS;
            while (true) {
                if (!advance()) {
                    return;
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            workNanos += System.nanoTime() - start;

            if (++ticksSinceReport >= PROGRESS_INTERVAL_TICKS) {
                ticksSinceReport = 0;
                listener.progress(step, totalSteps, active.size());
            }
        }

        // One small unit of work: scan a chunk, or tick a plant. Returns false once the job is over.
        private boolean advance() {
            if (scanning) {
                if (chunkCursor < chunks.size()) {
                    scan(chunks.get(chunkCursor++));
                } else {
                    scanning = false;
                    plantCursor = 0;
                }
                return true;
            }

            if (plantCursor < active.size()) {
                tickPlant(active.get(plantCursor++));
                if (errors >= MAX_ERRORS) {
                    // Something is badly wrong (the log has the details) - stop instead of flooding it.
                    finish(true);
                    return false;
                }
                return true;
            }

            step++;
            if (step >= totalSteps) {
                finish(false);
                return false;
            }
            scanning = true;
            chunkCursor = 0;
            active.clear();
            return true;
        }

        private void scan(ChunkPos chunk) {
            GardenScan.forEachPlantBlock(level, chunk, (pos, state) -> {
                boolean upperHalf = state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER;
                if (state.isRandomlyTicking() && !upperHalf) {
                    active.add(pos.immutable());
                }
            });
        }

        private void tickPlant(BlockPos pos) {
            RandomSource random = level.getRandom();
            BlockState state = level.getBlockState(pos);
            int ticks = ticksFor(random);
            for (int i = 0; i < ticks && GardenScan.isPlantBlock(state) && state.isRandomlyTicking(); i++) {
                try {
                    state.randomTick(level, pos, random);
                    plantTicks++;
                } catch (RuntimeException e) {
                    errors++;
                    FlowerDisease.LOGGER.error("Flower Disease day simulation: exception ticking {} at {}", state, pos, e);
                    return;
                }
                // A tick can settle the plant or replace it altogether.
                state = level.getBlockState(pos);
            }
        }

        // How many random ticks a plant receives in one sub-step: Poisson distributed, like the real thing.
        private int ticksFor(RandomSource random) {
            if (ticksPerStep >= POISSON_LIMIT) {
                return Math.max(0, (int) Math.round(ticksPerStep + Math.sqrt(ticksPerStep) * random.nextGaussian()));
            }

            int count = 0;
            double product = random.nextDouble();
            while (product > poissonLimit) {
                count++;
                product *= random.nextDouble();
            }
            return count;
        }

        void finish(boolean cancelled) {
            job = null;
            GardenStats after = GardenStats.collect(level, chunks);
            listener.finished(new Result(before, after, days, plantTicks, errors, workNanos / 1_000_000L, cancelled));
        }
    }
}
