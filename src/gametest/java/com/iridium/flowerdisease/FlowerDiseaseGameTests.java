package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

// Headless checks of the plant life cycle, run with "./gradlew runGameTestServer" (dev environment only - the
// registration hook doesn't exist in a production game). They drive the real game logic in a real server world:
// planting through the Garden Bag's own code, random-ticking plants directly, and fast-forwarding days with the same
// DayAdvance the /diseasedflower day command uses. Every test also ends by checking structural invariants (nothing
// floating, no half a tall plant, every block entity present) because those are the bugs that only show up as a
// crash or a ghost block in somebody's world.
//
// Results worth eyeballing are logged with a "[GT]" prefix, including an ASCII map of the garden.
@GameTestHolder(FlowerDisease.MODID)
@PrefixGameTestTemplate(false)
public final class FlowerDiseaseGameTests {
    private static final String TEMPLATE = "arena";
    private static final int SIZE = 48;
    private static final int HEIGHT = 20;
    // Chunks kept loaded around the arena so that plants near its edge still find their whole search area loaded.
    private static final int MARGIN_CHUNKS = 2;
    private static final int LONG_TIMEOUT = 12000;

    private FlowerDiseaseGameTests() {
    }

    // ---- Tests ---------------------------------------------------------------------------------------

    @GameTest(template = TEMPLATE, batch = "gt_burst", timeoutTicks = 200)
    public static void plantingBurstCreatesAFewGenerations(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            burst(helper, arena);
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    private static void burst(GameTestHelper helper, Arena arena) {
        Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2);
        BlockPos root = arena.at(24, 1, 24);
        Component failure = GardenBagItem.plant(arena.level, root, bag.stack(), Direction.UP);
        helper.assertTrue(failure == null, "planting failed: " + failure);

        List<BlockPos> plants = arena.plantPositions();
        log("burst: " + plants.size() + " plants after planting\n" + arena.map());
        helper.assertTrue(plants.size() >= 2, "expected the burst to create children, found " + plants.size() + " plants");
        helper.assertTrue(plants.size() <= 1 + Config.FLOWER_BURST_MAX_PLANTS.getAsInt(), "burst exceeded its plant budget: " + plants.size());

        GardenBagContents rootProfile = bag.contents();
        for (BlockPos pos : plants) {
            SpreadProfileBlockEntity plant = DiseasedPlantLogic.profileAt(arena.level, pos);
            helper.assertTrue(plant != null, "plant at " + pos + " has no block entity");
            helper.assertTrue(plant.profile().equals(rootProfile), "child profile differs from the root's at " + pos);
            boolean isRoot = pos.equals(root);
            helper.assertTrue(isRoot ? plant.depth() == 0 : plant.depth() >= 1 && plant.depth() <= Config.FLOWER_BURST_GENERATIONS.getAsInt(),
                    "unexpected depth " + plant.depth() + " at " + pos);
        }

        failOnProblems(arena.validate());
    }

