package com.iridium.flowerdisease;

import java.util.List;
import java.util.Locale;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

// Tuning knobs for how Diseased Flowers spread and what they settle into. See DiseasedFlowerBlock.
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue FLOWER_MAX_NEARBY = BUILDER
            .comment(
                    "Maximum number of same-species field flowers allowed within the density check radius.",
                    "Once this many are found nearby, the flower stops spreading and settles for good.",
                    "Lower values leave more empty gaps between flowers; higher values make denser fields."
            )
            .defineInRange("maxNearbyFlowers", 5, 1, 200);

    public static final ModConfigSpec.IntValue FLOWER_DENSITY_RADIUS = BUILDER
            .comment(
                    "Horizontal radius (in blocks) checked around a Diseased Flower when counting nearby field flowers."
            )
            .defineInRange("densityCheckRadius", 4, 1, 16);

    public static final ModConfigSpec.IntValue FLOWER_SPREAD_DISTANCE = BUILDER
            .comment(
                    "Maximum horizontal distance (in blocks) a new Diseased Flower can spawn from the flower spreading it."
            )
            .defineInRange("spreadDistance", 3, 1, 16);

    public static final ModConfigSpec.IntValue FLOWER_SPREAD_VERTICAL_RANGE = BUILDER
            .comment(
                    "How many blocks up/down from the parent's height a spread target may be, so the field can follow",
                    "slopes and steps instead of only spreading across perfectly flat ground."
            )
            .defineInRange("spreadVerticalRange", 2, 0, 8);

    public static final ModConfigSpec.IntValue FLOWER_SPREAD_ATTEMPTS = BUILDER
            .comment(
                    "How many random spots are tried each time a Diseased Flower attempts to spread.",
                    "If none are valid (crowded or blocked terrain), the flower settles for good - see 'settleWeights' below."
            )
            .defineInRange("spreadAttempts", 8, 1, 64);

    public static final ModConfigSpec.DoubleValue FLOWER_SPREAD_CHANCE = BUILDER
            .comment(
                    "Extra chance (0.0-1.0) applied on top of vanilla's random tick sampling before a Diseased Flower",
                    "attempts to spread. Vanilla random ticks alone already fire roughly 15-20 times per in-game day",
                    "per block (with the default randomTickSpeed=3), so this value is what actually controls the",
                    "'many days to fill a field' pacing. Lower = slower spread."
            )
            .defineInRange("spreadChance", 0.02, 0.0, 1.0);

    public static final ModConfigSpec.IntValue FLOWER_MAX_GENERATIONS = BUILDER
            .comment(
                    "How many generations of children a Diseased Flower can produce before a new child instantly",
                    "settles as a normal flower instead of continuing to spread. A flower planted directly by a",
                    "player starts with this many generations; each spread reduces the child's count by 1. This",
                    "bounds how far a single planting can ultimately reach, but with an organic/irregular edge",
                    "(not a perfect circle), since each spread is a random jump rather than a fixed radius.",
                    "0 disables spreading entirely - the flower settles on its very first random tick."
            )
            .defineInRange("maxGenerations", 6, 0, 64);

    // Each list below controls what a Diseased Flower turns into once it can no longer spread (either
    // the area is crowded, or the terrain has no reachable spot - see spreadVerticalRange/spreadAttempts).
    // Entries are "<block id> <weight> [full|lower|upper]", e.g. "minecraft:rose_bush 30 full". Weights
    // are relative to each other within the same list, not percentages - {"a 1", "b 1"} is the same
    // 50/50 split as {"a 50", "b 50"}. The third word only matters when the target is a two-block plant
    // (Sunflower/Lilac/Rose Bush/Peony): "full" places the correct, complete two-block plant; "lower"
    // places just its bottom half, a deliberately shorter look; "upper" places its standalone one-block
    // "_top" flower instead (e.g. Rose Bush Top) - a normal single-block flower using the top texture.
    // It defaults to "full" when omitted. Invalid/unknown entries are ignored; if a list ends up with no
    // valid entries, the flower falls back to its own plain vanilla flower. Add or remove lines and use
    // /reload (or restart) to test changes.
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DANDELION_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> POPPY_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLUE_ORCHID_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLIUM_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> AZURE_BLUET_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> RED_TULIP_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ORANGE_TULIP_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WHITE_TULIP_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PINK_TULIP_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> OXEYE_DAISY_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> CORNFLOWER_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LILY_OF_THE_VALLEY_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SUNFLOWER_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LILAC_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ROSE_BUSH_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PEONY_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WITHER_ROSE_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> SHORT_GRASS_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FERN_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> DEAD_BUSH_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TALL_GRASS_SETTLE_WEIGHTS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> LARGE_FERN_SETTLE_WEIGHTS;

    static {
        BUILDER.push("settleWeights");
        DANDELION_SETTLE_WEIGHTS = settleWeights("dandelion", List.of("minecraft:dandelion 70", "minecraft:sunflower 30 full"));
        POPPY_SETTLE_WEIGHTS = settleWeights("poppy", List.of("minecraft:poppy 50", "minecraft:rose_bush 50 full"));
        BLUE_ORCHID_SETTLE_WEIGHTS = settleWeights("blueOrchid", List.of("minecraft:blue_orchid 100"));
        ALLIUM_SETTLE_WEIGHTS = settleWeights("allium", List.of("minecraft:allium 50", "minecraft:peony 50 full"));
        AZURE_BLUET_SETTLE_WEIGHTS = settleWeights("azureBluet", List.of("minecraft:azure_bluet 34", "minecraft:lily_of_the_valley 33", "minecraft:oxeye_daisy 33"));
        RED_TULIP_SETTLE_WEIGHTS = settleWeights("redTulip", List.of("minecraft:red_tulip 70", "minecraft:rose_bush 30 full"));
        ORANGE_TULIP_SETTLE_WEIGHTS = settleWeights("orangeTulip", List.of("minecraft:orange_tulip 100"));
        WHITE_TULIP_SETTLE_WEIGHTS = settleWeights("whiteTulip", List.of("minecraft:white_tulip 100"));
        PINK_TULIP_SETTLE_WEIGHTS = settleWeights("pinkTulip", List.of("minecraft:pink_tulip 70", "minecraft:peony 30 full"));
        OXEYE_DAISY_SETTLE_WEIGHTS = settleWeights("oxeyeDaisy", List.of("minecraft:oxeye_daisy 100"));
        CORNFLOWER_SETTLE_WEIGHTS = settleWeights("cornflower", List.of("minecraft:cornflower 60", "minecraft:blue_orchid 40"));
        LILY_OF_THE_VALLEY_SETTLE_WEIGHTS = settleWeights("lilyOfTheValley", List.of("minecraft:lily_of_the_valley 100"));
        SUNFLOWER_SETTLE_WEIGHTS = settleWeights("sunflower", List.of("minecraft:sunflower 100 full"));
        LILAC_SETTLE_WEIGHTS = settleWeights("lilac", List.of("minecraft:lilac 100 full"));
        ROSE_BUSH_SETTLE_WEIGHTS = settleWeights("roseBush", List.of("minecraft:rose_bush 100 full"));
        PEONY_SETTLE_WEIGHTS = settleWeights("peony", List.of("minecraft:peony 100 full"));
        WITHER_ROSE_SETTLE_WEIGHTS = settleWeights("witherRose", List.of("minecraft:wither_rose 100"));
        SHORT_GRASS_SETTLE_WEIGHTS = settleWeights("shortGrass", List.of("minecraft:short_grass 50", "minecraft:tall_grass 50 full"));
        FERN_SETTLE_WEIGHTS = settleWeights("fern", List.of("minecraft:fern 50", "minecraft:large_fern 50 full"));
        DEAD_BUSH_SETTLE_WEIGHTS = settleWeights("deadBush", List.of("minecraft:dead_bush 100"));
        TALL_GRASS_SETTLE_WEIGHTS = settleWeights("tallGrass", List.of("minecraft:tall_grass 100 full"));
        LARGE_FERN_SETTLE_WEIGHTS = settleWeights("largeFern", List.of("minecraft:large_fern 100 full"));
        BUILDER.pop();
    }

    private static ModConfigSpec.ConfigValue<List<? extends String>> settleWeights(String key, List<String> defaults) {
        return BUILDER
                .comment("Possible outcomes for the Diseased " + key + " (see the settleWeights comment above for the format).")
                .defineListAllowEmpty(key, defaults, () -> "", Config::validateSettleEntry);
    }

    private static boolean validateSettleEntry(Object obj) {
        if (!(obj instanceof String text)) {
            return false;
        }

        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2 || parts.length > 3) {
            return false;
        }

        try {
            if (!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(parts[0]))) {
                return false;
            }
            if (Integer.parseInt(parts[1]) <= 0) {
                return false;
            }
            if (parts.length == 3) {
                SettleTable.Half.valueOf(parts[2].toUpperCase(Locale.ROOT));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static final ModConfigSpec SPEC = BUILDER.build();
}
