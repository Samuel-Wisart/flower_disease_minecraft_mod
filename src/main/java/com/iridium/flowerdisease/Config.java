package com.iridium.flowerdisease;

import net.neoforged.neoforge.common.ModConfigSpec;

// Tuning knobs for how the Diseased Flower spreads. See DiseasedFlowerBlock for how these are used.
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue FLOWER_MAX_NEARBY = BUILDER
            .comment(
                    "Maximum number of Diseased Flowers/Cornflowers allowed within the density check radius.",
                    "Once this many are found nearby, the flower stops spreading and settles into a normal Cornflower.",
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
                    "If none are valid, the flower simply waits for its next random tick instead of giving up permanently."
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

    static final ModConfigSpec SPEC = BUILDER.build();
}