    @GameTest(template = TEMPLATE, batch = "gt_lifetime", timeoutTicks = 400)
    public static void lifetimeMatchesTheRabbitsFootCount(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            lifetime(helper, arena);
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    private static void lifetime(GameTestHelper helper, Arena arena) {
        // Slime x256 makes the density so high that room is never the limit, so what ends each plant's life is
        // the lifetime test alone.
        int lifetime = 4;
        Bag bag = new Bag().add(Items.POPPY, 1).add(Items.RABBIT_FOOT, lifetime).add(Items.SLIME_BALL, 256);
        GardenBagContents contents = bag.contents();
        RandomSource random = arena.level.getRandom();

        int trials = 0;
        long children = 0;
        for (int round = 0; round < 40; round++) {
            for (int gx = 0; gx < 6; gx++) {
                for (int gz = 0; gz < 6; gz++) {
                    BlockPos root = arena.at(4 + gx * 8, 1, 4 + gz * 8);
                    placeRoot(arena.level, root, FlowerDisease.DISEASED_POPPY.get(), contents);
                    tickUntilSettled(arena.level, root, random);
                    children += arena.countPlantsAround(root, 4) - 1;
                    trials++;
                    arena.clearAround(root, 4);
                }
            }
        }

        double mean = (double) children / trials;
        log(String.format(Locale.ROOT, "lifetime: %d trials, mean children %.2f (Rabbit's Foot x%d)", trials, mean, lifetime));
        helper.assertTrue(mean > lifetime * 0.8 && mean < lifetime * 1.2, "mean children " + mean + " is far from " + lifetime);
    }

    @GameTest(template = TEMPLATE, batch = "gt_moss", timeoutTicks = 600)
    public static void mossBlocksCorruptTheGroundWhenPlantsSettle(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            moss(helper, arena);
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    private static void moss(GameTestHelper helper, Arena arena) {
        // One generation of budget: the root reproduces, its children are born already settled, and with 64 Moss
        // Blocks every settling plant converts the block it stands on.
        // Rabbit's Foot x1000 keeps the root from settling on its very first tick (a 1 in 9 chance at the default
        // lifetime), which would leave nothing to check.
        Bag bag = new Bag().add(Items.POPPY, 1).add(Items.MOSS_BLOCK, 64).add(Items.BONE_MEAL, 1).add(Items.RABBIT_FOOT, 1000);
        RandomSource random = arena.level.getRandom();
        BlockPos root = arena.at(24, 1, 24);
        placeRoot(arena.level, root, FlowerDisease.DISEASED_POPPY.get(), bag.contents());
        tickUntilSettled(arena.level, root, random);

        int plants = arena.countPlantsAround(root, 8);
        int flowerBlocks = arena.countFlowerBlocks();
        log("moss: " + plants + " plants, " + flowerBlocks + " flower blocks after the root settled\n" + arena.map());
        helper.assertTrue(plants >= 2, "the root should have reproduced at least once");
        helper.assertTrue(flowerBlocks == plants, "every settled plant should have corrupted its ground: " + plants + " plants, " + flowerBlocks + " flower blocks");

        // Let the flower blocks spread on their own until they all settle.
        int guard = 0;
        while (guard++ < 20_000) {
            List<BlockPos> active = arena.activeFlowerBlocks();
            if (active.isEmpty()) {
                break;
            }
            for (BlockPos pos : active) {
                arena.level.getBlockState(pos).randomTick(arena.level, pos, random);
            }
        }
        helper.assertTrue(arena.activeFlowerBlocks().isEmpty(), "flower blocks never settled");

        int finalBlocks = arena.countFlowerBlocks();
        log("moss: " + finalBlocks + " flower blocks after they spread\n" + arena.map());
        for (BlockPos pos : arena.flowerBlockPositions()) {
            FlowerMassBlockEntity entity = arena.level.getBlockEntity(pos) instanceof FlowerMassBlockEntity e ? e : null;
            helper.assertTrue(entity != null, "flower block at " + pos + " has no block entity");
            helper.assertTrue(entity.replacedState().is(Blocks.GRASS_BLOCK), "flower block at " + pos + " remembers " + entity.replacedState());
        }

        failOnProblems(arena.validate());
    }

    @GameTest(template = TEMPLATE, batch = "gt_growth_default", timeoutTicks = LONG_TIMEOUT)
    public static void defaultGardenGrowsOverThreeDays(GameTestHelper helper) {
        growth(helper, "default", new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2), 3, 16);
    }

    @GameTest(template = TEMPLATE, batch = "gt_growth_sparse", timeoutTicks = LONG_TIMEOUT)
    public static void sparseGardenGrowsOverThreeDays(GameTestHelper helper) {
        growth(helper, "sparse (Slime x4)", new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.SLIME_BALL, 4), 3, 4);
    }

