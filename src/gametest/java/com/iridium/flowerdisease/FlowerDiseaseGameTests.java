package com.iridium.flowerdisease;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
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
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.AABB;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.LongArrayList;
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
    // How many chunks beyond the ones kept loaded are swept for leftovers, before and after each test.
    private static final int SWEEP_RING = 3;
    // The lifetime the density of a garden was calibrated at (SpreadMath's spacing scale), which the growth measurements pin: at the
    // server's default of 2 half of all lineages die out on their own, and a garden fills the arena only when it is lucky.
    private static final int MEASURED_LIFETIME = 8;

    private FlowerDiseaseGameTests() {
    }

    // ---- Tests ---------------------------------------------------------------------------------------

    // The planted flower is generation 1; the burst is generation 2 - everything the planted flower would have spawned
    // over its whole life, all at once. It then has nothing left to do, so it settles, and only its children (still
    // generation 2, the burst's last) stay active. A lifetime of 1000 keeps the lifetime test from ending the root's
    // life before it has a single child, so what limits it is the room around it.
    @GameTest(template = TEMPLATE, batch = "gt_burst", timeoutTicks = 200)
    public static void plantingBurstIsTheWholeSecondGeneration(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(1000);
            BlockPos root = arena.at(24, 1, 24);
            plantVia(helper, arena, bag, root);

            List<BlockPos> plants = arena.plantPositions();
            log("burst: " + plants.size() + " plants after planting\n" + arena.map());
            helper.assertTrue(plants.size() >= 2, "expected the burst to create children, found " + plants.size() + " plants");
            helper.assertTrue(plants.size() <= 1 + Config.FLOWER_BURST_MAX_PLANTS.getAsInt(), "burst exceeded its plant budget: " + plants.size());
            helper.assertTrue(!GardenScan.isPlantBlock(arena.level.getBlockState(root)), "the planted flower should have settled after living its life");

            for (BlockPos pos : plants) {
                if (pos.equals(root)) {
                    continue;
                }
                SpreadProfileBlockEntity plant = DiseasedPlantLogic.profileAt(arena.level, pos);
                helper.assertTrue(plant != null, "child at " + pos + " has no block entity");
                helper.assertTrue(plant.depth() == 1 && plant.profile().equals(bag.contents()), "a child of the planted flower should be generation 2, found depth " + plant.depth() + " at " + pos);
            }
            failOnProblems(helper, arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // With the default lifetime the root would come up empty 1 time in 3 if the lifetime test applied to its first child;
    // the burst never leaves a planting without one. Forty-eight plantings, so that the 1 in 3 would show up.
    @GameTest(template = TEMPLATE, batch = "gt_burst_child", timeoutTicks = 400)
    public static void plantingBurstAlwaysHasAChild(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(Items.POPPY, 1).add(Items.BONE_MEAL, 2);
            int planted = 0;
            for (int round = 0; round < 3; round++) {
                for (int gx = 0; gx < 4; gx++) {
                    for (int gz = 0; gz < 4; gz++) {
                        BlockPos root = arena.at(6 + gx * 12, 1, 6 + gz * 12);
                        plantVia(helper, arena, bag, root);
                        int flowers = arena.countPlantsAround(root, 8);
                        helper.assertTrue(flowers >= 2, "a planting came up with " + flowers + " flower(s) - the root should always have a child");
                        arena.clearAround(root, 8);
                        planted++;
                    }
                }
            }
            log("burst: " + planted + " plantings, every one with a child");
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // Bone Meal counts generations with the planted flower as the first, so one of it is exactly one flower.
    @GameTest(template = TEMPLATE, batch = "gt_burst_one", timeoutTicks = 200)
    public static void oneBoneMealPlantsOnlyOneFlower(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(Items.POPPY, 1).add(Items.BONE_MEAL, 1).lifetime(1000);
            BlockPos root = arena.at(24, 1, 24);
            plantVia(helper, arena, bag, root);

            helper.assertTrue(arena.countPlants() == 1, "expected exactly one flower, found " + arena.countPlants());
            helper.assertTrue(!GardenScan.isPlantBlock(arena.level.getBlockState(root)), "the only generation there is should be settled, not still spreading");
            failOnProblems(helper, arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // With two, the burst covers all the reproduction there will ever be: the children are the last generation, so
    // nothing in the garden is still alive afterwards.
    @GameTest(template = TEMPLATE, batch = "gt_burst_two", timeoutTicks = 200)
    public static void twoBoneMealsMakeTheBurstEverything(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.BONE_MEAL, 2).lifetime(1000);
            BlockPos root = arena.at(24, 1, 24);
            plantVia(helper, arena, bag, root);

            log("burst with Bone Meal x2: " + arena.countPlants() + " flowers, " + arena.countDiseased() + " still spreading\n" + arena.map());
            helper.assertTrue(arena.countPlants() >= 2, "expected children, found " + arena.countPlants() + " flowers");
            helper.assertTrue(arena.countDiseased() == 0, arena.countDiseased() + " plants are still spreading; the burst should have been everything");
            failOnProblems(helper, arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    private static void plantVia(GameTestHelper helper, Arena arena, Bag bag, BlockPos root) {
        plantVia(helper, arena, bag, root, Direction.UP);
    }

    // `clickedFace` is the face of the block that was clicked: a creeper clings to that block, the plants grow away from it.
    private static void plantVia(GameTestHelper helper, Arena arena, Bag bag, BlockPos root, Direction clickedFace) {
        Component failure = GardenBagItem.plant(arena.level, root, bag.contents(), clickedFace);
        helper.assertTrue(failure == null, "planting failed: " + failure);
    }

    @GameTest(template = TEMPLATE, batch = "gt_lifetime", timeoutTicks = 400)
    public static void lifetimeMatchesTheProfilesAttempts(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            lifetime(helper, arena, 4);
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // With nothing said about it - no bag item, no profile edit - a plant has two children on average, which is what the
    // setting's default is; the setting is what the plant reads.
    @GameTest(template = TEMPLATE, batch = "gt_lifetime_default", timeoutTicks = 400)
    public static void plantsHaveTwoChildrenOnAverageByDefault(GameTestHelper helper) {
        helper.assertTrue(Config.FLOWER_LIFETIME.getDefault() == 2, "the lifetime should default to 2, it is " + Config.FLOWER_LIFETIME.getDefault());
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            lifetime(helper, arena, 0);
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // `attempts` 0 leaves the lifetime to the server's setting.
    private static void lifetime(GameTestHelper helper, Arena arena, int attempts) {
        // Slime x256 makes the density so high that room is never the limit, so what ends each plant's life is
        // the lifetime test alone.
        int lifetime = attempts > 0 ? attempts : Config.FLOWER_LIFETIME.getAsInt();
        Bag bag = new Bag().add(Items.POPPY, 1).lifetime(attempts).add(Items.SLIME_BALL, 256);
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
        log(String.format(Locale.ROOT, "lifetime: %d trials, mean children %.2f (%d attempts)", trials, mean, lifetime));
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
        // Two generations: the root reproduces, its children are born already settled, and with 64 Moss Blocks every
        // settling plant converts the block it stands on.
        // A lifetime of 1000 keeps the root from settling on its very first tick (a 1 in 3 chance at the default), which would
        // leave nothing to check.
        Bag bag = new Bag().add(Items.POPPY, 1).add(Items.MOSS_BLOCK, 64).add(Items.BONE_MEAL, 2).lifetime(1000);
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
            // Settled, all of them: what is left is where they came from and what they replaced.
            helper.assertTrue(entity.garden() != GardenRegistry.NO_GARDEN && entity.depth() == 0 && entity.cap() == 0,
                    "a settled flower block should keep only its garden and its ground: garden " + entity.garden() + ", depth " + entity.depth() + ", cap " + entity.cap());
        }

        failOnProblems(helper, arena.validate());
    }

    @GameTest(template = TEMPLATE, batch = "gt_growth_default", timeoutTicks = LONG_TIMEOUT)
    public static void defaultGardenGrowsOverThreeDays(GameTestHelper helper) {
        growth(helper, "default", new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(MEASURED_LIFETIME), 3, 16);
    }

    // What the server's default lifetime does to a garden (a lifetime of 2, unless the config says otherwise): half of the lineages
    // die out on their own, so five plantings leave a garden that is patchy and far short of its density. Logged for the record - the
    // asserts are the spacing and the structural invariants, which hold whatever survives.
    @GameTest(template = TEMPLATE, batch = "gt_growth_default_lifetime", timeoutTicks = LONG_TIMEOUT)
    public static void defaultLifetimeGardenGrowsOverThreeDays(GameTestHelper helper) {
        growth(helper, "the server's lifetime of " + Config.FLOWER_LIFETIME.getAsInt(), new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2), 3, 16, false);
    }

    @GameTest(template = TEMPLATE, batch = "gt_growth_sparse", timeoutTicks = LONG_TIMEOUT)
    public static void sparseGardenGrowsOverThreeDays(GameTestHelper helper) {
        growth(helper, "sparse (Slime x4)", new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.SLIME_BALL, 4).lifetime(MEASURED_LIFETIME), 3, 4);
    }

    @GameTest(template = TEMPLATE, batch = "gt_growth_dense", timeoutTicks = LONG_TIMEOUT)
    public static void denseGardenGrowsOverThreeDays(GameTestHelper helper) {
        growth(helper, "dense (Slime x64)", new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.SLIME_BALL, 64).lifetime(MEASURED_LIFETIME), 3, 64);
    }

    // ---- Natural variation (see Variation) ------------------------------------------------------------

    // The noise itself, with no world involved: a variance of one and centred at every detail, smooth from one block to the
    // next, and - with the calibration - an average density that stays the bag's however strong the variation is. The species
    // fields are independent of each other, and make stretches where one species dominates.
    @GameTest(template = TEMPLATE, batch = "gt_variation_noise", timeoutTicks = 600)
    public static void variationNoiseIsUnitSmoothAndKeepsTheAverageDensity(GameTestHelper helper) {
        List<String> problems = new ArrayList<>();

        for (double detail : new double[]{0.0, 0.4, 1.0}) {
            double sum = 0.0;
            double sumSquares = 0.0;
            double largestStep = 0.0;
            int count = 0;
            for (int i = 0; i < 300; i++) {
                for (int j = 0; j < 300; j++) {
                    double x = i * 9.3;
                    double z = j * 9.3;
                    double value = Variation.field(555L, x, z, 48.0, detail);
                    sum += value;
                    sumSquares += value * value;
                    count++;
                    largestStep = Math.max(largestStep, Math.abs(value - Variation.field(555L, x + 1.0, z, 48.0, detail)));
                }
            }
            double mean = sum / count;
            double std = Math.sqrt(sumSquares / count - mean * mean);
            log(String.format(Locale.ROOT, "variation noise, detail %.1f: mean %.3f, std %.3f, largest step across one block %.3f", detail, mean, std, largestStep));
            if (Math.abs(mean) > 0.08 || std < 0.93 || std > 1.07) {
                problems.add(String.format(Locale.ROOT, "detail %.1f: mean %.3f and std %.3f should be about 0 and 1", detail, mean, std));
            }
            if (largestStep > 0.4) {
                problems.add(String.format(Locale.ROOT, "detail %.1f: the field jumps by %.3f across a single block", detail, largestStep));
            }
        }

        // The average of 1 / factor squared (what the density goes with) over a sample the calibration has never seen - at
        // several strengths, and with the limits of the spacing moved.
        for (double[] setting : new double[][]{{0.2, 0.4, 3.0}, {0.4, 0.4, 3.0}, {0.8, 0.4, 3.0}, {1.2, 0.4, 3.0}, {0.8, 0.25, 6.0}, {1.0, 0.7, 1.5}}) {
            double sigma = setting[0];
            Variation.Settings settings = new Variation.Settings(sigma, 48.0, setting[1], setting[2], 0.0, 64.0, 0.4, 0);
            double sum = 0.0;
            double smallest = Double.MAX_VALUE;
            double largest = 0.0;
            int count = 0;
            for (int i = 0; i < 120; i++) {
                for (int j = 0; j < 120; j++) {
                    double factor = Variation.spacingFactor(settings, 999L, i * 0.71 * 48.0, j * 0.71 * 48.0);
                    sum += 1.0 / (factor * factor);
                    smallest = Math.min(smallest, factor);
                    largest = Math.max(largest, factor);
                    count++;
                }
            }
            double density = sum / count;
            log(String.format(Locale.ROOT, "variation %.1f within x%.2f..x%.2f: average density x%.3f of the bag's, spacing from x%.2f to x%.2f (constant x%.3f)",
                    sigma, setting[1], setting[2], density, smallest, largest, Variation.calibration(settings)));
            if (density < 0.9 || density > 1.1) {
                problems.add(String.format(Locale.ROOT, "variation %.1f within x%.2f..x%.2f: the average density is x%.3f of the bag's, not about 1", sigma, setting[1], setting[2], density));
            }
            if (smallest < setting[1] - 1e-9 || largest > setting[2] + 1e-9) {
                problems.add(String.format(Locale.ROOT, "variation %.1f: the spacing factor went outside its limits x%.2f..x%.2f (x%.2f..x%.2f)", sigma, setting[1], setting[2], smallest, largest));
            }
        }

        if (Variation.field(7L, 12.5, -80.25, 48.0, 0.4) != Variation.field(7L, 12.5, -80.25, 48.0, 0.4)) {
            problems.add("the same place gave two different values");
        }
        if (Variation.field(7L, 12.5, -80.25, 48.0, 0.4) == Variation.field(8L, 12.5, -80.25, 48.0, 0.4)) {
            problems.add("two seeds gave the same field");
        }
        Variation.Settings off = Variation.Settings.OFF;
        if (Variation.spacingFactor(off, 1L, 3.0, 4.0) != 1.0 || Variation.speciesFactor(off, 1L, 3.0, 4.0, Blocks.POPPY) != 1.0) {
            problems.add("with the variation off the factors must be exactly 1");
        }

        // Two species: independent fields (no correlation), and stretches where either one is the far likelier.
        Variation.Settings species = new Variation.Settings(0.0, 48.0, 0.4, 3.0, 1.0, 64.0, 0.4, 0);
        double sumA = 0.0, sumB = 0.0, sumAA = 0.0, sumBB = 0.0, sumAB = 0.0;
        double highest = -Double.MAX_VALUE;
        double lowest = Double.MAX_VALUE;
        int samples = 0;
        for (int i = 0; i < 100; i++) {
            for (int j = 0; j < 100; j++) {
                double x = i * 0.71 * 64.0;
                double z = j * 0.71 * 64.0;
                double a = Math.log(Variation.speciesFactor(species, 42L, x, z, Blocks.POPPY));
                double b = Math.log(Variation.speciesFactor(species, 42L, x, z, Blocks.DANDELION));
                sumA += a;
                sumB += b;
                sumAA += a * a;
                sumBB += b * b;
                sumAB += a * b;
                highest = Math.max(highest, a - b);
                lowest = Math.min(lowest, a - b);
                samples++;
            }
        }
        double correlation = (samples * sumAB - sumA * sumB) / Math.sqrt((samples * sumAA - sumA * sumA) * (samples * sumBB - sumB * sumB));
        log(String.format(Locale.ROOT, "species fields: correlation %.3f between two species, log ratio from %.2f to %.2f", correlation, lowest, highest));
        if (Math.abs(correlation) > 0.15) {
            problems.add(String.format(Locale.ROOT, "two species' fields are correlated (%.3f)", correlation));
        }
        if (highest < Math.log(3.0) || lowest > -Math.log(3.0)) {
            problems.add(String.format(Locale.ROOT, "no stretch where one species is 3 times likelier than the other (log ratios %.2f to %.2f)", lowest, highest));
        }

        failOnProblems(helper, problems);
        helper.succeed();
    }

    // A parent's children keep the spacing of the ground it stands on - wide in a clearing, tight in a thicket - under the same
    // garden rules, only the spacing field differs. The two spots are the widest and the tightest around, on a seed whose field
    // has plenty of contrast, and the children are made one after the other by the very code a random tick uses.
    @GameTest(template = TEMPLATE, batch = "gt_variation_spacing", timeoutTicks = 600)
    public static void childrenKeepTheSpacingOfTheGroundTheirParentStandsOn(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare(true, MARGIN_CHUNKS);
        try {
            BlockPos thicket = null;
            BlockPos clearing = null;
            for (int seed = 0; seed < 60 && thicket == null; seed++) {
                Variation.override(new Variation.Settings(0.9, 20.0, 0.4, 3.0, 0.0, 64.0, 0.3, seed));
                double low = Double.MAX_VALUE;
                double high = 0.0;
                BlockPos lowest = null;
                BlockPos highest = null;
                for (int x = -8; x <= 56; x += 4) {
                    for (int z = -8; z <= 56; z += 4) {
                        BlockPos pos = arena.at(x, 1, z);
                        double factor = Variation.spacingFactor(arena.level, pos);
                        if (factor < low) {
                            low = factor;
                            lowest = pos;
                        }
                        if (factor > high) {
                            high = factor;
                            highest = pos;
                        }
                    }
                }
                if (high >= 2.5 && low <= 0.6) {
                    thicket = lowest;
                    clearing = highest;
                }
            }
            helper.assertTrue(thicket != null, "no seed gave a field with both a thicket and a clearing around the arena");

            GardenBagContents contents = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).contents();
            int density = SpreadMath.resolveDensity(contents.densityPer16x16());
            double nominal = SpreadMath.minSpacing(density);
            double[] closestPair = new double[2];
            for (int spot = 0; spot < 2; spot++) {
                BlockPos root = spot == 0 ? thicket : clearing;
                double factor = Variation.spacingFactor(arena.level, root);
                double spacing = SpreadMath.spacing(density, factor);

                placeRoot(arena.level, root, FlowerDisease.DISEASED_POPPY.get(), contents);
                SpreadProfileBlockEntity rootPlant = DiseasedPlantLogic.profileAt(arena.level, root);
                List<BlockPos> family = new ArrayList<>(List.of(root));
                for (int attempt = 0; attempt < 12; attempt++) {
                    BlockPos child = DiseasedPlantLogic.tryReproduce(arena.level, root, arena.level.getRandom(), FlowerDisease.DISEASED_POPPY.get(),
                            Blocks.POPPY, DiseasedPlantLogic.Shape.SINGLE, rootPlant.lineage());
                    if (child == null) {
                        break;
                    }
                    family.add(child);
                }

                double closest = Double.MAX_VALUE;
                double farthest = 0.0;
                for (int i = 0; i < family.size(); i++) {
                    farthest = Math.max(farthest, Math.sqrt(family.get(i).distSqr(root)));
                    for (int j = i + 1; j < family.size(); j++) {
                        closest = Math.min(closest, Math.sqrt(family.get(i).distSqr(family.get(j))));
                    }
                }
                log(String.format(Locale.ROOT, "%s at %s: spacing x%.2f = %.1f blocks (usual %.1f), %d plants in the family, closest pair %.2f, farthest child %.1f",
                        spot == 0 ? "thicket" : "clearing", root.toShortString(), factor, spacing, nominal, family.size(), closest, farthest));
                helper.assertTrue(family.size() >= 2, "the parent in the " + (spot == 0 ? "thicket" : "clearing") + " found no place for a single child");
                helper.assertTrue(closest >= spacing - 1e-9, "two plants are " + closest + " blocks apart, closer than the local spacing " + spacing);
                closestPair[spot] = closest;
                arena.clearAround(root, 30);
            }
            helper.assertTrue(closestPair[0] < nominal, "plants in the thicket should come closer than the usual spacing " + nominal + ", closest pair was " + closestPair[0]);
            helper.assertTrue(closestPair[1] >= 2 * nominal, "plants in the clearing should keep more than twice the usual spacing " + nominal + ", closest pair was " + closestPair[1]);
        } finally {
            arena.cleanup();
            Variation.override(Variation.Settings.OFF);
        }
        helper.succeed();
    }

    // The species fields make stretches of one species: where a pool's flower is favoured it is most of what is drawn (even
    // when the parent is the other kind, because inheritance yields to a species the ground holds back), and where it is
    // held back it is the exception.
    @GameTest(template = TEMPLATE, batch = "gt_variation_species", timeoutTicks = 600)
    public static void speciesFieldsMakeStretchesOfOneSpecies(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(BlockPos.ZERO);
        List<SettleTable.Option> pool = List.of(new SettleTable.Option(Blocks.POPPY, 3), new SettleTable.Option(Blocks.DANDELION, 2));
        try {
            BlockPos poppies = null;
            BlockPos dandelions = null;
            for (int seed = 0; seed < 60 && poppies == null; seed++) {
                Variation.override(new Variation.Settings(0.0, 48.0, 0.4, 3.0, 1.5, 24.0, 0.3, seed));
                double most = 0.0;
                double least = 1.0;
                BlockPos mostAt = null;
                BlockPos leastAt = null;
                for (int x = -8; x <= 56; x += 3) {
                    for (int z = -8; z <= 56; z += 3) {
                        BlockPos pos = base.offset(x, 1, z);
                        double[] weights = Variation.weights(level, pos, pool);
                        double share = weights[0] / (weights[0] + weights[1]);
                        if (share > most) {
                            most = share;
                            mostAt = pos;
                        }
                        if (share < least) {
                            least = share;
                            leastAt = pos;
                        }
                    }
                }
                if (most >= 0.9 && least <= 0.1) {
                    poppies = mostAt;
                    dandelions = leastAt;
                }
            }
            helper.assertTrue(poppies != null, "no seed gave a field with a stretch for each species around the arena");

            RandomSource random = level.getRandom();
            int poppiesAmongPoppies = 0;
            int poppiesAmongDandelions = 0;
            for (int i = 0; i < 400; i++) {
                // The parents are the wrong kind on purpose.
                if (DiseasedPlantLogic.pickSpecies(level, poppies, pool, Blocks.DANDELION, random).block() == Blocks.POPPY) {
                    poppiesAmongPoppies++;
                }
                if (DiseasedPlantLogic.pickSpecies(level, dandelions, pool, Blocks.POPPY, random).block() == Blocks.POPPY) {
                    poppiesAmongDandelions++;
                }
            }
            log("species stretches: " + poppiesAmongPoppies + "/400 poppies where they are favoured (parent a dandelion), "
                    + poppiesAmongDandelions + "/400 where they are held back (parent a poppy)");
            helper.assertTrue(poppiesAmongPoppies >= 260, "only " + poppiesAmongPoppies + "/400 children were poppies where poppies are favoured");
            helper.assertTrue(poppiesAmongDandelions <= 120, poppiesAmongDandelions + "/400 children were poppies where poppies are held back");
        } finally {
            Variation.override(Variation.Settings.OFF);
        }
        helper.succeed();
    }

    // What /diseasedflower variation prints: the summary, and the two maps (spacing, and the species of a pool of three), each
    // with the right number of rows of the right width and the marker in the middle - and the flat map when the variation is off.
    @GameTest(template = TEMPLATE, batch = "gt_variation_commands", timeoutTicks = 200)
    public static void variationCommandsDrawTheirMaps(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos here = helper.absolutePos(new BlockPos(24, 1, 24));
        Variation.Settings settings = new Variation.Settings(0.6, 32.0, 0.4, 3.0, 1.0, 48.0, 0.4, 3);
        List<SettleTable.Option> pool = List.of(new SettleTable.Option(Blocks.POPPY, 3), new SettleTable.Option(Blocks.DANDELION, 2), new SettleTable.Option(Blocks.CORNFLOWER, 1));
        try {
            Variation.override(settings);
            List<String> summary = VariationCommands.describe(level, here);
            log("variation summary:\n" + String.join("\n", summary));
            helper.assertTrue(summary.size() == 5, "expected 5 lines of summary, got " + summary.size());
            helper.assertTrue(summary.get(1).contains("0.60") && summary.get(2).contains("1.00"), "the summary should show the settings: " + summary);

            for (List<SettleTable.Option> options : List.of(List.<SettleTable.Option>of(), pool)) {
                List<Component> map = VariationCommands.render(settings, level.getSeed(), here, 4, options);
                helper.assertTrue(map.size() == 19, "a map is a header, 17 rows and a legend, got " + map.size() + " lines");
                for (int row = 1; row <= 17; row++) {
                    String text = map.get(row).getString();
                    helper.assertTrue(text.length() == 57, "row " + row + " of the " + (options.isEmpty() ? "spacing" : "species") + " map has " + text.length() + " cells, not 57");
                    helper.assertTrue((row == 9) == (text.charAt(28) == '+'), "the marker belongs in the middle of the middle row only (row " + row + ")");
                }
                helper.assertTrue(map.get(18).getString().length() > 10, "the legend is empty");
            }

            List<Component> flat = VariationCommands.render(Variation.Settings.OFF, level.getSeed(), here, 4, List.of());
            helper.assertTrue(flat.get(0).getString().contains("off"), "a map of a variation that is off should say so: " + flat.get(0).getString());
        } finally {
            Variation.override(Variation.Settings.OFF);
        }
        helper.succeed();
    }

    // A whole garden on a strongly varying spacing field: its density follows the field. The interior of the arena is cut into
    // 9x9 cells; for each, the density the field predicts (the mean of 1 / factor^2 over sample points in it) is compared with
    // the plants that grew there, on a seed whose field has plenty of contrast over the arena. Every structural invariant
    // still holds.
    @GameTest(template = TEMPLATE, batch = "gt_variation_growth", timeoutTicks = LONG_TIMEOUT)
    public static void gardenDensityFollowsTheSpacingField(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        ensureRandomTickSpeed(arena.level);

        int cells = 4;
        int side = 9;
        int border = (SIZE - cells * side) / 2;
        double[] predicted = new double[cells * cells];
        boolean found = false;
        for (int seed = 0; seed < 80 && !found; seed++) {
            Variation.override(new Variation.Settings(0.6, 16.0, 0.4, 3.0, 0.0, 64.0, 0.3, seed));
            for (int cell = 0; cell < predicted.length; cell++) {
                double sum = 0.0;
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        int x = border + (cell % cells) * side + 1 + i * 3;
                        int z = border + (cell / cells) * side + 1 + j * 3;
                        double factor = Variation.spacingFactor(arena.level, arena.at(x, 1, z));
                        sum += 1.0 / (factor * factor);
                    }
                }
                predicted[cell] = sum / 9.0;
            }
            double smallest = Double.MAX_VALUE;
            double largest = 0.0;
            for (double value : predicted) {
                smallest = Math.min(smallest, value);
                largest = Math.max(largest, value);
            }
            found = largest / smallest >= 4.0;
        }
        helper.assertTrue(found, "no seed gave a field with enough contrast over the arena");

        Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(MEASURED_LIFETIME);
        BlockPos root = arena.at(24, 1, 24);
        plantVia(helper, arena, bag, root);
        for (BlockPos extra : List.of(arena.at(12, 1, 12), arena.at(36, 1, 12), arena.at(12, 1, 36), arena.at(36, 1, 36))) {
            plantVia(helper, arena, bag, extra);
        }

        Simulation simulation = Simulation.start(arena, 3);
        helper.assertTrue(simulation != null, "a day simulation was already running");

        helper.succeedWhen(() -> {
            helper.assertTrue(simulation.result != null, "simulation still running");
            if (!simulation.reported) {
                simulation.reported = true;

                int[] counts = new int[cells * cells];
                for (BlockPos plant : arena.plantPositions()) {
                    int x = plant.getX() - arena.origin.getX() - border;
                    int z = plant.getZ() - arena.origin.getZ() - border;
                    if (x >= 0 && x < cells * side && z >= 0 && z < cells * side) {
                        counts[(z / side) * cells + x / side]++;
                    }
                }

                double meanCount = 0.0;
                double meanPredicted = 0.0;
                for (int cell = 0; cell < counts.length; cell++) {
                    meanCount += counts[cell];
                    meanPredicted += predicted[cell];
                }
                meanCount /= counts.length;
                meanPredicted /= counts.length;
                double covariance = 0.0;
                double varianceCount = 0.0;
                double variancePredicted = 0.0;
                StringBuilder table = new StringBuilder();
                for (int cell = 0; cell < counts.length; cell++) {
                    covariance += (counts[cell] - meanCount) * (predicted[cell] - meanPredicted);
                    varianceCount += (counts[cell] - meanCount) * (counts[cell] - meanCount);
                    variancePredicted += (predicted[cell] - meanPredicted) * (predicted[cell] - meanPredicted);
                    table.append(String.format(Locale.ROOT, "%2d (field says x%.2f) ", counts[cell], predicted[cell]));
                    if (cell % cells == cells - 1) {
                        table.append('\n');
                    }
                }
                double correlation = covariance / Math.sqrt(varianceCount * variancePredicted);
                log(String.format(Locale.ROOT, "variation growth: plants per 9x9 cell against the density the field predicts, correlation %.2f, mean %.1f per cell%n%s%s%n%s",
                        correlation, meanCount, table, describe(simulation.result), arena.map()));
                if (correlation < 0.5) {
                    failNow(helper, String.format(Locale.ROOT, "the density of the garden does not follow the spacing field (correlation %.2f)", correlation));
                }
                List<String> problems = arena.validate();
                arena.cleanup();
                Variation.override(Variation.Settings.OFF);
                simulation.problems = problems;
            }
            failOnProblems(helper, simulation.problems);
            if (simulation.result.errors() > 0) {
                failNow(helper, simulation.result.errors() + " exceptions while ticking plants (see the log)");
            }
        });
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
        Component failure = GardenBagItem.plant(arena.level, root, bag.contents(), Direction.UP);
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
            failOnProblems(helper, simulation.problems);
            if (simulation.result.errors() > 0) {
                failNow(helper, simulation.result.errors() + " exceptions while ticking plants (see the log)");
            }
        });
    }

    @GameTest(template = TEMPLATE, batch = "gt_nbt", timeoutTicks = 100)
    public static void profilesSurviveSavingAndOldKeysStillLoad(GameTestHelper helper) {
        Bag bag = new Bag()
                .add(Items.POPPY, 3).add(Items.DANDELION, 1).add(Items.SCULK, 5).add(Items.NETHER_STAR, 1).lifetime(6)
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
        // (three generations remaining after the plant itself = a cap of four counting the plant as the first)
        helper.assertTrue(loaded.generations() == 4 && loaded.spreadChance() == 0.5 && loaded.spreadDistance() == 7 && loaded.densityPer16x16() == 9
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
            plant.inherit(plant.garden(), 42);

            CompoundTag saved = plant.saveWithFullMetadata(arena.level.registryAccess());
            BlockEntity reloaded = BlockEntity.loadStatic(pos, arena.level.getBlockState(pos), saved, arena.level.registryAccess());
            helper.assertTrue(reloaded instanceof SpreadProfileBlockEntity, "the block entity did not reload");
            SpreadProfileBlockEntity copy = (SpreadProfileBlockEntity) reloaded;
            copy.setLevel(arena.level);
            helper.assertTrue(copy.depth() == 42 && copy.garden() == plant.garden() && copy.profile().equals(original),
                    "block entity reloaded as garden " + copy.garden() + " depth " + copy.depth() + ", " + copy.profile());
            helper.assertTrue(!saved.contains("Species") && !saved.contains("Generations"), "the profile should live in the registry, not in the block entity: " + saved);
            log("nbt: a plant with every modifier set saves as " + GardenStats.serializedSize(saved) + " bytes (the profile sits in the garden registry)");
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
            helper.assertTrue(GardenBagItem.plant(arena.level, onGround, bag.contents(), Direction.UP) == null, "a creeper could not be planted on the ground");
            helper.assertTrue(arena.level.getBlockState(onGround).getBlock() instanceof CreepingFlowerBlock, "no creeper at " + onGround);

            // Clicking the west face of a wall: the creeper grabs the wall behind it.
            arena.level.setBlock(arena.at(30, 1, 24), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockPos besideWall = arena.at(29, 1, 24);
            helper.assertTrue(GardenBagItem.plant(arena.level, besideWall, bag.contents(), Direction.WEST) == null, "a creeper could not be planted on a wall");
            helper.assertTrue(arena.level.getBlockState(besideWall).getBlock() instanceof CreepingFlowerBlock, "no creeper at " + besideWall);

            // Clicking the underside of a ceiling: hangs from it.
            arena.level.setBlock(arena.at(20, 5, 20), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockPos underCeiling = arena.at(20, 4, 20);
            helper.assertTrue(GardenBagItem.plant(arena.level, underCeiling, bag.contents(), Direction.DOWN) == null, "a creeper could not be planted on a ceiling");

            failOnProblems(helper, arena.validate());
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
        Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(MEASURED_LIFETIME);
        Component failure = GardenBagItem.plant(arena.level, root, bag.contents(), Direction.UP);
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
                failNow(helper, result.errors() + " exceptions while ticking plants (see the log)");
            }

            if (day[0] < totalDays) {
                current[0] = Simulation.start(arena, 1);
                helper.assertTrue(current[0] != null, "could not start the next day");
                helper.assertTrue(false, "day " + day[0] + " done, next one started");
            }
            arena.cleanup();
        });
    }

    // A planting is one garden: every plant of it points at the same registry entry instead of carrying a copy of the
    // profile.
    @GameTest(template = TEMPLATE, batch = "gt_garden_shared", timeoutTicks = 200)
    public static void plantsOfAPlantingShareOneGarden(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(1000);
            GardenRegistry registry = GardenRegistry.of(arena.level);
            int before = registry.size();
            BlockPos root = arena.at(24, 1, 24);
            plantVia(helper, arena, bag, root);
            helper.assertTrue(registry.size() == before + 1, "one planting should create exactly one garden, created " + (registry.size() - before));

            int garden = GardenRegistry.NO_GARDEN;
            int active = 0;
            for (BlockPos pos : arena.plantPositions()) {
                SpreadProfileBlockEntity plant = DiseasedPlantLogic.profileAt(arena.level, pos);
                if (plant == null) {
                    continue;
                }
                active++;
                garden = garden == GardenRegistry.NO_GARDEN ? plant.garden() : garden;
                helper.assertTrue(garden != GardenRegistry.NO_GARDEN && plant.garden() == garden, "plants of one planting should share a garden, found " + plant.garden() + " and " + garden);
                CompoundTag saved = plant.saveWithFullMetadata(arena.level.registryAccess());
                helper.assertTrue(!saved.contains("Species") && !saved.contains("Lifetime"), "a plant should not carry a copy of the profile: " + saved);
            }
            helper.assertTrue(active >= 1, "expected active children");

            GardenRegistry.Garden entry = registry.get(garden);
            helper.assertTrue(entry != null && !entry.legacy() && entry.profile().equals(bag.contents()) && entry.origin().equals(root),
                    "the registry should remember the planting: " + entry);
            failOnProblems(helper, arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // The point of a shared profile: replacing it changes every plant of the garden at once.
    @GameTest(template = TEMPLATE, batch = "gt_garden_edit", timeoutTicks = 200)
    public static void editingAGardenChangesEveryPlantOfIt(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            plantVia(helper, arena, new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(1000), arena.at(24, 1, 24));

            List<SpreadProfileBlockEntity> plants = new ArrayList<>();
            for (BlockPos pos : arena.plantPositions()) {
                SpreadProfileBlockEntity plant = DiseasedPlantLogic.profileAt(arena.level, pos);
                if (plant != null) {
                    plants.add(plant);
                }
            }
            helper.assertTrue(plants.size() >= 2, "expected several active plants, found " + plants.size());

            GardenBagContents edited = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(3).add(Items.SLIME_BALL, 4).contents();
            GardenRegistry.of(arena.level).replaceProfile(plants.get(0).garden(), edited);
            for (SpreadProfileBlockEntity plant : plants) {
                helper.assertTrue(plant.profile().equals(edited), "a plant of the garden still sees the old profile: " + plant.profile());
            }
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // A block entity written before gardens existed carries its whole profile: it is folded into the registry the first
    // time anything asks for it, identical profiles share one garden, and it is rewritten in the new form.
    @GameTest(template = TEMPLATE, batch = "gt_garden_legacy", timeoutTicks = 200)
    public static void oldSavesMigrateIntoTheRegistry(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            GardenBagContents oldProfile = new Bag().add(Items.POPPY, 2).add(Items.SCULK, 3).contents();
            CompoundTag old = new CompoundTag();
            oldProfile.writeTo(old);
            old.putLong("Depth", 7);

            SpreadProfileBlockEntity first = loadOldPlant(arena, arena.at(10, 1, 10), old);
            SpreadProfileBlockEntity second = loadOldPlant(arena, arena.at(12, 1, 10), old);
            helper.assertTrue(first.garden() == GardenRegistry.NO_GARDEN, "nothing has asked for the profile yet, so it should still be waiting");

            helper.assertTrue(first.profile().equals(oldProfile), "the old profile was not preserved: " + first.profile());
            helper.assertTrue(first.garden() != GardenRegistry.NO_GARDEN && first.depth() == 7, "expected a garden and the old depth, found garden " + first.garden() + " depth " + first.depth());
            second.profile();
            helper.assertTrue(second.garden() == first.garden(), "identical old profiles should share one legacy garden");
            GardenRegistry.Garden entry = GardenRegistry.of(arena.level).get(first.garden());
            helper.assertTrue(entry != null && entry.legacy(), "the garden should be marked as folded in from an older save: " + entry);

            CompoundTag resaved = first.saveWithFullMetadata(arena.level.registryAccess());
            helper.assertTrue(resaved.contains("Garden") && !resaved.contains("Species") && !resaved.contains("DecayStrength"), "the plant should be rewritten in the new form: " + resaved);
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    private static SpreadProfileBlockEntity loadOldPlant(Arena arena, BlockPos pos, CompoundTag data) {
        SpreadProfileBlockEntity plant = new SpreadProfileBlockEntity(pos, FlowerDisease.DISEASED_POPPY.get().defaultBlockState());
        plant.loadWithComponents(data.copy(), arena.level.registryAccess());
        plant.setLevel(arena.level);
        return plant;
    }

    @GameTest(template = TEMPLATE, batch = "gt_registry_nbt", timeoutTicks = 100)
    public static void theRegistrySurvivesSaving(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GardenBagContents planted = new Bag().add(Items.POPPY, 3).add(Items.MOSS_BLOCK, 9).contents();
        GardenBagContents folded = new Bag().add(Items.DANDELION, 1).add(Items.FEATHER, 6).contents();

        GardenRegistry registry = new GardenRegistry();
        int first = registry.create(planted, new BlockPos(1, 2, 3), 500);
        int legacy = registry.intern(folded);
        helper.assertTrue(registry.intern(folded) == legacy, "the same old profile should not create a second garden");

        GardenRegistry loaded = GardenRegistry.load(registry.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
        GardenRegistry.Garden entry = loaded.get(first);
        helper.assertTrue(loaded.size() == 2, "expected two gardens after reloading, found " + loaded.size());
        helper.assertTrue(entry != null && entry.profile().equals(planted) && entry.origin().equals(new BlockPos(1, 2, 3)) && entry.plantedAt() == 500 && !entry.legacy(),
                "a planting did not survive: " + entry);
        helper.assertTrue(loaded.profileOf(legacy).equals(folded) && loaded.get(legacy).legacy(), "a folded-in garden did not survive");
        helper.assertTrue(loaded.intern(folded) == legacy, "after reloading, the same old profile should still find its garden");
        helper.assertTrue(loaded.create(planted, BlockPos.ZERO, 0) > legacy, "new gardens must not reuse an old id");
        helper.succeed();
    }

    // Only a plant that still has something to do keeps a block entity: a creeper has no vanilla form, so settling it
    // leaves it in place - as one of our blocks, but without data - and the upper half of a tall plant never had one.
    @GameTest(template = TEMPLATE, batch = "gt_settled_entities", timeoutTicks = 200)
    public static void settledPlantsKeepNoBlockEntity(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            BlockPos creeper = arena.at(10, 1, 10);
            plantVia(helper, arena, new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1), creeper);
            // A planted creeper draws the energy of a patch, and stays active until that has grown; with none to grow it
            // settles - so take the energy away and let it finish.
            if (arena.level.getBlockEntity(creeper) instanceof CreeperBlockEntity planted) {
                planted.setEnergy(0);
            }
            tickUntilSettled(arena.level, creeper, arena.level.getRandom());
            BlockState creeperState = arena.level.getBlockState(creeper);
            helper.assertTrue(creeperState.getBlock() instanceof CreepingFlowerBlock && DiseasedPlantLogic.isSettled(creeperState), "expected a settled creeper, found " + creeperState);
            helper.assertTrue(arena.level.getBlockEntity(creeper) == null, "a settled creeper should keep no block entity");

            BlockPos tall = arena.at(20, 1, 20);
            DoublePlantBlock.placeAt(arena.level, FlowerDisease.DISEASED_SUNFLOWER.get().defaultBlockState(), tall, SettleTable.PLACEMENT_FLAGS);
            helper.assertTrue(arena.level.getBlockEntity(tall) instanceof SpreadProfileBlockEntity, "the lower half of an active tall plant should have a block entity");
            helper.assertTrue(arena.level.getBlockEntity(tall.above()) == null, "the upper half should never have one");

            failOnProblems(helper, arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // ---- Stall report -------------------------------------------------------------------------------

    // The report a stalled game writes (see StallWatchdog), made on demand: the summary with the server thread first, and vanilla's
    // level dump of each dimension - the file that says which chunk is not ready to be saved.
    @GameTest(template = TEMPLATE, batch = "gt_stall_report", timeoutTicks = 200)
    public static void aStallReportSaysWhereTheGameIsWaiting(GameTestHelper helper) throws IOException {
        java.nio.file.Path folder = StallWatchdog.report(helper.getLevel().getServer(), "test: pretending the world has been stopping for 99 s");
        helper.assertTrue(folder != null, "the report could not be written");
        try {
            String threads = java.nio.file.Files.readString(folder.resolve("threads.txt"));
            log("stall report, first lines:\n" + threads.lines().limit(12).collect(java.util.stream.Collectors.joining("\n")));
            helper.assertTrue(threads.startsWith("Flower Disease stall report: test"), "the report should start with what it is about");
            helper.assertTrue(threads.contains("chunk system has work:") && threads.contains("no deadlocked threads"), "the report should say how the chunk system is and whether anything is deadlocked");
            int server = threads.indexOf("=== Server thread");
            int others = threads.indexOf("=== ", server + 1);
            helper.assertTrue(server >= 0 && (others < 0 || threads.indexOf("=== Server thread") < others), "the server thread should come first");
            for (String file : List.of("stats.txt", "chunks.csv", "entities.csv", "block_entities.csv")) {
                helper.assertTrue(java.nio.file.Files.exists(folder.resolve("overworld").resolve(file)), "the level dump lacks " + file);
            }
        } finally {
            try (var files = java.nio.file.Files.walk(folder)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
        helper.succeed();
    }

    // The watching itself: a tick that does not end (the test thread sleeps in the middle of one, as far as the watchdog can tell) is
    // noticed from another thread, and reported.
    @GameTest(template = TEMPLATE, batch = "gt_stall_watch", timeoutTicks = 200)
    public static void theWatchdogNoticesATickThatDoesNotEnd(GameTestHelper helper) throws Exception {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        java.nio.file.Path logs = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("logs");
        List<java.nio.file.Path> before = stallReports(logs);
        try {
            StallWatchdog.start(server, 1);
            StallWatchdog.tickStarted();
            Thread.sleep(4500L);
        } finally {
            StallWatchdog.tickFinished();
            StallWatchdog.start(server);
        }

        List<java.nio.file.Path> reports = new ArrayList<>(stallReports(logs));
        reports.removeAll(before);
        try {
            helper.assertTrue(!reports.isEmpty() && reports.size() <= 3, "expected one to three reports of the stalled tick, found " + reports.size());
            String threads = java.nio.file.Files.readString(reports.get(0).resolve("threads.txt"));
            helper.assertTrue(threads.startsWith("Flower Disease stall report: one server tick has been running for"), "the report should say what stalled: " + threads.lines().findFirst().orElse(""));
        } finally {
            for (java.nio.file.Path report : reports) {
                try (var files = java.nio.file.Files.walk(report)) {
                    files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
                }
            }
        }
        helper.succeed();
    }

    private static List<java.nio.file.Path> stallReports(java.nio.file.Path logs) throws IOException {
        if (!java.nio.file.Files.isDirectory(logs)) {
            return List.of();
        }
        try (var entries = java.nio.file.Files.list(logs)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("flowerdisease-stall-")).toList();
        }
    }

    // ---- Creeper patches ----------------------------------------------------------------------------

    // The knobs the patch tests pin (see PatchGrowth.Settings), so that they hold whatever the config says. No hanging: the ground
    // has no wall to hang from.
    private static final PatchGrowth.Settings PATCHES = new PatchGrowth.Settings(12, 0.33, 1.0, 1.0, 1.0, 3, 1.0, 0.0, 6);

    // A seed's budget goes from 0 up to its style's cap with the middle values the most likely (a lone piece is rare); the three
    // styles share the seeds 3:4:3 and have caps of 12, 10 and 6 pieces at patchMaxPieces 12; with the variety at 0 they are all
    // alike.
    @GameTest(template = TEMPLATE, batch = "gt_patch_budget", timeoutTicks = 100)
    public static void creeperBudgetsFollowTheBellCurveAndTheStyles(GameTestHelper helper) {
        RandomSource random = helper.getLevel().getRandom();
        int max = 12;
        int[] counts = new int[max + 1];
        int samples = 24_000;
        for (int i = 0; i < samples; i++) {
            counts[PatchGrowth.rollBudget(random, max)]++;
        }

        int totalWeight = 0;
        for (int budget = 0; budget <= max; budget++) {
            totalWeight += PatchGrowth.weight(budget, max);
        }
        StringBuilder report = new StringBuilder("patch budget over " + samples + " rolls:");
        for (int budget = 0; budget <= max; budget++) {
            double expected = (double) PatchGrowth.weight(budget, max) / totalWeight;
            double seen = (double) counts[budget] / samples;
            report.append(String.format(Locale.ROOT, " %d=%.1f%%", budget, seen * 100));
            helper.assertTrue(Math.abs(seen - expected) < 0.012, "a budget of " + budget + " came up " + seen + " of the time, expected " + expected);
        }
        log(report.toString());

        try {
            PatchGrowth.override(PATCHES);
            helper.assertTrue(PatchGrowth.traits(PATCHES, PatchGrowth.Style.STREAK).cap() == 12 && PatchGrowth.traits(PATCHES, PatchGrowth.Style.BRANCHY).cap() == 10
                    && PatchGrowth.traits(PATCHES, PatchGrowth.Style.TUFT).cap() == 6, "the styles' caps should be 12, 10 and 6 pieces");

            int[] styles = new int[3];
            int[] biggest = new int[3];
            for (int i = 0; i < 20_000; i++) {
                PatchGrowth.Plan plan = PatchGrowth.roll(random);
                styles[plan.style().ordinal()]++;
                biggest[plan.style().ordinal()] = Math.max(biggest[plan.style().ordinal()], plan.budget());
            }
            log("patch styles over 20000 seeds: " + Arrays.toString(styles) + ", biggest budgets " + Arrays.toString(biggest));
            helper.assertTrue(Math.abs(styles[0] / 20_000.0 - 0.3) < 0.02 && Math.abs(styles[1] / 20_000.0 - 0.4) < 0.02 && Math.abs(styles[2] / 20_000.0 - 0.3) < 0.02,
                    "the styles should share the seeds 3:4:3, got " + Arrays.toString(styles));
            helper.assertTrue(biggest[0] >= 10 && biggest[0] <= 12 && biggest[1] >= 8 && biggest[1] <= 10 && biggest[2] >= 4 && biggest[2] <= 6,
                    "the biggest budgets should reach (but not pass) the styles' caps 12, 10 and 6, got " + Arrays.toString(biggest));

            PatchGrowth.Settings alike = new PatchGrowth.Settings(12, 0.33, 1.0, 1.0, 1.0, 3, 0.0, 0.0, 6);
            helper.assertTrue(PatchGrowth.traits(alike, PatchGrowth.Style.STREAK).equals(PatchGrowth.traits(alike, PatchGrowth.Style.TUFT)),
                    "with a variety of 0 every style should be the same");
            helper.assertTrue(PatchGrowth.roll(random).budget() >= 0 && PatchGrowth.traits(new PatchGrowth.Settings(0, 0.33, 1.0, 1.0, 1.0, 3, 1.0, 0.0, 6), PatchGrowth.Style.STREAK).cap() == 0,
                    "patchMaxPieces 0 should leave every seed without a patch");
        } finally {
            PatchGrowth.override(null);
        }
        helper.succeed();
    }

    // The numbers PatchStats reads a patch by: pieces that touch (corners included) are one patch, a line is far more elongated
    // than a square, and the eigenvalues it is built on come out right.
    @GameTest(template = TEMPLATE, batch = "gt_patch_stats", timeoutTicks = 100)
    public static void patchStatsTellPatchesApartAndMeasureTheirShape(GameTestHelper helper) {
        LongArrayList cells = new LongArrayList();
        // A line of ten, a 5x5 square far from it, and a lone piece; a diagonal neighbour of the lone piece joins it.
        for (int x = 0; x < 10; x++) {
            cells.add(new BlockPos(x, 0, 0).asLong());
        }
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                cells.add(new BlockPos(30 + x, 0, 30 + z).asLong());
            }
        }
        cells.add(new BlockPos(60, 0, 60).asLong());
        PatchStats three = PatchStats.of(cells);
        helper.assertTrue(three.patches == 3 && three.pieces == 36 && three.biggest() == 25, "expected 3 patches of 36 pieces, the biggest 25: " + three.patches + " / " + three.pieces + " / " + three.biggest());

        cells.add(new BlockPos(61, 1, 60).asLong());
        helper.assertTrue(PatchStats.of(cells).patches == 3 && PatchStats.of(cells).pieces == 37, "a piece touching another at a corner is part of its patch");
        cells.add(new BlockPos(63, 0, 60).asLong());
        helper.assertTrue(PatchStats.of(cells).patches == 4, "a piece two blocks away is a patch of its own");

        LongArrayList line = new LongArrayList();
        LongArrayList square = new LongArrayList();
        for (int x = 0; x < 10; x++) {
            line.add(new BlockPos(x, 0, 0).asLong());
        }
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                square.add(new BlockPos(x, 0, z).asLong());
            }
        }
        double lineAspect = PatchStats.aspect(line);
        double squareAspect = PatchStats.aspect(square);
        log(String.format(Locale.ROOT, "patch shape: a line of ten has an elongation of %.2f, a 5x5 square %.2f", lineAspect, squareAspect));
        helper.assertTrue(lineAspect > 5.0 && squareAspect < 1.05, "a line should be far more elongated than a square: " + lineAspect + " / " + squareAspect);

        double[] eigenvalues = PatchStats.eigenvalues(2.0, 3.0, 4.0, 0.5, 0.25, 0.75);
        helper.assertTrue(Math.abs(eigenvalues[0] + eigenvalues[1] + eigenvalues[2] - 9.0) < 1e-9 && eigenvalues[0] >= eigenvalues[1] && eigenvalues[1] >= eigenvalues[2],
                "the eigenvalues should sum to the trace, largest first: " + Arrays.toString(eigenvalues));
        double[] diagonal = PatchStats.eigenvalues(1.0, 5.0, 3.0, 0.0, 0.0, 0.0);
        helper.assertTrue(diagonal[0] == 5.0 && diagonal[1] == 3.0 && diagonal[2] == 1.0, "a diagonal matrix's eigenvalues are its diagonal: " + Arrays.toString(diagonal));
        helper.succeed();
    }

    // Seeds with a budget of 3 grow patches of at most 3 pieces beyond themselves, at most 3 blocks out, in about a day, made of
    // pieces that never reproduce or corrupt anything; when they are done every piece has settled and the whole thing costs no
    // block entities. Sixteen seeds, spread out so their patches stay apart, because a single ragged patch says little.
    @GameTest(template = TEMPLATE, batch = "gt_patch_growth", timeoutTicks = LONG_TIMEOUT)
    public static void creeperSeedsGrowBoundedSterilePatches(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        ensureRandomTickSpeed(arena.level);
        PatchGrowth.override(PATCHES);

        int budget = 3;
        // Moss x64 so that any piece that DID roll for corrupting terrain would show as a Flower Block; Bone Meal x1 and the
        // seeds marked done so that they have no lineage left to roll with either.
        GardenBagContents contents = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1).add(Items.MOSS_BLOCK, 64).contents();
        List<BlockPos> seeds = new ArrayList<>();
        for (int gx = 0; gx < 4; gx++) {
            for (int gz = 0; gz < 4; gz++) {
                BlockPos seed = arena.at(4 + gx * 10, 1, 4 + gz * 10);
                placeSeed(arena.level, seed, contents, budget);
                seeds.add(seed);
            }
        }

        Simulation[] current = {Simulation.start(arena, 1)};
        helper.assertTrue(current[0] != null, "a day simulation was already running");
        int[] stage = {0};

        helper.succeedWhen(() -> {
            helper.assertTrue(current[0].result != null, "simulation still running");
            List<BlockPos> pieces = arena.creeperPositions();
            if (current[0].result.errors() > 0) {
                failNow(helper, current[0].result.errors() + " exceptions while ticking plants (see the log)");
            }

            if (stage[0] == 0) {
                // A day in: some pieces are still growing, and none of them is a member of the lineage.
                for (BlockPos pos : pieces) {
                    if (seeds.contains(pos)) {
                        continue;
                    }
                    if (arena.level.getBlockEntity(pos) instanceof CreeperBlockEntity piece) {
                        if (!piece.isPiece() || !piece.lineageDone() || piece.garden() == GardenRegistry.NO_GARDEN) {
                            failNow(helper, "a piece at " + pos + " is not a sterile piece of its seed's garden: piece " + piece.isPiece() + ", done " + piece.lineageDone() + ", garden " + piece.garden());
                        }
                        if (piece.energy() <= 0 || piece.energy() >= budget) {
                            failNow(helper, "the growing end of a patch at " + pos + " has a budget of " + piece.energy() + ", the seeds had " + budget);
                        }
                    }
                }
                stage[0] = 1;
                current[0] = Simulation.start(arena, 3);
                helper.assertTrue(false, "the patches have a few more days to finish");
            }

            // Finished: everything settled, nothing left holding data, all within the budget's reach of a seed.
            double average = (double) pieces.size() / seeds.size();
            log(String.format(Locale.ROOT, "patches: %d pieces from %d seeds with a budget of %d (%.1f each)%n%s", pieces.size(), seeds.size(), budget, average, arena.map()));
            int reach = 0;
            for (BlockPos pos : pieces) {
                int nearest = Integer.MAX_VALUE;
                for (BlockPos seed : seeds) {
                    nearest = Math.min(nearest, Math.max(Math.abs(pos.getX() - seed.getX()), Math.abs(pos.getZ() - seed.getZ())));
                }
                reach = Math.max(reach, nearest);
                BlockState state = arena.level.getBlockState(pos);
                if (!DiseasedPlantLogic.isSettled(state) || arena.level.getBlockEntity(pos) != null) {
                    failNow(helper, "a creeper at " + pos + " never finished: " + state);
                }
            }
            helper.assertTrue(average >= 2.0 && average <= 1 + budget, "a patch with a budget of " + budget + " should average between 2 and " + (1 + budget) + " pieces with its seed, averaged " + average);
            helper.assertTrue(pieces.size() <= seeds.size() * (1 + budget), "a patch grew past its budget: " + pieces.size() + " pieces from " + seeds.size() + " seeds");
            helper.assertTrue(reach <= budget, "a patch reaches " + reach + " blocks from its seed, which had a budget of " + budget);
            helper.assertTrue(arena.countFlowerBlocks() == 0, "patch pieces must not corrupt terrain, found " + arena.countFlowerBlocks() + " Flower Blocks");
            failOnProblems(helper, arena.validate());
            PatchGrowth.override(null);
            arena.cleanup();
        });
    }

    // What the patches look like: 108 seeds on flat ground, each with its own style and budget from the bell curve, grown to the end
    // (four at a time and far apart, so that no two patches meet). The old flood fill made round blobs of very different sizes; a
    // patch here is bounded by its budget, and most are clearly elongated.
    @GameTest(template = TEMPLATE, batch = "gt_patch_shape", timeoutTicks = LONG_TIMEOUT)
    public static void creeperPatchesAreBoundedAndMostlyElongated(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            PatchGrowth.override(PATCHES);
            GardenBagContents contents = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1).contents();
            RandomSource random = arena.level.getRandom();

            int patches = 0;
            int pieces = 0;
            int biggest = 0;
            int shaped = 0;
            int roundOnes = 0;
            int[] histogram = new int[PATCHES.maxPieces() + 2];
            String firstMap = "";
            for (int round = 0; round < 27; round++) {
                for (int gx = 0; gx < 2; gx++) {
                    for (int gz = 0; gz < 2; gz++) {
                        placeSeed(arena.level, arena.at(12 + gx * 24, 1, 12 + gz * 24), contents, PatchGrowth.roll(random), true);
                    }
                }
                growPatches(arena);

                PatchStats stats = PatchStats.of(arena.creeperCells());
                patches += stats.patches;
                pieces += stats.pieces;
                biggest = Math.max(biggest, stats.biggest());
                shaped += stats.shaped;
                roundOnes += stats.round;
                for (int size : stats.sizes) {
                    histogram[Math.min(size, histogram.length - 1)]++;
                }
                if (round == 0) {
                    firstMap = arena.map();
                }
                failOnProblems(helper, arena.validate());
                arena.clearCreepers();
            }

            double mean = (double) pieces / patches;
            log(String.format(Locale.ROOT, "patch shapes: %d patches, %.1f pieces on average, biggest %d, %d of %d patches of 4 pieces or more are round%nsizes 1..%d+: %s%n%s",
                    patches, mean, biggest, roundOnes, shaped, histogram.length - 1, Arrays.toString(Arrays.copyOfRange(histogram, 1, histogram.length)), firstMap));
            helper.assertTrue(biggest <= PATCHES.maxPieces() + 1, "a patch has " + biggest + " pieces; the budget caps it at " + (PATCHES.maxPieces() + 1) + " with its seed");
            helper.assertTrue(mean >= 3.0 && mean <= 9.0, "patches should average between 3 and 9 pieces, averaged " + mean);
            helper.assertTrue(shaped >= 40 && roundOnes < shaped * 0.35, roundOnes + " of " + shaped + " patches are round - most should be clearly elongated");
        } finally {
            PatchGrowth.override(null);
            arena.cleanup();
        }
        helper.succeed();
    }

    // A tendril turns away from where it would be crowded: with a crowding of 1 patches keep apart, with 8 nothing holds them off and
    // seeds only five blocks apart run into one another. Sixty-four seeds each way, on the same arena.
    @GameTest(template = TEMPLATE, batch = "gt_patch_crowding", timeoutTicks = LONG_TIMEOUT)
    public static void crowdingKeepsPatchesApart(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            GardenBagContents contents = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1).contents();
            RandomSource random = arena.level.getRandom();
            int[] biggest = new int[2];
            int[] touching = new int[2];
            for (int variant = 0; variant < 2; variant++) {
                PatchGrowth.override(new PatchGrowth.Settings(12, 0.33, 1.0, 1.0, 1.0, variant == 0 ? 1 : 8, 1.0, 0.0, 6));
                for (int gx = 0; gx < 8; gx++) {
                    for (int gz = 0; gz < 8; gz++) {
                        placeSeed(arena.level, arena.at(4 + gx * 5, 1, 4 + gz * 5), contents, PatchGrowth.roll(random), true);
                    }
                }
                growPatches(arena);
                PatchStats stats = PatchStats.of(arena.creeperCells());
                biggest[variant] = stats.biggest();
                touching[variant] = 64 - stats.patches;
                log(String.format(Locale.ROOT, "patch crowding %d: 64 seeds five blocks apart made %d separate patches, the biggest of %d pieces (%d pieces in all)",
                        variant == 0 ? 1 : 8, stats.patches, stats.biggest(), stats.pieces));
                arena.clearCreepers();
            }
            helper.assertTrue(touching[1] > touching[0] + 8 && biggest[1] > biggest[0], "with a crowding of 8 the patches should run into one another far more than with 1: merged seeds "
                    + touching[1] + " against " + touching[0] + ", biggest patch " + biggest[1] + " against " + biggest[0]);
        } finally {
            PatchGrowth.override(null);
            arena.cleanup();
        }
        helper.succeed();
    }

    // Going down a wall to its lower edge, a tendril may hang a strand of pieces straight down, held only by the piece above like a
    // vine; take away what holds the top of the strand and it all comes down. Here the wall is a single block, so the piece clinging to
    // it is the top of the strand.
    @GameTest(template = TEMPLATE, batch = "gt_patch_hang", timeoutTicks = 200)
    public static void tendrilsHangStrandsAndTheyFallWhenTheirHoldIsGone(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            // Always grows, never turns, hangs at once at the edge, and strands are at most four pieces.
            PatchGrowth.override(new PatchGrowth.Settings(12, 1.0, 0.0, 0.0, 0.0, 8, 1.0, 1.0, 4));
            BlockPos wall = arena.at(24, 6, 24);
            arena.level.setBlock(wall, Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            BlockPos top = wall.south();
            GardenBagContents contents = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1).contents();
            arena.level.setBlock(top, FlowerDisease.SUNFLOWER_CREEPER.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true), SettleTable.PLACEMENT_FLAGS);
            CreeperBlockEntity seed = (CreeperBlockEntity) arena.level.getBlockEntity(top);
            seed.startGarden(arena.level, contents, top);
            seed.startPatch(10, 0, Direction.NORTH, Direction.DOWN);
            seed.markLineageDone();

            growPatches(arena);

            List<BlockPos> strand = new ArrayList<>();
            for (BlockPos pos : arena.creeperPositions()) {
                if (!pos.equals(top)) {
                    strand.add(pos);
                }
            }
            log("hanging strand: " + strand.size() + " pieces under the piece on the wall block\n" + arena.map());
            helper.assertTrue(strand.size() >= 1 && strand.size() <= 4, "expected a strand of 1 to 4 pieces, found " + strand.size());
            for (BlockPos pos : strand) {
                BlockState state = arena.level.getBlockState(pos);
                helper.assertTrue(pos.getX() == top.getX() && pos.getZ() == top.getZ() && pos.getY() < top.getY(), "a hanging piece at " + pos + " is not straight under the top one");
                helper.assertTrue(MultifaceBlock.hasFace(state, Direction.NORTH) && MultifaceBlock.availableFaces(state).size() == 1, "a hanging piece should have just the wall's face: " + state);
                helper.assertTrue(arena.level.getBlockState(pos.north()).isAir(), "a hanging piece at " + pos + " has something behind it, it is not hanging");
                helper.assertTrue(state.canSurvive(arena.level, pos), "a hanging piece at " + pos + " is not held");
                helper.assertTrue(DiseasedPlantLogic.isSettled(state) || arena.level.getBlockEntity(pos) != null, "an unsettled hanging piece at " + pos + " has no block entity");
            }
            failOnProblems(helper, arena.validate());

            // The wall goes: the piece on it has nothing to hold it and the strand under it nothing to hang from, one after the other.
            arena.level.setBlock(wall, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            helper.assertTrue(arena.creeperPositions().isEmpty(), "the strand should have come down with its wall, but " + arena.creeperPositions().size() + " pieces are left");
            arena.discardDroppedItems();
        } finally {
            PatchGrowth.override(null);
            arena.cleanup();
        }
        helper.succeed();
    }

    // Reproduction never hangs a piece: with a wall that ends and open air below it, every creeper it places (seeds and all) has
    // something solid behind one of its faces. (Only patches hang, and only under the wall's lower edge.)
    @GameTest(template = TEMPLATE, batch = "gt_patch_nohang", timeoutTicks = 400)
    public static void reproductionNeverHangsAPiece(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            PatchGrowth.override(new PatchGrowth.Settings(0, 0.33, 1.0, 1.0, 1.0, 3, 1.0, 1.0, 6));
            for (int x = 14; x <= 34; x++) {
                for (int y = 4; y <= 8; y++) {
                    arena.level.setBlock(arena.at(x, y, 24), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            RandomSource random = arena.level.getRandom();
            // Planted on the south face of the wall, at the height of its lower row, so that the garden has the wall's lower edge and the
            // open air under it to tempt it.
            Bag bag = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.SLIME_BALL, 64).lifetime(1000);
            plantVia(helper, arena, bag, arena.at(24, 4, 25), Direction.SOUTH);
            for (int i = 0; i < 30; i++) {
                for (BlockPos pos : arena.creeperPositions()) {
                    BlockState state = arena.level.getBlockState(pos);
                    if (state.isRandomlyTicking()) {
                        state.randomTick(arena.level, pos, random);
                    }
                }
            }

            List<BlockPos> creepers = arena.creeperPositions();
            log("no hanging by reproduction: " + creepers.size() + " creeper pieces\n" + arena.map());
            helper.assertTrue(creepers.size() >= 3, "expected the wall's garden to have grown, found " + creepers.size() + " pieces");
            for (BlockPos pos : creepers) {
                BlockState state = arena.level.getBlockState(pos);
                boolean solid = false;
                for (Direction face : Direction.values()) {
                    solid |= MultifaceBlock.hasFace(state, face) && MultifaceBlock.canAttachTo(arena.level, face, pos.relative(face), arena.level.getBlockState(pos.relative(face)));
                }
                helper.assertTrue(solid, "a creeper at " + pos + " has nothing solid to cling to (" + state + ") - reproduction hung a piece");
            }
        } finally {
            PatchGrowth.override(null);
            arena.cleanup();
        }
        helper.succeed();
    }

    // The tendrils on a body with edges and corners: a block of stone floating in the air, with seeds near the edges of its top. They
    // follow the surface over the edges and down the sides and, where a side ends, hang a strand or wrap under; every piece is held by
    // something, everything settles, and when the block goes every piece of every patch goes with it - the strands cascade down.
    @GameTest(template = TEMPLATE, batch = "gt_patch_3d", timeoutTicks = LONG_TIMEOUT)
    public static void patchesFollowEdgesAndCornersOfAFloatingBlock(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            PatchGrowth.override(new PatchGrowth.Settings(12, 0.33, 1.0, 1.0, 1.0, 3, 1.0, 0.5, 6));
            for (int x = 20; x <= 27; x++) {
                for (int z = 20; z <= 27; z++) {
                    for (int y = 9; y <= 11; y++) {
                        arena.level.setBlock(arena.at(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }

            GardenBagContents contents = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1).contents();
            RandomSource random = arena.level.getRandom();
            int sides = 0;
            int undersides = 0;
            int hanging = 0;
            int pieces = 0;
            for (int round = 0; round < 30; round++) {
                for (int seed = 0; seed < 10; seed++) {
                    // Within two blocks of the edge of the top, so that a tendril soon has an edge to go over.
                    int along = 20 + random.nextInt(8);
                    int edge = random.nextBoolean() ? 20 + random.nextInt(2) : 26 + random.nextInt(2);
                    BlockPos at = random.nextBoolean() ? arena.at(along, 12, edge) : arena.at(edge, 12, along);
                    if (arena.level.isEmptyBlock(at)) {
                        placeSeed(arena.level, at, contents, PatchGrowth.roll(random), true);
                    }
                }
                growPatches(arena);
                failOnProblems(helper, arena.validate());

                for (BlockPos pos : arena.creeperPositions()) {
                    BlockState state = arena.level.getBlockState(pos);
                    pieces++;
                    boolean hangs = false;
                    for (Direction face : Direction.values()) {
                        if (MultifaceBlock.hasFace(state, face) && !MultifaceBlock.canAttachTo(arena.level, face, pos.relative(face), arena.level.getBlockState(pos.relative(face)))) {
                            hangs = true;
                        }
                    }
                    hanging += hangs ? 1 : 0;
                    sides += Direction.Plane.HORIZONTAL.stream().anyMatch(face -> MultifaceBlock.hasFace(state, face)) ? 1 : 0;
                    undersides += MultifaceBlock.hasFace(state, Direction.UP) ? 1 : 0;
                }
                if (round < 29) {
                    arena.clearCreepers();
                }
            }
            log("patches on a floating block: " + pieces + " pieces over 30 rounds, " + sides + " on a side, " + undersides + " under it, " + hanging + " hanging\n" + arena.map());
            helper.assertTrue(sides >= 20, "tendrils should have gone over the edges and down the sides, only " + sides + " pieces are on a side");
            helper.assertTrue(undersides >= 1, "some tendril should have wrapped under the block");
            helper.assertTrue(hanging >= 1, "some tendril should have hung a strand below a lower edge");

            // The block goes, cell by cell, with the updates a player breaking it would cause: nothing may be left behind, hanging or not.
            List<BlockPos> block = new ArrayList<>();
            for (int x = 20; x <= 27; x++) {
                for (int z = 20; z <= 27; z++) {
                    for (int y = 9; y <= 11; y++) {
                        block.add(arena.at(x, y, z));
                    }
                }
            }
            for (BlockPos pos : block) {
                arena.level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            helper.assertTrue(arena.creeperPositions().isEmpty(), "pieces were left in the air when their block went: " + arena.creeperPositions().size());
            arena.discardDroppedItems();
        } finally {
            PatchGrowth.override(null);
            arena.cleanup();
        }
        helper.succeed();
    }

    // No energy, no patch: it is one piece, and once its own life is over it settles and keeps no block entity.
    @GameTest(template = TEMPLATE, batch = "gt_patch_none", timeoutTicks = 200)
    public static void creeperWithoutBudgetStaysASinglePiece(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            // Bone Meal x1: the generation cap gives it no children, so its life ends at its first tick.
            GardenBagContents contents = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1).contents();
            BlockPos pos = arena.at(24, 1, 24);
            placeSeed(arena.level, pos, contents, 0, false);
            tickUntilSettled(arena.level, pos, arena.level.getRandom());

            BlockState state = arena.level.getBlockState(pos);
            helper.assertTrue(state.getBlock() instanceof CreepingFlowerBlock && DiseasedPlantLogic.isSettled(state), "expected a settled creeper, found " + state);
            helper.assertTrue(arena.level.getBlockEntity(pos) == null, "a settled creeper keeps no block entity");
            helper.assertTrue(arena.creeperPositions().size() == 1, "with no budget there should be one piece, found " + arena.creeperPositions().size());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // The seed is still a member of the lineage: it reproduces (placing seeds of its own, each with a patch to grow)
    // while only its patch is sterile.
    @GameTest(template = TEMPLATE, batch = "gt_patch_seeds", timeoutTicks = 200)
    public static void creeperSeedsKeepReproducing(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).lifetime(1000);
            plantVia(helper, arena, bag, arena.at(24, 1, 24));

            int seeds = 0;
            for (BlockPos pos : arena.creeperPositions()) {
                if (arena.level.getBlockEntity(pos) instanceof CreeperBlockEntity creeper && !creeper.isPiece() && !creeper.lineageDone() && creeper.garden() != GardenRegistry.NO_GARDEN) {
                    seeds++;
                }
            }
            log("creeper lineage: " + arena.creeperPositions().size() + " creeper pieces, " + seeds + " of them seeds still reproducing\n" + arena.map());
            helper.assertTrue(seeds >= 1, "the planted creeper should have left children that are still seeds, found " + seeds);
            failOnProblems(helper, arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // A creeper saved before tendrils has only its energy (and whether its life is over): with no face, heading or style it reads as a
    // streak on its down face, and still grows a patch and finishes.
    @GameTest(template = TEMPLATE, batch = "gt_patch_legacy", timeoutTicks = 200)
    public static void creepersSavedBeforeTendrilsStillGrow(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            PatchGrowth.override(PATCHES);
            GardenBagContents contents = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1).contents();
            // On the south face of a wall, where the face an old creeper reads as having (down) is not one it has.
            for (int x = 21; x <= 27; x++) {
                for (int y = 1; y <= 6; y++) {
                    arena.level.setBlock(arena.at(x, y, 24), Blocks.STONE_BRICKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            BlockPos pos = arena.at(24, 3, 25);
            placeSeed(arena.level, pos, contents, 4, true);
            arena.level.setBlock(pos, FlowerDisease.SUNFLOWER_CREEPER.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.NORTH), true), SettleTable.PLACEMENT_FLAGS);
            CompoundTag saved = arena.level.getBlockEntity(pos).saveWithFullMetadata(arena.level.registryAccess());
            for (String key : List.of("Face", "Heading", "Style", "Hang")) {
                saved.remove(key);
            }
            helper.assertTrue(saved.getByte("Energy") == 4 && saved.getBoolean("LineageDone"), "the old-style tag should still say how much energy it has: " + saved);

            BlockEntity old = BlockEntity.loadStatic(pos, arena.level.getBlockState(pos), saved, arena.level.registryAccess());
            helper.assertTrue(old instanceof CreeperBlockEntity, "an old creeper did not load");
            arena.level.setBlockEntity(old);
            growPatches(arena);

            int pieces = arena.creeperPositions().size();
            log("an old creeper with an energy of 4 grew " + pieces + " pieces\n" + arena.map());
            helper.assertTrue(pieces >= 2 && pieces <= 5, "an old creeper with an energy of 4 should grow a patch of up to 5 pieces, found " + pieces);
            failOnProblems(helper, arena.validate());
        } finally {
            PatchGrowth.override(null);
            arena.cleanup();
        }
        helper.succeed();
    }

    // ---- Growing over other plants (Fermented Spider Eye) ------------------------------------------------

    // A bag with a Fermented Spider Eye ignores the other species - and grows over the plants that are not of its pool, destroying
    // them without drops: the root, and every child, on a ground where every cell holds a poppy or a lilac (two blocks tall) and the
    // garden is dandelions. Without the eye none of them is touched.
    @GameTest(template = TEMPLATE, batch = "gt_overgrow", timeoutTicks = 400)
    public static void aGardenThatIgnoresTheOthersGrowsOverPlantsOutsideItsPool(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            arena.discardDroppedItems();
            for (boolean eye : new boolean[]{false, true}) {
                int foreign = coverWithForeignPlants(arena);
                Bag bag = new Bag().add(Items.DANDELION, 1).add(Items.SLIME_BALL, 64).lifetime(1000);
                if (eye) {
                    bag.add(Items.FERMENTED_SPIDER_EYE, 1);
                }

                BlockPos root = arena.at(24, 1, 24);
                Component failure = GardenBagItem.plant(arena.level, root, bag.contents(), Direction.UP);
                if (!eye) {
                    helper.assertTrue(failure != null, "a garden that respects the others should not plant over a poppy");
                    helper.assertTrue(arena.countBlocks(Blocks.POPPY) + arena.countBlocks(Blocks.LILAC) == foreign, "nothing may be touched without the eye");
                    arena.clearAllPlants();
                    continue;
                }

                helper.assertTrue(failure == null, "planting over a poppy with the eye failed: " + failure);
                int dandelions = arena.countBlocks(Blocks.DANDELION) + arena.countBlocks(FlowerDisease.DISEASED_DANDELION.get());
                int left = arena.countBlocks(Blocks.POPPY) + arena.countBlocks(Blocks.LILAC);
                log(String.format(Locale.ROOT, "growing over: %d dandelions took the place of %d of %d poppies and lilacs%n%s", dandelions, foreign - left, foreign, arena.map()));
                helper.assertTrue(dandelions >= 3, "expected the garden to have spread over the plants around it, found " + dandelions + " dandelions");
                helper.assertTrue(foreign - left >= dandelions - 1, "every dandelion but a lucky few should have taken the place of a plant: " + dandelions + " dandelions, " + (foreign - left) + " plants gone");
                helper.assertTrue(arena.droppedItems().isEmpty(), "growing over a plant must not drop anything, found " + arena.droppedItems().size() + " items");
                failOnProblems(helper, arena.validate());
            }
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // Poppies everywhere on the ground of the middle of the arena, with a lilac in place of every sixteenth: the plants a garden that
    // is not made of them can grow over. Returns how many blocks that is (a lilac is two).
    private static int coverWithForeignPlants(Arena arena) {
        int count = 0;
        for (int x = 9; x <= 38; x++) {
            for (int z = 9; z <= 38; z++) {
                BlockPos pos = arena.at(x, 1, z);
                if (x % 4 == 0 && z % 4 == 0) {
                    DoublePlantBlock.placeAt(arena.level, Blocks.LILAC.defaultBlockState(), pos, SettleTable.PLACEMENT_FLAGS);
                    count += 2;
                } else {
                    arena.level.setBlock(pos, Blocks.POPPY.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                    count++;
                }
            }
        }
        return count;
    }

    // The same for a tall garden - a two-block child takes over a poppy and the cell above it, or a lilac whole - and for the patches of
    // a creeper garden, which grow over the poppies on the ground the way they would over bare ground.
    @GameTest(template = TEMPLATE, batch = "gt_overgrow_shapes", timeoutTicks = LONG_TIMEOUT)
    public static void tallChildrenAndPatchesGrowOverPlantsToo(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            arena.discardDroppedItems();
            int foreign = coverWithForeignPlants(arena);
            Bag tall = new Bag().add(Items.SUNFLOWER, 1).add(Items.SLIME_BALL, 64).add(Items.FERMENTED_SPIDER_EYE, 1).lifetime(1000);
            Component failure = GardenBagItem.plant(arena.level, arena.at(24, 1, 24), tall.contents(), Direction.UP);
            helper.assertTrue(failure == null, "planting a tall flower over a poppy with the eye failed: " + failure);
            int sunflowers = arena.countBlocks(FlowerDisease.DISEASED_SUNFLOWER.get()) + arena.countBlocks(Blocks.SUNFLOWER);
            log("growing over, tall: " + sunflowers + " sunflower blocks (both halves) among the poppies and lilacs\n" + arena.map());
            helper.assertTrue(sunflowers >= 4, "expected tall children that took the place of the plants around, found " + sunflowers + " sunflower blocks");
            helper.assertTrue(arena.droppedItems().isEmpty(), "growing over a plant must not drop anything, found " + arena.droppedItems().size() + " items");
            failOnProblems(helper, arena.validate());
            arena.clearAllPlants();

            // A creeper's patch: the ground is covered with poppies right up to the seed, and every piece of the patch takes one's place.
            PatchGrowth.override(PATCHES);
            for (boolean eye : new boolean[]{true, false}) {
                coverWithForeignPlants(arena);
                Bag creeper = new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).add(Items.BONE_MEAL, 1);
                if (eye) {
                    creeper.add(Items.FERMENTED_SPIDER_EYE, 1);
                }
                BlockPos seed = arena.at(23, 1, 23);
                arena.level.setBlock(seed, Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                placeSeed(arena.level, seed, creeper.contents(), new PatchGrowth.Plan(PatchGrowth.Style.STREAK, 8), true);
                int before = arena.countBlocks(Blocks.POPPY) + arena.countBlocks(Blocks.LILAC);
                growPatches(arena);
                int pieces = arena.creeperPositions().size();
                int gone = before - (arena.countBlocks(Blocks.POPPY) + arena.countBlocks(Blocks.LILAC));
                log("creeper patch " + (eye ? "with" : "without") + " the eye: " + pieces + " pieces, " + gone + " plants gone (of " + foreign + ")");
                if (eye) {
                    helper.assertTrue(pieces >= 3 && gone >= pieces - 1 - 1, "the patch should have grown over the plants: " + pieces + " pieces, " + gone + " plants gone");
                    helper.assertTrue(arena.droppedItems().isEmpty(), "a patch growing over plants must not drop anything, found " + arena.droppedItems().size() + " items");
                } else {
                    helper.assertTrue(pieces == 1 && gone == 0, "a patch that respects the plants around it has nowhere to grow: " + pieces + " pieces, " + gone + " plants gone");
                }
                arena.clearAllPlants();
            }
            failOnProblems(helper, arena.validate());
        } finally {
            PatchGrowth.override(null);
            arena.cleanup();
        }
        helper.succeed();
    }

    // What a garden counts as its own kind - for the crowding of a garden that ignores the others, and for what it grows over: the
    // species of its pool, their Diseased forms and the vanilla ones they settle into. Anything else that is a plant is foreign.
    @GameTest(template = TEMPLATE, batch = "gt_family", timeoutTicks = 100)
    public static void aGardensOwnKindIncludesTheDiseasedFormsOfItsSpecies(GameTestHelper helper) {
        List<SettleTable.Option> pool = List.of(new SettleTable.Option(Blocks.POPPY, 3), new SettleTable.Option(Blocks.DANDELION, 2), new SettleTable.Option(FlowerDisease.SUNFLOWER_CREEPER.get(), 1));
        Block self = FlowerDisease.DISEASED_POPPY.get();
        Block fallback = Blocks.POPPY;
        BlockState lilacLower = Blocks.LILAC.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER);
        BlockState lilacUpper = Blocks.LILAC.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);

        for (BlockState own : List.of(FlowerDisease.DISEASED_POPPY.get().defaultBlockState(), FlowerDisease.DISEASED_DANDELION.get().defaultBlockState(),
                Blocks.POPPY.defaultBlockState(), Blocks.DANDELION.defaultBlockState(), FlowerDisease.SUNFLOWER_CREEPER.get().defaultBlockState())) {
            helper.assertTrue(SettleTable.isSameSpecies(own, self, fallback, pool), own + " is of the pool's kind");
            helper.assertTrue(!SettleTable.isForeignPlant(own, self, fallback, pool), own + " must not be grown over");
        }
        for (BlockState foreign : List.of(FlowerDisease.DISEASED_ALLIUM.get().defaultBlockState(), Blocks.ALLIUM.defaultBlockState(), Blocks.OAK_SAPLING.defaultBlockState(),
                FlowerDisease.LILAC_CREEPER.get().defaultBlockState(), lilacLower)) {
            helper.assertTrue(SettleTable.isForeignPlant(foreign, self, fallback, pool), foreign + " is a plant outside the pool");
        }
        helper.assertTrue(!SettleTable.isForeignPlant(lilacUpper, self, fallback, pool), "the upper half of a tall plant is never met on its own");
        helper.assertTrue(!SettleTable.isForeignPlant(Blocks.STONE.defaultBlockState(), self, fallback, pool) && !SettleTable.isForeignPlant(Blocks.AIR.defaultBlockState(), self, fallback, pool),
                "stone and air are not plants");
        helper.succeed();
    }

    // Grows every patch in the arena to its end, by random-ticking the pieces that still grow the way days of ticks would.
    private static void growPatches(Arena arena) {
        RandomSource random = arena.level.getRandom();
        for (int guard = 0; guard < 5000; guard++) {
            List<BlockPos> active = arena.growingCreepers();
            if (active.isEmpty()) {
                return;
            }
            for (BlockPos pos : active) {
                BlockState state = arena.level.getBlockState(pos);
                if (state.isRandomlyTicking()) {
                    state.randomTick(arena.level, pos, random);
                }
            }
        }
        throw new IllegalStateException("the patches never finished growing");
    }

    // ---- Disease Powder -----------------------------------------------------------------------------

    // Used on a plant of a garden, it lives its day and the garden next door - well within reach - does not.
    @GameTest(template = TEMPLATE, batch = "gt_powder_garden", timeoutTicks = LONG_TIMEOUT)
    public static void diseasePowderAdvancesOnlyTheClickedGarden(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        ensureRandomTickSpeed(arena.level);
        Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(50);
        plantVia(helper, arena, bag, arena.at(8, 1, 24));
        plantVia(helper, arena, bag, arena.at(40, 1, 24));

        Map<BlockPos, int[]> before = arena.growingPlants();
        Set<Integer> gardens = new java.util.TreeSet<>();
        before.values().forEach(entry -> gardens.add(entry[0]));
        helper.assertTrue(gardens.size() == 2, "expected growing plants of two gardens, found " + gardens);

        BlockPos clicked = null;
        int clickedGarden = 0;
        for (Map.Entry<BlockPos, int[]> entry : before.entrySet()) {
            if (clicked == null || entry.getKey().getX() < clicked.getX()) {
                clicked = entry.getKey();
                clickedGarden = entry.getValue()[0];
            }
        }
        helper.assertTrue(DiseasePowderItem.advance(arena.level, clicked, null) == DiseasePowderItem.Outcome.STARTED, "the powder did not start");
        int untouched = 0;
        for (int garden : gardens) {
            untouched = garden != clickedGarden ? garden : untouched;
        }
        int clickedId = clickedGarden;
        int untouchedId = untouched;

        helper.succeedWhen(() -> {
            helper.assertTrue(!DayAdvance.isRunning(), "the powder is still working");
            Map<BlockPos, int[]> after = arena.growingPlants();
            if (!sameGarden(before, untouchedId, after)) {
                failNow(helper, "the garden that was not clicked changed");
            }
            if (sameGarden(before, clickedId, after)) {
                failNow(helper, "the garden that was clicked did not live its day");
            }
            arena.cleanup();
        });
    }

    // Used on plain ground - or on a flower that has settled and so remembers no garden - it advances everything growing
    // within reach.
    @GameTest(template = TEMPLATE, batch = "gt_powder_area", timeoutTicks = LONG_TIMEOUT)
    public static void diseasePowderOnAnythingElseAdvancesEveryGardenNearby(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        ensureRandomTickSpeed(arena.level);
        Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).lifetime(50);
        plantVia(helper, arena, bag, arena.at(8, 1, 24));
        plantVia(helper, arena, bag, arena.at(40, 1, 24));

        Map<BlockPos, int[]> before = arena.growingPlants();
        Set<Integer> gardens = new java.util.TreeSet<>();
        before.values().forEach(entry -> gardens.add(entry[0]));
        helper.assertTrue(gardens.size() == 2, "expected growing plants of two gardens, found " + gardens);

        // Bare ground in the middle, with nothing growing on or above it.
        BlockPos ground = arena.at(24, 0, 44);
        helper.assertTrue(DiseasePowderItem.gardenAt(arena.level, ground) == GardenRegistry.NO_GARDEN, "the ground should belong to no garden");
        helper.assertTrue(DiseasePowderItem.advance(arena.level, ground, null) == DiseasePowderItem.Outcome.STARTED, "the powder did not start");

        helper.succeedWhen(() -> {
            helper.assertTrue(!DayAdvance.isRunning(), "the powder is still working");
            Map<BlockPos, int[]> after = arena.growingPlants();
            for (int garden : gardens) {
                if (sameGarden(before, garden, after)) {
                    failNow(helper, "garden #" + garden + " did not advance");
                }
            }
            arena.cleanup();
        });
    }

    // With nothing growing nearby it says so and is not used up (the item only shrinks when the outcome is STARTED).
    @GameTest(template = TEMPLATE, batch = "gt_powder_none", timeoutTicks = 200)
    public static void diseasePowderWithNothingGrowingDoesNothing(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            helper.assertTrue(DiseasePowderItem.advance(arena.level, arena.at(24, 0, 24), null) == DiseasePowderItem.Outcome.NOTHING_TO_ADVANCE, "expected nothing to advance");
            helper.assertTrue(!DayAdvance.isRunning(), "no simulation should have started");
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // Whether the growing plants of one garden are exactly what they were: the same places, at the same depth.
    private static boolean sameGarden(Map<BlockPos, int[]> before, int garden, Map<BlockPos, int[]> after) {
        return gardenOf(before, garden).equals(gardenOf(after, garden));
    }

    private static Map<BlockPos, Integer> gardenOf(Map<BlockPos, int[]> plants, int garden) {
        Map<BlockPos, Integer> result = new java.util.HashMap<>();
        plants.forEach((pos, entry) -> {
            if (entry[0] == garden) {
                result.put(pos, entry[1]);
            }
        });
        return result;
    }

    // Every creeper species can be picked in the bag, planted, and grows its own block.
    @GameTest(template = TEMPLATE, batch = "gt_creeper_species", timeoutTicks = 200)
    public static void everyCreeperSpeciesCanBePlanted(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            List<net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem>> items = List.of(
                    FlowerDisease.SUNFLOWER_CREEPER_ITEM, FlowerDisease.LILAC_CREEPER_ITEM, FlowerDisease.ROSE_BUSH_CREEPER_ITEM,
                    FlowerDisease.PEONY_CREEPER_ITEM, FlowerDisease.OXEYE_DAISY_CREEPER_ITEM);
            int spot = 0;
            for (var item : items) {
                helper.assertTrue(FlowerDisease.bagOutcomeItems().containsKey(item.get()), item.getId() + " cannot be picked in the bag");
                BlockPos pos = arena.at(6 + 8 * spot++, 1, 10);
                plantVia(helper, arena, new Bag().add(item.get(), 1).add(Items.BONE_MEAL, 1), pos);
                helper.assertTrue(arena.level.getBlockState(pos).getBlock() == item.get().getBlock(), item.getId() + " planted as " + arena.level.getBlockState(pos));
            }
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // Places a creeper on the ground with a patch of the given budget (of the branching style), the way a planting does, without the
    // burst. A seed whose life is already over (`lineageDone`, the default here) only grows its patch.
    private static void placeSeed(ServerLevel level, BlockPos pos, GardenBagContents contents, int budget) {
        placeSeed(level, pos, contents, budget, true);
    }

    private static void placeSeed(ServerLevel level, BlockPos pos, GardenBagContents contents, int budget, boolean lineageDone) {
        placeSeed(level, pos, contents, new PatchGrowth.Plan(PatchGrowth.Style.BRANCHY, budget), lineageDone);
    }

    private static void placeSeed(ServerLevel level, BlockPos pos, GardenBagContents contents, PatchGrowth.Plan plan, boolean lineageDone) {
        level.setBlock(pos, FlowerDisease.SUNFLOWER_CREEPER.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true), SettleTable.PLACEMENT_FLAGS);
        CreeperBlockEntity seed = (CreeperBlockEntity) level.getBlockEntity(pos);
        seed.startGarden(level, contents, pos);
        PatchGrowth.begin(seed, plan, Direction.DOWN, level.getRandom());
        if (lineageDone) {
            seed.markLineageDone();
        }
    }

    // A creeper's budget, its style, the face and heading of the tendril it ends, whether its life is over: all saved with it, and it
    // comes back as the right class.
    @GameTest(template = TEMPLATE, batch = "gt_reload_creeper", timeoutTicks = 100)
    public static void creeperDataSurvivesAReload(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            ServerLevel level = arena.level;
            BlockPos pos = arena.at(10, 1, 10);
            placeSeed(level, pos, new Bag().add(FlowerDisease.SUNFLOWER_CREEPER_ITEM.get(), 1).contents(), 4, true);
            CreeperBlockEntity original = (CreeperBlockEntity) level.getBlockEntity(pos);
            original.inherit(original.garden(), 6);
            original.startPiece(4, original.garden(), 2, Direction.NORTH, Direction.UP, 3);

            BlockEntity reloaded = BlockEntity.loadStatic(pos, level.getBlockState(pos), original.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
            helper.assertTrue(reloaded instanceof CreeperBlockEntity, "a creeper came back as " + (reloaded == null ? "nothing" : reloaded.getClass().getSimpleName()));
            CreeperBlockEntity copy = (CreeperBlockEntity) reloaded;
            helper.assertTrue(copy.energy() == 4 && copy.lineageDone() && copy.isPiece() && copy.garden() == original.garden(),
                    "a creeper forgot its data across a reload: energy " + copy.energy() + ", done " + copy.lineageDone() + ", piece " + copy.isPiece() + ", garden " + copy.garden());
            helper.assertTrue(copy.face() == Direction.NORTH && copy.heading() == Direction.UP && copy.style() == 2 && copy.hang() == 3,
                    "a creeper forgot which way it was growing across a reload: face " + copy.face() + ", heading " + copy.heading() + ", style " + copy.style() + ", hang " + copy.hang());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // A chunk that unloads and loads again rebuilds each block entity from its saved tag through the block entity TYPE
    // (BlockEntity#loadStatic), not through the block - so the type has to build the right class for every block that
    // uses it, or a Flower Block comes back as a plain plant entity that has forgotten the ground it replaced.
    @GameTest(template = TEMPLATE, batch = "gt_reload", timeoutTicks = 100)
    public static void blockEntitiesComeBackAsTheRightClassAfterAReload(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            ServerLevel level = arena.level;

            BlockPos flowerBlock = arena.at(10, 1, 10);
            level.setBlock(flowerBlock, FlowerDisease.FLOWER_BLOCK.get().defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
            FlowerMassBlockEntity original = (FlowerMassBlockEntity) level.getBlockEntity(flowerBlock);
            original.setReplacedState(Blocks.STONE.defaultBlockState());
            original.inheritFlowerBlock(5, 3, 1);
            BlockEntity reloaded = BlockEntity.loadStatic(flowerBlock, level.getBlockState(flowerBlock), original.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
            helper.assertTrue(reloaded instanceof FlowerMassBlockEntity, "a Flower Block came back as " + (reloaded == null ? "nothing" : reloaded.getClass().getSimpleName()));
            FlowerMassBlockEntity copy = (FlowerMassBlockEntity) reloaded;
            helper.assertTrue(copy.replacedState().is(Blocks.STONE) && copy.cap() == 3 && copy.garden() == 5 && copy.depth() == 1,
                    "a Flower Block forgot its data across a reload: " + copy.replacedState() + ", cap " + copy.cap() + ", garden " + copy.garden() + ", depth " + copy.depth());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    // What a pile of block entities costs, per entity: on disk (raw and compressed, the way a chunk holds them), on the
    // wire (the chunk packet a client receives), in memory once loaded from disk, and in time to load them. Logged for
    // the record; the asserts only catch a blow-up. Flower blocks and Diseased plants are measured separately since
    // they carry different data.
    @GameTest(template = TEMPLATE, batch = "gt_footprint", timeoutTicks = 1200)
    public static void blockEntityFootprint(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            RandomSource random = arena.level.getRandom();
            GardenBagContents profile = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.MOSS_BLOCK, 20).contents();
            // One garden for everything, like one big planting - what every block entity below points at.
            int garden = GardenRegistry.of(arena.level).create(profile, arena.at(0, 1, 0), 0);

            // Scattered cells and varied contents, like a real garden - a solid grid of identical entities would
            // compress far better than anything the game produces.
            List<BlockPos> flowerBlocks = arena.scatter(20_000, pos -> placeFlowerBlock(arena.level, pos, garden, random));
            Footprint flowerFootprint = Footprint.measure(arena, flowerBlocks, "flower blocks");
            arena.replaceWithStone(flowerBlocks);

            List<BlockPos> plants = arena.scatter(10_000, pos -> placePlant(arena.level, pos, garden, random));
            Footprint plantFootprint = Footprint.measure(arena, plants, "diseased plants");
            arena.replaceWithStone(plants);

            for (Footprint footprint : List.of(flowerFootprint, plantFootprint)) {
                log(footprint.describe());
                helper.assertTrue(footprint.gzipPerEntity() < 200, footprint.label + " take " + footprint.gzipPerEntity() + " compressed bytes each");
            }
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    private static final BlockState[] REPLACED_GROUND = {
            Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.SAND.defaultBlockState()
    };

    // A Flower Block the way the game leaves one: it remembers the ground it replaced and its garden, and while it is
    // still spreading also how deep it is and how far it may go.
    private static void placeFlowerBlock(ServerLevel level, BlockPos pos, int garden, RandomSource random) {
        boolean settled = random.nextBoolean();
        level.setBlock(pos, FlowerDisease.FLOWER_BLOCK.get().defaultBlockState().setValue(SettleTable.SETTLED, settled), SettleTable.PLACEMENT_FLAGS);
        if (level.getBlockEntity(pos) instanceof FlowerMassBlockEntity flowerBlock) {
            flowerBlock.setReplacedState(REPLACED_GROUND[random.nextInt(REPLACED_GROUND.length)]);
            flowerBlock.inheritFlowerBlock(garden, 1 + random.nextInt(5), random.nextInt(5));
            if (settled) {
                flowerBlock.settled();
            }
        }
    }

    // An active Diseased plant somewhere in a garden, a few generations deep.
    private static void placePlant(ServerLevel level, BlockPos pos, int garden, RandomSource random) {
        level.setBlock(pos, FlowerDisease.DISEASED_POPPY.get().defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
        if (level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity plant) {
            plant.inherit(garden, random.nextInt(60));
        }
    }

    // ---- Shared scenarios ----------------------------------------------------------------------------

    private static void growth(GameTestHelper helper, String label, Bag bag, int days, int density) {
        growth(helper, label, bag, days, density, true);
    }

    // `checkDensity` false leaves the density out of the asserts (it is still logged): for a lifetime too short for the garden to fill.
    private static void growth(GameTestHelper helper, String label, Bag bag, int days, int density, boolean checkDensity) {
        Arena arena = new Arena(helper);
        arena.prepare();
        ensureRandomTickSpeed(arena.level);

        // Five plantings, so that measuring how a garden fills up does not hang on one lineage surviving: any single plant's
        // descendants die out about 1 time in 8 at a lifetime of 8 (a geometric offspring count with mean 8).
        BlockPos root = arena.at(24, 1, 24);
        plantVia(helper, arena, bag, root);
        for (BlockPos extra : List.of(arena.at(12, 1, 12), arena.at(36, 1, 12), arena.at(12, 1, 36), arena.at(36, 1, 36))) {
            plantVia(helper, arena, bag, extra);
        }
        int afterBurst = arena.countPlants();

        Simulation simulation = Simulation.start(arena, days);
        helper.assertTrue(simulation != null, "a day simulation was already running");

        helper.succeedWhen(() -> {
            helper.assertTrue(simulation.result != null, "simulation still running");
            if (!simulation.reported) {
                simulation.reported = true;
                double spacing = SpreadMath.minSpacing(SpreadMath.resolveDensity(density));
                Arena.Density measured = arena.measureDensity(root, 10);
                log("growth [" + label + "]: " + afterBurst + " plants after the burst, " + arena.countPlants() + " after " + days
                        + " days; " + describe(simulation.result) + "\n" + measured.describe(density, spacing) + "\n" + arena.map());
                // The point of the spacing model: nobody is closer than it, and a full garden holds about `density` per chunk.
                if (measured.minDistance < spacing - 1e-6) {
                    failNow(helper, "two plants are " + measured.minDistance + " blocks apart, closer than the minimum spacing " + spacing);
                }
                // Within 30% - or more when the interior holds so few plants that chance alone moves the count that much.
                double interiorPlants = density * (SIZE - 20.0) * (SIZE - 20.0) / 256.0;
                double slack = Math.max(0.3, 2.0 / Math.sqrt(interiorPlants));
                if (checkDensity && (measured.perChunk < density * (1 - slack) || measured.perChunk > density * (1 + slack))) {
                    failNow(helper, String.format(Locale.ROOT, "a garden of density %d holds %.1f plants per 16x16, expected about %d", density, measured.perChunk, density));
                }
                List<String> problems = arena.validate();
                arena.cleanup();
                simulation.problems = problems;
            }
            failOnProblems(helper, simulation.problems);
            if (simulation.result.errors() > 0) {
                failNow(helper, simulation.result.errors() + " exceptions while ticking plants (see the log)");
            }
        });
    }

    // The same summary /diseasedflower day gives, so the command's text is exercised too.
    private static String describe(DayAdvance.Result result) {
        return result.days() + " days simulated: " + result.plantTicks() + " plant ticks, " + result.workMillis() + " ms, "
                + result.errors() + " errors\n" + String.join("\n", result.after().describe(result.before()));
    }

    private static void failOnProblems(GameTestHelper helper, List<String> problems) {
        if (!problems.isEmpty()) {
            failNow(helper, problems.size() + " invariant violations, first: " + problems.subList(0, Math.min(5, problems.size())));
        }
    }

    // Fails the test at once, from anywhere - including inside succeedWhen, where a thrown GameTestAssertException just
    // means "not yet, try again next tick" (and any other exception takes the whole server's tick loop down with it).
    private static void failNow(GameTestHelper helper, String message) {
        helper.testInfo.fail(new GameTestAssertException(message));
        throw new GameTestAssertException(message);
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
            plant.startGarden(level, contents, pos);
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

    // Sizes and costs of a set of block entities, see blockEntityFootprint.
    private static final class Footprint {
        private static final int HEAP_SAMPLES = 200_000;

        final String label;
        int entities;
        long rawWith, rawWithout, gzipWith, gzipWithout, packetWith, packetWithout;
        long heapBytesPerEntity;
        double loadMicrosPerEntity;
        int saveTagBytes;

        Footprint(String label) {
            this.label = label;
        }

        // `positions` are the block entities to measure; they're swapped for stone in the middle of the measurement (so
        // the caller must not rely on them afterwards) to get the "without" side of each comparison.
        static Footprint measure(Arena arena, List<BlockPos> positions, String label) {
            Footprint footprint = new Footprint(label);
            ServerLevel level = arena.level;
            footprint.entities = positions.size();

            try {
                for (ChunkPos chunkPos : arena.chunks) {
                    LevelChunk chunk = level.getChunk(chunkPos.x, chunkPos.z);
                    CompoundTag with = ChunkSerializer.write(level, chunk);
                    CompoundTag without = with.copy();
                    without.remove("block_entities");
                    footprint.rawWith += rawSize(with);
                    footprint.rawWithout += rawSize(without);
                    footprint.gzipWith += gzipSize(with);
                    footprint.gzipWithout += gzipSize(without);
                    footprint.packetWith += packetSize(level, chunk);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            BlockPos sample = positions.get(0);
            BlockEntity sampleEntity = level.getBlockEntity(sample);
            BlockState sampleState = level.getBlockState(sample);
            CompoundTag saved = sampleEntity.saveWithFullMetadata(level.registryAccess());
            footprint.saveTagBytes = GardenStats.serializedSize(saved);

            // The same chunks with those blocks as plain stone: no block entities left to send.
            arena.replaceWithStone(positions);
            for (ChunkPos chunkPos : arena.chunks) {
                footprint.packetWithout += packetSize(level, level.getChunk(chunkPos.x, chunkPos.z));
            }

            // Loading them back from disk into memory, each with its own copy of whatever it stores.
            byte[] savedBytes;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                NbtIo.write(saved, new DataOutputStream(out));
                savedBytes = out.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            // A fresh tag per entity, parsed from bytes like a chunk load does, so nothing is shared between them.
            long before = usedHeap();
            long start = System.nanoTime();
            List<BlockEntity> loaded = new ArrayList<>(HEAP_SAMPLES);
            try {
                for (int i = 0; i < HEAP_SAMPLES; i++) {
                    CompoundTag fresh = NbtIo.read(new DataInputStream(new ByteArrayInputStream(savedBytes)));
                    loaded.add(BlockEntity.loadStatic(new BlockPos(i, 64, 0), sampleState, fresh, level.registryAccess()));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            double micros = (System.nanoTime() - start) / 1000.0;
            long after = usedHeap();
            footprint.loadMicrosPerEntity = micros / HEAP_SAMPLES;
            footprint.heapBytesPerEntity = Math.max(0, (after - before) / HEAP_SAMPLES);
            if (loaded.size() != HEAP_SAMPLES) {
                throw new IllegalStateException("keeps the list alive across the measurement");
            }
            return footprint;
        }

        long gzipPerEntity() {
            return (gzipWith - gzipWithout) / Math.max(1, entities);
        }

        String describe() {
            return String.format(Locale.ROOT,
                    "footprint [%s]: %d entities | one saves as %d bytes | in chunk data: %d bytes each raw, %d compressed | in the chunk packet: %d bytes each"
                            + " | loaded into memory: ~%d bytes each, %.1f us each to load",
                    label, entities, saveTagBytes, (rawWith - rawWithout) / Math.max(1, entities), gzipPerEntity(),
                    (packetWith - packetWithout) / Math.max(1, entities), heapBytesPerEntity, loadMicrosPerEntity);
        }

        private static long usedHeap() {
            System.gc();
            System.gc();
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }

        private static long rawSize(CompoundTag tag) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.write(tag, new DataOutputStream(bytes));
            return bytes.size();
        }

        private static long gzipSize(CompoundTag tag) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            return bytes.size();
        }

        private static long packetSize(ServerLevel level, LevelChunk chunk) {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, new ClientboundLevelChunkWithLightPacket(chunk, level.getLightEngine(), null, null));
                return buffer.writerIndex();
            } finally {
                buffer.release();
            }
        }
    }

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

    // What goes in a Garden Bag, without going through a menu. The lifetime is no bag item any more: it is the profile's own, as
    // /diseasedflower profile set lifetime makes it (0 = the server's setting).
    private static final class Bag {
        private final List<ItemStack> items = new ArrayList<>();
        private int lifetime;

        Bag add(Item item, int count) {
            for (int left = count; left > 0; left -= 64) {
                items.add(new ItemStack(item, Math.min(left, 64)));
            }
            return this;
        }

        Bag lifetime(int attempts) {
            this.lifetime = attempts;
            return this;
        }

        GardenBagContents contents() {
            return GardenBagContents.read(items).withLifetimeAttempts(lifetime);
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
            // Every scenario but the variation ones measures an evenly spaced garden, whatever the config says.
            Variation.override(Variation.Settings.OFF);
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

            // Whatever an earlier test left within reach must not be ticked along with this one's plants.
            sweep(sweepArea());

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
            sweep(sweepArea());
            for (ChunkPos chunk : chunks) {
                level.setChunkForced(chunk.x, chunk.z, false);
            }
        }

        // Every chunk that could hold something a test left behind: the ones kept loaded plus a ring around them, since
        // plants at the edge of the loaded area place children just beyond it - where nothing ticks them or cleans up
        // after them, and where a later test's arena may reach.
        private Set<ChunkPos> sweepArea() {
            Set<ChunkPos> area = new LinkedHashSet<>();
            for (ChunkPos chunk : chunks) {
                for (int dx = -SWEEP_RING; dx <= SWEEP_RING; dx++) {
                    for (int dz = -SWEEP_RING; dz <= SWEEP_RING; dz++) {
                        area.add(new ChunkPos(chunk.x + dx, chunk.z + dz));
                    }
                }
            }
            return area;
        }

        // Removes everything this mod put in these chunks (restoring the ground Flower Blocks replaced) and the vanilla
        // flowers its plants settled into, so a test never leaks plants into the next one.
        private void sweep(Set<ChunkPos> area) {
            List<BlockPos> ours = new ArrayList<>();
            for (ChunkPos chunk : area) {
                level.getChunk(chunk.x, chunk.z);
                GardenScan.forEachPlantBlock(level, chunk, (pos, state) -> ours.add(pos.immutable()));
            }
            for (BlockPos pos : ours) {
                BlockState state = level.getBlockState(pos);
                BlockState restored = state.getBlock() instanceof FlowerMassBlock && level.getBlockEntity(pos) instanceof FlowerMassBlockEntity flowerBlock
                        ? flowerBlock.replacedState()
                        : Blocks.AIR.defaultBlockState();
                level.setBlock(pos, restored, SettleTable.PLACEMENT_FLAGS);
            }

            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (ChunkPos chunk : area) {
                for (int x = chunk.getMinBlockX(); x <= chunk.getMaxBlockX(); x++) {
                    for (int z = chunk.getMinBlockZ(); z <= chunk.getMaxBlockZ(); z++) {
                        for (int y = origin.getY() - 3; y < origin.getY() + HEIGHT; y++) {
                            cursor.set(x, y, z);
                            BlockState state = level.getBlockState(cursor);
                            if (SettleTable.isAnyPlant(state) || state.getBlock() instanceof DoublePlantBlock) {
                                level.setBlock(cursor, Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                            }
                        }
                    }
                }
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

        // Plants that are still this mod's blocks (i.e. still spreading or settled in place), not plain vanilla flowers.
        int countDiseased() {
            int count = 0;
            for (BlockPos pos : plantPositions()) {
                if (GardenScan.isPlantBlock(level.getBlockState(pos))) {
                    count++;
                }
            }
            return count;
        }

        // Puts `count` blocks at distinct random cells of the arena's box (above the floor), one per cell, with whatever
        // `placer` makes of each; returns where. For measurements that need thousands of block entities without
        // growing them.
        List<BlockPos> scatter(int count, java.util.function.Consumer<BlockPos> placer) {
            List<BlockPos> cells = new ArrayList<>();
            for (int y = 1; y < HEIGHT; y++) {
                for (int x = 0; x < SIZE; x++) {
                    for (int z = 0; z < SIZE; z++) {
                        cells.add(at(x, y, z));
                    }
                }
            }
            java.util.Collections.shuffle(cells, new java.util.Random(1234));

            List<BlockPos> placed = new ArrayList<>(cells.subList(0, Math.min(count, cells.size())));
            for (BlockPos pos : placed) {
                placer.accept(pos);
            }
            return placed;
        }

        void replaceWithStone(List<BlockPos> positions) {
            for (BlockPos pos : positions) {
                level.setBlock(pos, Blocks.STONE.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                if (level.getBlockEntity(pos) != null) {
                    level.removeBlockEntity(pos);
                }
            }
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

        // The plants of the arena that still keep a block entity with a garden - i.e. that are still growing - as
        // {garden, depth} by position.
        Map<BlockPos, int[]> growingPlants() {
            Map<BlockPos, int[]> result = new java.util.HashMap<>();
            for (BlockPos pos : plantPositions()) {
                SpreadProfileBlockEntity plant = DiseasedPlantLogic.profileAt(level, pos);
                if (plant != null && plant.garden() != GardenRegistry.NO_GARDEN) {
                    result.put(pos.immutable(), new int[]{plant.garden(), (int) plant.depth()});
                }
            }
            return result;
        }

        // Every creeper block (patch pieces included) in the arena's box.
        List<BlockPos> creeperPositions() {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = 1; y < HEIGHT; y++) {
                        BlockPos pos = at(x, y, z);
                        if (level.getBlockState(pos).getBlock() instanceof CreepingFlowerBlock) {
                            result.add(pos);
                        }
                    }
                }
            }
            return result;
        }

        // Creeper blocks that are still ticking: the growing ends of tendrils, and seeds.
        List<BlockPos> growingCreepers() {
            List<BlockPos> result = new ArrayList<>();
            for (BlockPos pos : creeperPositions()) {
                if (level.getBlockState(pos).isRandomlyTicking()) {
                    result.add(pos);
                }
            }
            return result;
        }

        // Every creeper block of the box as a block position, for PatchStats.
        LongArrayList creeperCells() {
            LongArrayList cells = new LongArrayList();
            for (BlockPos pos : creeperPositions()) {
                cells.add(pos.asLong());
            }
            return cells;
        }

        void clearCreepers() {
            for (BlockPos pos : creeperPositions()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
            }
        }

        // Every plant of the box, ours and vanilla's, tall ones whole - the ground is left as it was.
        void clearAllPlants() {
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = 1; y < HEIGHT; y++) {
                        BlockPos pos = at(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (SettleTable.isAnyPlant(state) || state.getBlock() instanceof DoublePlantBlock) {
                            level.setBlock(pos, Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                        }
                    }
                }
            }
        }

        // How many blocks of this kind the box holds (the two halves of a tall plant count as two).
        int countBlocks(Block block) {
            int count = 0;
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    for (int y = 1; y < HEIGHT; y++) {
                        if (level.getBlockState(at(x, y, z)).is(block)) {
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        // Items lying in the box, which growing over a plant, or one breaking, would drop.
        List<ItemEntity> droppedItems() {
            return level.getEntitiesOfClass(ItemEntity.class, AABB.encapsulatingFullBlocks(origin, origin.offset(SIZE, HEIGHT, SIZE)).inflate(2.0));
        }

        void discardDroppedItems() {
            droppedItems().forEach(ItemEntity::discard);
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

        // What a garden looks like once it has filled the arena: plants per 16x16 in the middle of it (away from the edges,
        // where the garden simply stops), and the smallest distance between any two of them.
        record Density(int plants, double perChunk, double minDistance, double farthest) {
            String describe(int density, double spacing) {
                return String.format(Locale.ROOT, "%d plants: %.1f per 16x16 in the interior (density %d), closest pair %.2f blocks apart (minimum spacing %.2f), farthest %.1f blocks from the root",
                        plants, perChunk, density, minDistance, spacing, farthest);
            }
        }

        Density measureDensity(BlockPos root, int border) {
            List<BlockPos> plants = plantPositions();
            int interior = 0;
            double minDistance = Double.MAX_VALUE;
            double farthest = 0;
            for (int i = 0; i < plants.size(); i++) {
                BlockPos plant = plants.get(i);
                int x = plant.getX() - origin.getX();
                int z = plant.getZ() - origin.getZ();
                if (x >= border && x < SIZE - border && z >= border && z < SIZE - border) {
                    interior++;
                }
                farthest = Math.max(farthest, Math.sqrt(plant.distSqr(root)));
                for (int j = i + 1; j < plants.size(); j++) {
                    minDistance = Math.min(minDistance, Math.sqrt(plant.distSqr(plants.get(j))));
                }
            }
            double area = (SIZE - 2.0 * border) * (SIZE - 2.0 * border);
            return new Density(plants.size(), interior / area * 256.0, plants.size() < 2 ? Double.MAX_VALUE : minDistance, farthest);
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
                            // Only a plant with something left to do keeps a block entity: not one that has settled in
                            // place, and not the upper half of a tall plant.
                            boolean upperHalf = state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER;
                            boolean needsEntity = !upperHalf && !DiseasedPlantLogic.isSettled(state);
                            boolean hasEntity = level.getBlockEntity(pos) instanceof SpreadProfileBlockEntity;
                            if (needsEntity && !hasEntity) {
                                problems.add("active Diseased plant without block entity at " + pos + ": " + state);
                            }
                            if (!needsEntity && hasEntity) {
                                problems.add("settled or upper-half plant still holding a block entity at " + pos + ": " + state);
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
