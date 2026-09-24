package com.iridium.flowerdisease;

import net.neoforged.neoforge.common.ModConfigSpec;

// Server/modpack-level defaults and safety limits. Everything a player is meant to tune lives in the Garden
// Bag (see GardenBagContents); these are only the values used when the bag says nothing about a knob, plus
// kill switches and caps a pack author may want. The maths that turns them into behavior is in SpreadMath.
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ---- Reproduction pacing -------------------------------------------------------------------------

    public static final ModConfigSpec.DoubleValue FLOWER_SPREAD_CHANCE = BUILDER
            .comment(
                    "Chance (0.0-1.0) that a Diseased plant receiving a random tick tries to reproduce, at generation 0.",
                    "It decays with the generation depth (see decayHalfGenerations), so a garden starts fast and keeps",
                    "slowing down without ever fully stopping. Vanilla delivers ~17.6 random ticks per block per",
                    "in-game day with the default randomTickSpeed=3."
            )
            .defineInRange("spreadChance", 1.0, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue FLOWER_DECAY_HALF_GENERATIONS = BUILDER
            .comment(
                    "The generation depth at which the reproduction chance has dropped to half (chance = base / (1 + depth / this)).",
                    "Each Sculk in the Garden Bag shortens it: this / (1 + sculk / 4)."
            )
            .defineInRange("decayHalfGenerations", 16.0, 1.0, 1000.0);

    public static final ModConfigSpec.IntValue FLOWER_DEFAULT_LIFETIME = BUILDER
            .comment(
                    "Average number of times a plant tries to reproduce before it settles for good, when the bag has no",
                    "Rabbit's Foot. Every attempt has an N/(N+1) chance of letting the plant keep going."
            )
            .defineInRange("defaultLifetimeAttempts", 8, 1, 1000);

    public static final ModConfigSpec.IntValue FLOWER_MAX_GENERATIONS = BUILDER
            .comment(
                    "How many generations a planting may have when the bag has no Bone Meal, counting the flower that was",
                    "planted as the first: 1 = just that flower, 2 = it and its children, and so on. -1 = unlimited (default)."
            )
            .defineInRange("maxGenerations", -1, -1, 1000);

    public static final ModConfigSpec.IntValue FLOWER_MAX_DEPTH = BUILDER
            .comment(
                    "Hard safety cap on how deep a lineage may go, whatever the bag says (0 = no cap). Useful for modpacks",
                    "that want to keep the 'unlimited' gardens from ever growing without bound."
            )
            .defineInRange("maxDepth", 0, 0, 100000);

    // ---- Placement ------------------------------------------------------------------------------------

    public static final ModConfigSpec.IntValue FLOWER_DEFAULT_DENSITY = BUILDER
            .comment(
                    "Flowers per 16x16 area a planting aims for when the bag has no Slime Ball. It also drives the",
                    "automatic density window and the automatic maximum spread distance."
            )
            .defineInRange("defaultDensity", 16, 1, 256);

    public static final ModConfigSpec.IntValue FLOWER_DENSITY_RADIUS = BUILDER
            .comment(
                    "Manual override for the radius (in blocks) of the window used to count nearby flowers. 0 = automatic:",
                    "the window grows or shrinks with the density so the limit stays around 5-6 flowers."
            )
            .defineInRange("densityCheckRadius", 0, 0, 16);

    public static final ModConfigSpec.IntValue FLOWER_SPREAD_DISTANCE = BUILDER
            .comment(
                    "Upper cap (in blocks) for the automatically computed maximum spread distance. A Feather in the",
                    "bag overrides the automatic value (up to 32)."
            )
            .defineInRange("spreadDistance", 16, 1, 32);

    public static final ModConfigSpec.DoubleValue FLOWER_AUTO_SPREAD_REACH = BUILDER
            .comment(
                    "How many average flower spacings the automatic maximum spread distance reaches. The spacing is",
                    "16 / sqrt(density), so denser gardens get a shorter reach and walk across the map instead of leaping."
            )
            .defineInRange("autoSpreadReach", 1.5, 0.5, 5.0);

    public static final ModConfigSpec.IntValue FLOWER_SPREAD_VERTICAL_RANGE = BUILDER
            .comment(
                    "How many blocks up/down from the parent's height a spread target may be, so the field can follow",
                    "slopes and steps instead of only spreading across perfectly flat ground."
            )
            .defineInRange("spreadVerticalRange", 2, 0, 8);

    public static final ModConfigSpec.IntValue FLOWER_SPREAD_ATTEMPTS = BUILDER
            .comment(
                    "How many positions are sampled on each distance ring beyond the second one (the two closest rings",
                    "are always fully checked)."
            )
            .defineInRange("spreadAttempts", 6, 1, 64);

    public static final ModConfigSpec.DoubleValue FLOWER_SPECIES_INHERITANCE = BUILDER
            .comment(
                    "Chance (0.0-1.0) that a child copies its parent's species instead of drawing from the bag's pool.",
                    "Higher values make larger single-species patches; the pool weights stay the long-run average."
            )
            .defineInRange("speciesInheritance", 0.6, 0.0, 1.0);

    public static final ModConfigSpec.IntValue FLOWER_BURST_GENERATIONS = BUILDER
            .comment(
                    "How many generations a planting made with the Garden Bag gets instantly, counting the planted flower",
                    "as the first: 1 = just the flower (no burst), 2 = it lives its whole life at once and all its children",
                    "appear (default), 3 = its children do the same, and so on. The lifetime test, density and the",
                    "generation cap still apply."
            )
            .defineInRange("plantingBurstGenerations", 2, 1, 5);

    public static final ModConfigSpec.IntValue FLOWER_BURST_MAX_PLANTS = BUILDER
            .comment("Upper bound on how many extra plants a single planting burst may create.")
            .defineInRange("plantingBurstMaxPlants", 32, 0, 256);

    // ---- Creeper patches ------------------------------------------------------------------------------

    public static final ModConfigSpec.IntValue PATCH_MAX_ENERGY = BUILDER
            .comment(
                    "A creeping flower that is planted or born grows a patch around itself: it draws an energy from 0 to this",
                    "value (the middle values the most likely) and every piece it grows has one less, so the patch spreads that",
                    "many pieces out across the surfaces around it, ignoring the density. 0 turns patches off."
            )
            .defineInRange("patchMaxEnergy", 5, 0, 10);

    public static final ModConfigSpec.DoubleValue PATCH_GROWTH_CHANCE = BUILDER
            .comment(
                    "Chance (0.0-1.0) that a creeper piece that still has energy grows one more piece when it receives a random",
                    "tick. Independent of the reproduction chance; at the default random tick speed a patch fills in in about a day."
            )
            .defineInRange("patchGrowthChance", 0.33, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue PATCH_FILL = BUILDER
            .comment(
                    "After a creeper piece grows a new piece, the chance (0.05-1.0) that it keeps growing more. 1.0 fills",
                    "everything within the energy's reach solidly; lower values leave ragged, organic patches (a piece grows",
                    "fill / (1 - fill) more pieces on average - 2.3 by default)."
            )
            .defineInRange("patchFill", 0.7, 0.05, 1.0);

    // ---- Flower blocks --------------------------------------------------------------------------------

    public static final ModConfigSpec.BooleanValue FLOWER_BLOCK_CONVERSION = BUILDER
            .comment(
                    "Whether plants configured with the Garden Bag's Moss Block modifier may corrupt terrain into Flower",
                    "Blocks at all. Off disables the whole mechanic instantly, even for plantings that have the modifier,",
                    "and settles every existing Flower Block in place - a safety switch for modpacks that don't want",
                    "any terrain corruption."
            )
            .define("flowerBlockConversion", true);

    public static final ModConfigSpec.DoubleValue FLOWER_BLOCK_SPREAD_FACTOR = BUILDER
            .comment(
                    "Multiplier applied to the base reproduction chance when a Flower Block spreads to one adjacent block",
                    "on its own random tick. Below 1.0 so the corruption spreads slower than the plants that spawned it."
            )
            .defineInRange("flowerBlockSpreadFactor", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.IntValue FLOWER_BLOCK_MAX_GENERATIONS = BUILDER
            .comment(
                    "A new Flower Block draws how many generations it may spread uniformly from 0 to this value, instead",
                    "of inheriting the plant's. Also the most a Flower Block placed by hand may spread."
            )
            .defineInRange("flowerBlockMaxGenerations", 4, 0, 16);

    static final ModConfigSpec SPEC = BUILDER.build();
}
