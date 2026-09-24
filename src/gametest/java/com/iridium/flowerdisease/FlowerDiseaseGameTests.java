package com.iridium.flowerdisease;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
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
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import io.netty.buffer.Unpooled;
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

    // The planted flower is generation 1; the burst is generation 2 - everything the planted flower would have spawned
    // over its whole life, all at once. It then has nothing left to do, so it settles, and only its children (still
    // generation 2, the burst's last) stay active. Rabbit's Foot x1000 keeps the lifetime test from ending the root's
    // life before it has a single child (1 in 9 at the default), so what limits it is the room around it.
    @GameTest(template = TEMPLATE, batch = "gt_burst", timeoutTicks = 200)
    public static void plantingBurstIsTheWholeSecondGeneration(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.RABBIT_FOOT, 1000);
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
            failOnProblems(arena.validate());
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
            Bag bag = new Bag().add(Items.POPPY, 1).add(Items.BONE_MEAL, 1).add(Items.RABBIT_FOOT, 1000);
            BlockPos root = arena.at(24, 1, 24);
            plantVia(helper, arena, bag, root);

            helper.assertTrue(arena.countPlants() == 1, "expected exactly one flower, found " + arena.countPlants());
            helper.assertTrue(!GardenScan.isPlantBlock(arena.level.getBlockState(root)), "the only generation there is should be settled, not still spreading");
            failOnProblems(arena.validate());
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
            Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.BONE_MEAL, 2).add(Items.RABBIT_FOOT, 1000);
            BlockPos root = arena.at(24, 1, 24);
            plantVia(helper, arena, bag, root);

            log("burst with Bone Meal x2: " + arena.countPlants() + " flowers, " + arena.countDiseased() + " still spreading\n" + arena.map());
            helper.assertTrue(arena.countPlants() >= 2, "expected children, found " + arena.countPlants() + " flowers");
            helper.assertTrue(arena.countDiseased() == 0, arena.countDiseased() + " plants are still spreading; the burst should have been everything");
            failOnProblems(arena.validate());
        } finally {
            arena.cleanup();
        }
        helper.succeed();
    }

    private static void plantVia(GameTestHelper helper, Arena arena, Bag bag, BlockPos root) {
        Component failure = GardenBagItem.plant(arena.level, root, bag.stack(), Direction.UP);
        helper.assertTrue(failure == null, "planting failed: " + failure);
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
        // Two generations: the root reproduces, its children are born already settled, and with 64 Moss Blocks every
        // settling plant converts the block it stands on.
        // Rabbit's Foot x1000 keeps the root from settling on its very first tick (a 1 in 9 chance at the default
        // lifetime), which would leave nothing to check.
        Bag bag = new Bag().add(Items.POPPY, 1).add(Items.MOSS_BLOCK, 64).add(Items.BONE_MEAL, 2).add(Items.RABBIT_FOOT, 1000);
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

    // A planting is one garden: every plant of it points at the same registry entry instead of carrying a copy of the
    // profile.
    @GameTest(template = TEMPLATE, batch = "gt_garden_shared", timeoutTicks = 200)
    public static void plantsOfAPlantingShareOneGarden(GameTestHelper helper) {
        Arena arena = new Arena(helper);
        arena.prepare();
        try {
            Bag bag = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.RABBIT_FOOT, 1000);
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
            failOnProblems(arena.validate());
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
            plantVia(helper, arena, new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.RABBIT_FOOT, 1000), arena.at(24, 1, 24));

            List<SpreadProfileBlockEntity> plants = new ArrayList<>();
            for (BlockPos pos : arena.plantPositions()) {
                SpreadProfileBlockEntity plant = DiseasedPlantLogic.profileAt(arena.level, pos);
                if (plant != null) {
                    plants.add(plant);
                }
            }
            helper.assertTrue(plants.size() >= 2, "expected several active plants, found " + plants.size());

            GardenBagContents edited = new Bag().add(Items.POPPY, 3).add(Items.DANDELION, 2).add(Items.RABBIT_FOOT, 3).add(Items.SLIME_BALL, 4).contents();
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
            BlockState creeperState = arena.level.getBlockState(creeper);
            helper.assertTrue(creeperState.getBlock() instanceof CreepingFlowerBlock && DiseasedPlantLogic.isSettled(creeperState), "expected a settled creeper, found " + creeperState);
            helper.assertTrue(arena.level.getBlockEntity(creeper) == null, "a settled creeper should keep no block entity");

            BlockPos tall = arena.at(20, 1, 20);
            DoublePlantBlock.placeAt(arena.level, FlowerDisease.DISEASED_SUNFLOWER.get().defaultBlockState(), tall, SettleTable.PLACEMENT_FLAGS);
            helper.assertTrue(arena.level.getBlockEntity(tall) instanceof SpreadProfileBlockEntity, "the lower half of an active tall plant should have a block entity");
            helper.assertTrue(arena.level.getBlockEntity(tall.above()) == null, "the upper half should never have one");

            failOnProblems(arena.validate());
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
        private static final int HEAP_SAMPLES = 50_000;

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
