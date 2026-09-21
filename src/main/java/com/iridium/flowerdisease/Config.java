package com.iridium.flowerdisease;

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

    public static final ModConfigSpec.BooleanValue FLOWER_BLOCK_CONVERSION = BUILDER
            .comment(
                    "Whether a reproductive Diseased Flower configured with the Garden Bag's Moss Block modifier is",
                    "allowed to corrupt terrain into Flower Blocks at all. Off disables the whole mechanic instantly,",
                    "even for plantings that have the modifier, and settles every existing Flower Block in place -",
                    "a safety switch for modpacks that don't want any terrain corruption."
            )
            .define("flowerBlockConversion", true);

    public static final ModConfigSpec.DoubleValue FLOWER_BLOCK_CHANCE = BUILDER
            .comment(
                    "Chance (0.0-1.0) that a reproductive Diseased Flower with the Moss Block modifier converts the",
                    "block directly beneath it into a Flower Block on a given random tick. Deliberately much lower",
                    "than spreadChance - this is meant to be rare."
            )
            .defineInRange("flowerBlockChance", 0.005, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue FLOWER_BLOCK_SPREAD_FACTOR = BUILDER
            .comment(
                    "Multiplier applied to a Flower Block's own spreadChance (inherited from the flower that created",
                    "it) when it spreads to one adjacent block on its own random tick. Below 1.0 so the corruption",
                    "spreads slower than the flower that spawned it."
            )
            .defineInRange("flowerBlockSpreadFactor", 0.25, 0.0, 1.0);

    // There used to be a per-species "settleWeights" section here controlling what a Diseased Flower
    // turns into once it can no longer spread. That was only ever meant for testing the settle mechanic
    // itself - real configuration now happens exclusively through the Garden Bag's species grid (see
    // GardenBagItem/SpreadProfileBlockEntity#speciesWeights, which is what the bag writes into and what
    // DiseasedPlantLogic actually reads). A Diseased Flower planted by hand, with no bag profile, always
    // settles back into its own plain vanilla self - no more random species swap.

    static final ModConfigSpec SPEC = BUILDER.build();
}