    @GameTest(template = TEMPLATE, batch = "gt_growth_dense", timeoutTicks = LONG_TIMEOUT)
    public static void denseGardenGrowsOverThreeDays(GameTestHelper helper) {
        growth(helper, "dense (Slime x64)", new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.SLIME_BALL, 64), 3, 64);
    }

    @GameTest(template = TEMPLATE, batch = "gt_smoke", timeoutTicks = LONG_TIMEOUT)
    public static void tallCreepingAndClimbingSmokeTest(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();

        // A stone wall for the creepers and a few tree trunks with a leaf crown for the tilted flowers.
        for (int x = 14; x <= 34; x++) {
            for (int y = 1; y <= 6; y++) {
                arena.level.setBlock(arena.at(x, y, 12), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        for (int trunk = 0; trunk < 3; trunk++) {
            int x = 18 + trunk * 6;
            for (int y = 1; y <= 7; y++) {
                arena.level.setBlock(arena.at(x, y, 30), Blocks.OAK_LOG.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    arena.level.setBlock(arena.at(x + dx, 8, 30 + dz), Blocks.OAK_LEAVES.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }

        Bag bag = new Bag()
                .add(Items.SUNFLOWER, 2)
                .add(Items.POPPY, 2)
                .add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 2)
                .add(Items.TWISTING_VINES, 1)
                .add(Items.MOSS_BLOCK, 64);
        ensureRandomTickSpeed(arena.level);
        // Plants a few blocks from both the wall and the trunks.
        BlockPos root = arena.at(24, 1, 21);
        Component failure = GardenBagItem.plant(arena.level, root, bag.stack(), Direction.UP);
        helper.assertTrue(failure == null, "planting failed: " + failure);

        Simulation simulation = Simulation.start(arena, 3);
        helper.assertTrue(simulation != null, "a day simulation was already running");

        helper.succeedWhen(() -> {
            helper.assertTrue(simulation.result != null, "simulation still running");
            if (!simulation.reported) {
                simulation.reported = true;
                log("smoke: " + describe(simulation.result) + "\n" + arena.map() + "\n" + arena.shapeCounts());
                List<String> problems = arena.validate();
                arena.cleanup();
                simulation.problems = problems;
            }
            failOnProblems(simulation.problems);
            if (simulation.result.errors() > 0) {
                throw new IllegalStateException(simulation.result.errors() + " exceptions while ticking plants (see the log)");
            }
        });
    }

    @GameTest(template = TEMPLATE, batch = "gt_nbt", timeoutTicks = 100)
    public static void profilesSurviveSavingAndOldKeysStillLoad(GameTestHelper helper) {
        Bag bag = new Bag()
                .add(Items.POPPY, 3).add(Items.DANDELION, 1).add(Items.SCULK, 5).add(Items.NETHER_STAR, 1).add(Items.RABBIT_FOOT, 6)
                .add(Items.SLIME_BALL, 8).add(Items.FEATHER, 9).add(Items.FERMENTED_SPIDER_EYE, 1).add(Items.TWISTING_VINES, 1)
                .add(Items.MOSS_BLOCK, 12).add(Items.BONE_MEAL, 7);
        GardenBagContents original = bag.contents();

        CompoundTag tag = new CompoundTag();
        original.writeTo(tag);
        helper.assertTrue(GardenBagContents.readFrom(tag).equals(original), "a profile did not survive writeTo/readFrom: " + original);

        CompoundTag empty = new CompoundTag();
        GardenBagContents.DEFAULT.writeTo(empty);
        helper.assertTrue(empty.isEmpty(), "the default profile should write nothing, wrote " + empty);
        helper.assertTrue(GardenBagContents.readFrom(empty).equals(GardenBagContents.DEFAULT), "an empty tag should read back as the default profile");

        // The keys written by versions before the life cycle redesign.
        CompoundTag legacy = new CompoundTag();
        legacy.putLong("GenerationsRemaining", 3);
        legacy.putDouble("SpreadChanceOverride", 0.5);
        legacy.putInt("SpreadDistanceOverride", 7);
        legacy.putInt("DensityTargetPer16x16", 9);
        legacy.putBoolean("RespectAllSpecies", false);
        legacy.putBoolean("SpawnsFlowerBlocks", true);
        legacy.putBoolean("Climbing", true);
        ListTag species = new ListTag();
        species.add(StringTag.valueOf("minecraft:poppy 3"));
        legacy.put("SpeciesWeights", species);
        GardenBagContents loaded = GardenBagContents.readFrom(legacy);
        helper.assertTrue(loaded.generations() == 3 && loaded.spreadChance() == 0.5 && loaded.spreadDistance() == 7 && loaded.densityPer16x16() == 9
                        && !loaded.respectAllSpecies() && loaded.mossBlocks() == 1 && loaded.climbing() && loaded.speciesWeights().equals(List.of("minecraft:poppy 3")),
                "legacy keys read as " + loaded);

        // And through a real block entity, depth included.
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            BlockPos pos = arena.at(10, 1, 10);
            placeRoot(arena.level, pos, FlowerDisease.DISEASED_POPPY.get(), original);
            SpreadProfileBlockEntity plant = DiseasedPlantLogic.profileAt(arena.level, pos);
            helper.assertTrue(plant != null, "no block entity on the placed root");
            plant.inherit(original, 42);

            CompoundTag saved = plant.saveWithFullMetadata(arena.level.registryAccess());
            BlockEntity reloaded = BlockEntity.loadStatic(pos, arena.level.getBlockState(pos), saved, arena.level.registryAccess());
            helper.assertTrue(reloaded instanceof SpreadProfileBlockEntity, "the block entity did not reload");
            SpreadProfileBlockEntity copy = (SpreadProfileBlockEntity) reloaded;
            helper.assertTrue(copy.depth() == 42 && copy.profile().equals(original), "block entity reloaded as depth " + copy.depth() + ", " + copy.profile());
            log("nbt: a plant with every modifier set saves as " + GardenStats.serializedSize(saved) + " bytes");
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, batch = "gt_creeper_plant", timeoutTicks = 200)
    public static void creepersCanBePlantedOnTheGroundAndOnWalls(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1);

            // Clicking the top of the ground: the creeper grabs the block below it.
            BlockPos onGround = arena.at(10, 1, 10);
            helper.assertTrue(GardenBagItem.plant(arena.level, onGround, bag.stack(), Direction.UP) == null, "a creeper could not be planted on the ground");
            helper.assertTrue(arena.level.getBlockState(onGround).getBlock() instanceof CreepingFlowerBlock, "no creeper at " + onGround);

            // Clicking the west face of a wall: the creeper grabs the wall behind it.
            arena.level.setBlock(arena.at(30, 1, 24), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockPos besideWall = arena.at(29, 1, 24);
            helper.assertTrue(GardenBagItem.plant(arena.level, besideWall, bag.stack(), Direction.WEST) == null, "a creeper could not be planted on a wall");
            helper.assertTrue(arena.level.getBlockState(besideWall).getBlock() instanceof CreepingFlowerBlock, "no creeper at " + besideWall);

            // Clicking the underside of a ceiling: hangs from it.
            arena.level.setBlock(arena.at(20, 5, 20), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockPos underCeiling = arena.at(20, 4, 20);
            helper.assertTrue(GardenBagItem.plant(arena.level, underCeiling, bag.stack(), Direction.DOWN) == null, "a creeper could not be planted on a ceiling");

            failOnProblems(arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // How fast a default garden actually spreads: the front radius after each simulated day, on ground that never
    // runs out. The design notes (about a minute between attempts at generation 0, slowing down with depth) predict
    // a front that keeps advancing but keeps slowing.
    @GameTest(template = TEMPLATE, batch = "gt_timeline", timeoutTicks = LONG_TIMEOUT)
    public static void gardenFrontAdvancesDayByDay(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        // Six chunks of margin all around, so the front has about 120 blocks to travel before it meets the edge.
        arena.prepare(true, 6);
        ensureRandomTickSpeed(arena.level);

        BlockPos root = arena.at(24, 1, 24);
        Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2);
        Component failure = GardenBagItem.plant(arena.level, root, bag.stack(), Direction.UP);
        helper.assertTrue(failure == null, "planting failed: " + failure);

        int totalDays = 8;
        int[] day = {0};
        Simulation[] current = {Simulation.start(arena, 1)};
        helper.assertTrue(current[0] != null, "a day simulation was already running");

        helper.succeedWhen(() -> {
            helper.assertTrue(current[0].result != null, "day " + (day[0] + 1) + " still running");
            day[0]++;
            DayAdvance.Result result = current[0].result;
            List<BlockPos> plants = arena.plantPositionsInRegion();
            double radius = 0;
            for (BlockPos plant : plants) {
                radius = Math.max(radius, Math.sqrt(plant.distSqr(root)));
            }
            log(String.format(Locale.ROOT, "timeline day %d: %d plants, front radius %.1f, %d still active (mean depth %.1f, max %d), %d ms",
                    day[0], plants.size(), radius, result.after().active, result.after().meanDepth(), result.after().maxDepth, result.workMillis()));
            if (result.errors() > 0) {
                throw new IllegalStateException(result.errors() + " exceptions while ticking plants (see the log)");
            }

            if (day[0] < totalDays) {
                current[0] = Simulation.start(arena, 1);
                helper.assertTrue(current[0] != null, "could not start the next day");
                helper.assertTrue(false, "day " + day[0] + " done, next one started");
            }
            arena.cleanup();
        });
    }

    // ---- Shared scenarios ----------------------------------------------------------------------------

    private static void growth(GameTestHelper helper, String label, Bag bag, int days, int density) {
        Arena arena = new Arena(helper);
        arena.prepare();
        ensureRandomTickSpeed(arena.level);

        BlockPos root = arena.at(24, 1, 24);
        Component failure = GardenBagItem.plant(arena.level, root, bag.stack(), Direction.UP);
        helper.assertTrue(failure == null, "planting failed: " + failure);
        int afterBurst = arena.countPlants();

        Simulation simulation = Simulation.start(arena, days);
        helper.assertTrue(simulation != null, "a day simulation was already running");

        helper.succeedWhen(() -> {
            helper.assertTrue(simulation.result != null, "simulation still running");
            if (!simulation.reported) {
                simulation.reported = true;
                int window = SpreadMath.windowRadius(SpreadMath.resolveDensity(density));
                log("growth [" + label + "]: " + afterBurst + " plants after the burst, " + arena.countPlants() + " after " + days
                        + " days; " + describe(simulation.result) + "\n" + arena.densityReport(root, window, SpreadMath.crowdLimit(density, window)) + "\n" + arena.map());
                List<String> problems = arena.validate();
                arena.cleanup();
                simulation.problems = problems;
            }
            failOnProblems(simulation.problems);
            if (simulation.result.errors() > 0) {
                throw new IllegalStateException(simulation.result.errors() + " exceptions while ticking plants (see the log)");
            }
        });
    }

    // The same summary /diseasedflower day gives, so the command's text is exercised too.
    private static String describe(DayAdvance.Result result) {
        return result.days() + " days simulated: " + result.plantTicks() + " plant ticks, " + result.workMillis() + " ms, "
                + result.errors() + " errors\n" + String.join("\n", result.after().describe(result.before()));
    }

    private static void failOnProblems(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new IllegalStateException(problems.size() + " invariant violations, first: " + problems.subList(0, Math.min(5, problems.size())));
        }
    }

    private static void ensureRandomTickSpeed(ServerLevel level) {
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(3, level.getServer());
    }

    private static void log(String message) {
        FlowerDisease.LOGGER.info("[GT] {}", message);
    }

    // Places a root exactly like the Garden Bag does, minus the burst - for tests that want to watch one plant alone.
    private static void placeRoot(ServerLevel level, BlockPos pos, Block block, GardenBagContents contents) {
        level.setBlock(pos, block.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
        if (level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity plant) {
            plant.configure(contents);
        }
    }

    private static void tickUntilSettled(ServerLevel level, BlockPos pos, RandomSource random) {
        BlockState state = level.getBlockState(pos);
        for (int guard = 0; guard < 10_000 && state.isRandomlyTicking(); guard++) {
            state.randomTick(level, pos, random);
            state = level.getBlockState(pos);
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------------

    // A day simulation over the whole arena, with its outcome filled in by DayAdvance's listener.
    private static final class Simulation {
        DayAdvance.Result result;
        boolean reported;
        List<String> problems = List.of();

        static Simulation start(Arena arena, int days) {
            Simulation simulation = new Simulation();
            boolean started = DayAdvance.start(arena.level, arena.chunks, days, new DayAdvance.Listener() {
                @Override
                public void progress(int step, int totalSteps, int activePlants) {
                }

                @Override
                public void finished(DayAdvance.Result result) {
                    simulation.result = result;
                }
            });
            return started ? simulation : null;
        }
    }

    // What goes in a Garden Bag, without going through a menu.
    private static final class Bag {
        private final List<ItemStack> items = new ArrayList<>();

        Bag add(Item item, int count) {
            for (int left = count; left > 0; left -= 64) {
                items.add(new ItemStack(item, Math.min(left, 64)));
            }
            return this;
        }

        GardenBagContents contents() {
            return GardenBagContents.read(items);
        }

        ItemStack stack() {
            ItemStack bag = new ItemStack(FlowerDisease.GARDEN_BAG.get());
            bag.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
            return bag;
        }
    }

    // A SIZE x HEIGHT x SIZE box of test world with grass on its floor layer and the chunks around it kept loaded.
    private static final class Arena {
        final ServerLevel level;
        final BlockPos origin;
        final List<ChunkPos> chunks = new ArrayList<>();

        Arena(GameTestHelper helper) {
            this.level = helper.getLevel();
            this.origin = helper.absolutePos(BlockPos.ZERO);
        }

        BlockPos at(int x, int y, int z) {
            return origin.offset(x, y, z);
        }

        void prepare() {
            prepare(false, MARGIN_CHUNKS);
        }

        // `wideFloor` extends the grass over every loaded chunk, for tests that want the ground never to run out.
        void prepare(boolean wideFloor, int marginChunks) {
            ChunkPos min = new ChunkPos(origin);
            ChunkPos max = new ChunkPos(origin.offset(SIZE - 1, 0, SIZE - 1));
            for (int cx = min.x - marginChunks; cx <= max.x + marginChunks; cx++) {
                for (int cz = min.z - marginChunks; cz <= max.z + marginChunks; cz++) {
                    level.setChunkForced(cx, cz, true);
                    level.getChunk(cx, cz);
                    chunks.add(new ChunkPos(cx, cz));
                }
            }

            BlockState air = Blocks.AIR.defaultBlockState();
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = 1; y < HEIGHT; y++) {
                        BlockPos pos = at(x, y, z);
                        if (!level.getBlockState(pos).isAir()) {
                            level.setBlock(pos, air, Block.UPDATE_CLIENTS);
                        }
                    }
                }
            }

            if (wideFloor) {
                for (ChunkPos chunk : chunks) {
                    for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                        for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                            level.setBlock(new BlockPos(x, origin.getY(), z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                        }
                    }
                }
            } else {
                for (int x = 0; x < SIZE; x++) {
                    for (int z = 0; z < SIZE; z++) {
                        level.setBlock(at(x, 0, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }

        // Removes everything this mod put in the world (and the vanilla flowers its plants settled into) across the
        // loaded area, so a test never leaks plants into the next one, then lets go of the chunks.
        void cleanup() {
            List<BlockPos> ours = new ArrayList<>();
            for (ChunkPos chunk : chunks) {
                GardenScan.forEachPlantBlock(level, chunk, (pos, state) -> ours.add(pos.immutable()));
            }
            for (BlockPos pos : ours) {
                BlockState state = level.getBlockState(pos);
                BlockState restored = state.getBlock() instanceof FlowerMassBlock && level.getBlockEntity(pos) instanceof FlowerMassBlockEntity flowerBlock
                        ? flowerBlock.replacedState()
                        : Blocks.AIR.defaultBlockState();
                level.setBlock(pos, restored, SettleTable.PLACEMENT_FLAGS);
            }

            int minX = chunks.stream().mapToInt(ChunkPos::getMinBlockX).min().orElse(0);
            int maxX = chunks.stream().mapToInt(ChunkPos::getMaxBlockX).max().orElse(0);
            int minZ = chunks.stream().mapToInt(ChunkPos::getMinBlockZ).min().orElse(0);
            int maxZ = chunks.stream().mapToInt(ChunkPos::getMaxBlockZ).max().orElse(0);
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = origin.getY() - 3; y < origin.getY() + HEIGHT; y++) {
                        cursor.set(x, y, z);
                        if (SettleTable.isAnyPlant(level.getBlockState(cursor)) || level.getBlockState(cursor).getBlock() instanceof DoublePlantBlock) {
                            level.setBlock(cursor, Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                        }
                    }
                }
            }

            for (ChunkPos chunk : chunks) {
                level.setChunkForced(chunk.x, chunk.z, false);
            }
        }

        // ---- Queries ----

        boolean isPlant(BlockState state) {
            return SettleTable.isAnyPlant(state);
        }

        // Every plant of any kind in the arena (upper halves of tall plants excluded), as positions.
        List<BlockPos> plantPositions() {
            List<BlockPos> plants = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = 1; y < HEIGHT; y++) {
                        BlockPos pos = at(x, y, z);
                        if (isPlant(level.getBlockState(pos))) {
                            plants.add(pos);
                        }
                    }
                }
            }
            return plants;
        }

        int countPlants() {
            return plantPositions().size();
        }

        // Same as plantPositions() but over every loaded chunk instead of just the arena's box.
        List<BlockPos> plantPositionsInRegion() {
            int minX = chunks.stream().mapToInt(ChunkPos::getMinBlockX).min().orElse(0);
            int maxX = chunks.stream().mapToInt(ChunkPos::getMaxBlockX).max().orElse(0);
            int minZ = chunks.stream().mapToInt(ChunkPos::getMinBlockZ).min().orElse(0);
            int maxZ = chunks.stream().mapToInt(ChunkPos::getMaxBlockZ).max().orElse(0);

            List<BlockPos> plants = new ArrayList<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    for (int y = origin.getY() + 1; y < origin.getY() + HEIGHT; y++) {
                        cursor.set(x, y, z);
                        if (isPlant(level.getBlockState(cursor))) {
                            plants.add(cursor.immutable());
                        }
                    }
                }
            }
            return plants;
        }

        // Plants within `radius` blocks horizontally and up to 3 up, counting the plant at `center` itself.
        int countPlantsAround(BlockPos center, int radius) {
            int count = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -1; dy <= 3; dy++) {
                        if (isPlant(level.getBlockState(center.offset(dx, dy, dz)))) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        void clearAround(BlockPos center, int radius) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -1; dy <= 3; dy++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (isPlant(level.getBlockState(pos)) || level.getBlockState(pos).getBlock() instanceof DoublePlantBlock) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                        }
                    }
                }
            }
        }

        List<BlockPos> flowerBlockPositions() {
            List<BlockPos> result = new ArrayList<>();
            for (ChunkPos chunk : chunks) {
                GardenScan.forEachPlantBlock(level, chunk, (pos, state) -> {
                    if (state.getBlock() instanceof FlowerMassBlock) {
                        result.add(pos.immutable());
                    }
                });
            }
            return result;
        }

        int countFlowerBlocks() {
            return flowerBlockPositions().size();
        }

        List<BlockPos> activeFlowerBlocks() {
            List<BlockPos> result = new ArrayList<>();
            for (BlockPos pos : flowerBlockPositions()) {
                if (level.getBlockState(pos).isRandomlyTicking()) {
                    result.add(pos);
                }
            }
            return result;
        }

        // ---- Reports ----

        // One character per column of the box the plants occupy: o = active Diseased plant, s = settled in place,
        // v = plain vanilla flower, T/t = tall (Diseased/vanilla), c = creeper piece, / = tilted plant, # = flower block.
        String map() {
            int minX = SIZE, maxX = -1, minZ = SIZE, maxZ = -1;
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    if (columnChar(x, z) != '.') {
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minZ = Math.min(minZ, z);
                        maxZ = Math.max(maxZ, z);
                    }
                }
            }
            if (maxX < 0) {
                return "(no plants)";
            }

            StringBuilder builder = new StringBuilder();
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    builder.append(columnChar(x, z));
                }
                builder.append('\n');
            }
            return builder.toString();
        }

        private char columnChar(int x, int z) {
            char found = '.';
            for (int y = 0; y < HEIGHT; y++) {
                BlockState state = level.getBlockState(at(x, y, z));
                Block block = state.getBlock();
                if (block instanceof FlowerMassBlock) {
                    found = found == '.' ? '#' : found;
                } else if (GardenScan.isPlantBlock(state)) {
                    if (block instanceof CreepingFlowerBlock) {
                        return 'c';
                    }
                    if (state.hasProperty(DoublePlantBlock.HALF)) {
                        return 'T';
                    }
                    if (state.hasProperty(PlantSupport.FACING) && state.getValue(PlantSupport.FACING) != Direction.UP) {
                        return '/';
                    }
                    return DiseasedPlantLogic.isSettled(state) ? 's' : 'o';
                } else if (block instanceof DoublePlantBlock) {
                    return 't';
                } else if (block instanceof BushBlock && found == '.') {
                    found = 'v';
                }
            }
            return found;
        }

        String shapeCounts() {
            int tilted = 0, creepers = 0, tall = 0, single = 0, vanilla = 0;
            for (ChunkPos chunk : chunks) {
                int[] counts = new int[4];
                GardenScan.forEachPlantBlock(level, chunk, (pos, state) -> {
                    if (state.getBlock() instanceof FlowerMassBlock) {
                        return;
                    }
                    if (state.getBlock() instanceof CreepingFlowerBlock) {
                        counts[0]++;
                    } else if (state.hasProperty(DoublePlantBlock.HALF)) {
                        if (state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER) {
                            counts[1]++;
                        }
                    } else if (state.hasProperty(PlantSupport.FACING) && state.getValue(PlantSupport.FACING) != Direction.UP) {
                        counts[2]++;
                    } else {
                        counts[3]++;
                    }
                });
                creepers += counts[0];
                tall += counts[1];
                tilted += counts[2];
                single += counts[3];
            }
            for (BlockPos pos : plantPositions()) {
                if (!GardenScan.isPlantBlock(level.getBlockState(pos))) {
                    vanilla++;
                }
            }
            return "diseased shapes: " + single + " upright, " + tilted + " tilted, " + tall + " tall, " + creepers + " creeper pieces; "
                    + vanilla + " plain vanilla flowers; " + countFlowerBlocks() + " flower blocks";
        }

        // How many other plants each plant sees in the crowding window, against the limit it was supposed to respect.
        String densityReport(BlockPos root, int radius, int limit) {
            List<BlockPos> plants = plantPositions();
            int max = 0;
            long sum = 0;
            double farthest = 0;
            for (BlockPos plant : plants) {
                int count = 0;
                for (BlockPos other : plants) {
                    if (other != plant && Math.abs(other.getX() - plant.getX()) <= radius && Math.abs(other.getZ() - plant.getZ()) <= radius) {
                        count++;
                    }
                }
                max = Math.max(max, count);
                sum += count;
                farthest = Math.max(farthest, Math.sqrt(plant.distSqr(root)));
            }
            return String.format(Locale.ROOT, "%d plants, window %dx%d limit %d: mean neighbors %.1f, max %d, farthest %.1f blocks from the root",
                    plants.size(), 2 * radius + 1, 2 * radius + 1, limit, plants.isEmpty() ? 0.0 : (double) sum / plants.size(), max, farthest);
        }

        // ---- Invariants ----

        // What must hold no matter how the plants got there: a plant can survive where it stands, a tall plant is
        // whole, and every Diseased block carries its profile.
        List<String> validate() {
            List<String> problems = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = 0; y < HEIGHT; y++) {
                        BlockPos pos = at(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        Block block = state.getBlock();

                        if (block instanceof DoublePlantBlock) {
                            DoubleBlockHalf half = state.getValue(DoublePlantBlock.HALF);
                            BlockState other = level.getBlockState(half == DoubleBlockHalf.LOWER ? pos.above() : pos.below());
                            if (!other.is(block) || other.getValue(DoublePlantBlock.HALF) == half) {
                                problems.add("unpaired tall half at " + pos + ": " + state + " next to " + other);
                            }
                        }
                        if (block instanceof FlowerMassBlock) {
                            if (!(level.getBlockEntity(pos) instanceof FlowerMassBlockEntity)) {
                                problems.add("flower block without block entity at " + pos);
                            }
                        } else if (GardenScan.isPlantBlock(state)) {
                            if (!state.canSurvive(level, pos)) {
                                problems.add("Diseased plant that cannot survive at " + pos + ": " + state);
                            }
                            if (!(level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity)) {
                                problems.add("Diseased plant without block entity at " + pos + ": " + state);
                            }
                        } else if (block instanceof BushBlock && !state.canSurvive(level, pos)) {
                            problems.add("vanilla plant that cannot survive at " + pos + ": " + state);
                        }
                    }
                }
            }
            return problems;
        }
    }
}
