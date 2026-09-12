package com.iridium.flowerdisease;

import java.util.List;

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

    // Each list below controls what a Diseased Flower turns into once it can no longer spread (either
    // the area is crowded, or the terrain has no reachable spot - see spreadVerticalRange/spreadAttempts).
    // Entries are "<block id> <weight>", e.g. "minecraft:rose_bush 30". Weights are relative to each
    // other within the same list, not percentages - {"a 1", "b 1"} is the same 50/50 split as {"a 50", "b 50"}.
    // Invalid/unknown block ids are ignored; if a list ends up with no valid entries, the flower falls
    // back to its own plain vanilla flower. Add or remove lines and use /reload (or restart) to test changes.
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

    static {
        BUILDER.push("settleWeights");
        DANDELION_SETTLE_WEIGHTS = settleWeights("dandelion", List.of("minecraft:dandelion 70", "minecraft:sunflower 30"));
        POPPY_SETTLE_WEIGHTS = settleWeights("poppy", List.of("minecraft:poppy 50", "minecraft:rose_bush 50"));
        BLUE_ORCHID_SETTLE_WEIGHTS = settleWeights("blueOrchid", List.of("minecraft:blue_orchid 100"));
        ALLIUM_SETTLE_WEIGHTS = settleWeights("allium", List.of("minecraft:allium 50", "minecraft:peony 50"));
        AZURE_BLUET_SETTLE_WEIGHTS = settleWeights("azureBluet", List.of("minecraft:azure_bluet 34", "minecraft:lily_of_the_valley 33", "minecraft:oxeye_daisy 33"));
        RED_TULIP_SETTLE_WEIGHTS = settleWeights("redTulip", List.of("minecraft:red_tulip 70", "minecraft:rose_bush 30"));
        ORANGE_TULIP_SETTLE_WEIGHTS = settleWeights("orangeTulip", List.of("minecraft:orange_tulip 100"));
        WHITE_TULIP_SETTLE_WEIGHTS = settleWeights("whiteTulip", List.of("minecraft:white_tulip 100"));
        PINK_TULIP_SETTLE_WEIGHTS = settleWeights("pinkTulip", List.of("minecraft:pink_tulip 70", "minecraft:peony 30"));
        OXEYE_DAISY_SETTLE_WEIGHTS = settleWeights("oxeyeDaisy", List.of("minecraft:oxeye_daisy 100"));
        CORNFLOWER_SETTLE_WEIGHTS = settleWeights("cornflower", List.of("minecraft:cornflower 60", "minecraft:blue_orchid 40"));
        LILY_OF_THE_VALLEY_SETTLE_WEIGHTS = settleWeights("lilyOfTheValley", List.of("minecraft:lily_of_the_valley 100"));
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
        if (parts.length != 2) {
            return false;
        }

        try {
            if (!BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(parts[0]))) {
                return false;
            }
            return Integer.parseInt(parts[1]) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    static final ModConfigSpec SPEC = BUILDER.build();
}
