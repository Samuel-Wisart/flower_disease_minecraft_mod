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
                    "The knob for how fast a garden starts out - /diseasedflower spreadchance <value> changes it live.",
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

    public static final ModConfigSpec.IntValue FLOWER_LIFETIME = BUILDER
            .comment(
                    "Average number of children a plant has before it settles for good: every reproduction attempt has an",
                    "N/(N+1) chance of letting the plant keep going, so it makes N attempts on average. Mind that a lineage",
                    "(a plant and everything descended from it) dies out on its own with probability 1/N - with 2, half of",
                    "them do - so raise it for gardens that keep going. /diseasedflower lifetime <n> changes it live."
            )
            .defineInRange("lifetimeAttempts", 2, 1, 1000);

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
                    "Flowers per 16x16 area a planting aims for when the bag has no Slime Ball. Plants of a garden keep",
                    "16 / sqrt(density) blocks from each other, so a full garden holds about this many per chunk (16 -> one",
                    "every 4 blocks, 64 -> every other block). It also sets the automatic maximum spread distance."
            )
            .defineInRange("defaultDensity", 16, 1, 256);

    public static final ModConfigSpec.IntValue FLOWER_SPREAD_DISTANCE = BUILDER
            .comment(
                    "Upper cap (in blocks) for the automatically computed maximum spread distance. A Feather in the",
                    "bag overrides the automatic value (up to 32). Keep it above 16 / sqrt(density) or nothing fits."
            )
            .defineInRange("spreadDistance", 32, 1, 32);

    public static final ModConfigSpec.DoubleValue FLOWER_AUTO_SPREAD_REACH = BUILDER
            .comment(
                    "How many minimum spacings (16 / sqrt(density) blocks) out the automatic maximum spread distance reaches.",
                    "A child can only go between one spacing and this, so denser gardens get a shorter reach and walk across",
                    "the map instead of leaping."
            )
            .defineInRange("autoSpreadReach", 1.5, 1.1, 5.0);

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

    // ---- Natural variation ----------------------------------------------------------------------------
    // Two smooth noise fields laid over the world (see Variation): one stretches and shrinks the spacing between plants from
    // place to place (thickets and clearings), the others make some species dominate some stretches. Everything is a function of
    // the position, the world seed and variationSeed, so it is the same every time; /diseasedflower variation changes these live
    // and /diseasedflower variation map draws them.

    public static final ModConfigSpec.DoubleValue SPACING_VARIATION = BUILDER
            .comment(
                    "How much the spacing between plants varies from place to place (0.0 = evenly spaced everywhere). It is the",
                    "standard deviation of the logarithm of the spacing: at 0.4 most places are within x0.8 to x1.7 of the bag's",
                    "spacing, and the extremes (a thicket, a clearing) reach the limits spacingMinFactor and spacingMaxFactor.",
                    "The average density over the world stays what the bag asks for: the thickets are paid for by the clearings,",
                    "so the typical place is a little sparser than the bag says (about x1.2 the spacing at 0.4, x1.7 at 0.8)."
            )
            .defineInRange("spacingVariation", 0.4, 0.0, 1.5);

    public static final ModConfigSpec.IntValue SPACING_VARIATION_SCALE = BUILDER
            .comment("About how many blocks across a thicket or a clearing is. Larger values give bigger, slower changes.")
            .defineInRange("spacingVariationScale", 48, 8, 512);

    public static final ModConfigSpec.DoubleValue SPACING_MIN_FACTOR = BUILDER
            .comment(
                    "The tightest a thicket may get, as a fraction of the bag's spacing: 0.4 = plants 2.5 times closer together, 6",
                    "times as dense. Lower values make denser thickets - every plant is still a block entity, so mind the count."
            )
            .defineInRange("spacingMinFactor", 0.4, 0.1, 1.0);

    public static final ModConfigSpec.DoubleValue SPACING_MAX_FACTOR = BUILDER
            .comment(
                    "The widest a clearing may get, as a multiple of the bag's spacing: 3.0 = plants 3 times farther apart, 9 times",
                    "sparser. The spacing never goes above 20 blocks whatever this says (the crowding check has to scan that far)."
            )
            .defineInRange("spacingMaxFactor", 3.0, 1.0, 10.0);

    public static final ModConfigSpec.DoubleValue SPECIES_VARIATION = BUILDER
            .comment(
                    "How strongly the species of a garden's pool favour different places (0.0 = every species everywhere, in",
                    "proportion to its weight in the bag). Every species has its own field and its weight is multiplied by e^(this",
                    "x field) - at 0.8 a species is about 5 times likelier where its field is high than where it is low, and a",
                    "stretch can end up almost entirely one flower. Only matters for a bag with two species or more."
            )
            .defineInRange("speciesVariation", 0.8, 0.0, 3.0);

    public static final ModConfigSpec.IntValue SPECIES_VARIATION_SCALE = BUILDER
            .comment("About how many blocks across a stretch dominated by one species is.")
            .defineInRange("speciesVariationScale", 64, 8, 512);

    public static final ModConfigSpec.DoubleValue VARIATION_DETAIL = BUILDER
            .comment(
                    "How much finer detail rides on top of the big shapes of both fields (0.0 = smooth blobs, 1.0 = as much",
                    "small-scale change as large-scale). Raises the ragged, organic look of the borders."
            )
            .defineInRange("variationDetail", 0.4, 0.0, 1.0);

    public static final ModConfigSpec.IntValue VARIATION_SEED = BUILDER
            .comment(
                    "Mixed into the world seed to pick the fields. Change it to get a different arrangement of thickets, clearings and",
                    "species stretches in the same world - handy to compare settings on the same spot."
            )
            .defineInRange("variationSeed", 0, Integer.MIN_VALUE, Integer.MAX_VALUE);

    // ---- Disease Powder -------------------------------------------------------------------------------

    public static final ModConfigSpec.IntValue POWDER_DAYS = BUILDER
            .comment("How many in-game days of growth one Disease Powder gives the plants it reaches, all at once.")
            .defineInRange("diseasePowderDays", 1, 1, 10);

    public static final ModConfigSpec.IntValue POWDER_RADIUS = BUILDER
            .comment(
                    "How far (in blocks, horizontally) from the block it is used on a Disease Powder reaches. Used on a plant of a",
                    "garden it only advances that garden; used on anything else it advances every growing plant within this radius."
            )
            .defineInRange("diseasePowderRadius", 48, 8, 128);

    // ---- Creeper patches ------------------------------------------------------------------------------
    // A patch is a few TENDRILS crawling over the surfaces around a creeper (see PatchGrowth): each keeps its heading, sometimes
    // turns, sometimes splits, and stops when it has spent its budget of pieces or has nowhere to go. Every seed has its own style
    // (a long streak, a branching vine or a small tuft), which is what keeps the patches from all looking alike. Everything here
    // is a plain number so it can be tried out live: /diseasedflower patch shows them and changes them, and /diseasedflower stats
    // reports the size and the elongation of the patches around you.

    public static final ModConfigSpec.IntValue PATCH_MAX_PIECES = BUILDER
            .comment(
                    "The most pieces a creeping flower's patch may grow (the seed itself not counted): a seed draws its budget",
                    "from 0 up to this (the middle values the most likely - about half of it on average) and every piece it grows",
                    "spends one. The cap is for the longest style; the branching ones get 5/6 of it and the tufts half. This is what",
                    "bounds the size of a patch, however lucky. 0 turns patches off."
            )
            .defineInRange("patchMaxPieces", 12, 0, 40);

    public static final ModConfigSpec.DoubleValue PATCH_GROWTH_CHANCE = BUILDER
            .comment(
                    "Chance (0.0-1.0) that the growing end of a patch grows one more piece when it receives a random tick.",
                    "Independent of the reproduction chance; at the default random tick speed a patch fills in in about a day."
            )
            .defineInRange("patchGrowthChance", 0.33, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue PATCH_TURNS = BUILDER
            .comment(
                    "How readily a tendril changes direction, as a multiple of what each style does by itself: 0 = dead straight,",
                    "1 = as designed (a streak turns about 1 step in 5, a tuft 1 in 2), 2 = very winding."
            )
            .defineInRange("patchTurns", 1.0, 0.0, 3.0);

    public static final ModConfigSpec.DoubleValue PATCH_BRANCHING = BUILDER
            .comment(
                    "How readily a tendril splits in two, as a multiple of what each style does by itself: 0 = never, 1 = as",
                    "designed, 2 = a tangle. A split shares the tendril's remaining budget between the two arms."
            )
            .defineInRange("patchBranching", 1.0, 0.0, 3.0);

    public static final ModConfigSpec.DoubleValue PATCH_THICKNESS = BUILDER
            .comment(
                    "How often a tendril grows a second piece beside the one it just made (a thicker stroke, the way tufts get",
                    "their bulk), as a multiple of what each style does by itself: 0 = strokes one piece wide, 1 = as designed."
            )
            .defineInRange("patchThickness", 1.0, 0.0, 3.0);

    public static final ModConfigSpec.IntValue PATCH_CROWDING = BUILDER
            .comment(
                    "How many creeper pieces a new piece may already have around it (in the 3x3x3 blocks around it, the piece it",
                    "grows from not counted) before the tendril turns away or stops: 1 keeps patches apart and thin, 3 (default)",
                    "lets them brush against each other, 8 lets them merge into mats. Tufts allow 2 more than this."
            )
            .defineInRange("patchCrowding", 3, 1, 8);

    public static final ModConfigSpec.DoubleValue PATCH_STYLE_VARIETY = BUILDER
            .comment(
                    "How different the seeds' styles are from each other: 0 = every seed grows the same average patch, 1 = long",
                    "streaks, branching vines and small tufts as designed, above 1 exaggerates the differences."
            )
            .defineInRange("patchStyleVariety", 1.0, 0.0, 1.5);

    public static final ModConfigSpec.DoubleValue PATCH_HANG_CHANCE = BUILDER
            .comment(
                    "Chance (0.0-1.0) that a tendril going down a wall, when it reaches the wall's lower edge, hangs a strand of",
                    "pieces straight down like a vine instead of wrapping under the edge. A hanging piece is held only by the piece",
                    "above it - break the top one and the whole strand comes down. Only patches do this: reproduction never places a",
                    "piece in mid-air. 0 turns hanging strands off."
            )
            .defineInRange("patchHangChance", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.IntValue PATCH_HANG_LENGTH = BUILDER
            .comment(
                    "The longest a hanging strand may be (a strand draws its length from 1 to this; the pieces still come out of",
                    "the tendril's budget)."
            )
            .defineInRange("patchHangLength", 6, 1, 16);

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

    // ---- Diagnostics ----------------------------------------------------------------------------------

    public static final ModConfigSpec.IntValue STALL_REPORT_SECONDS = BUILDER
            .comment(
                    "A safety net for a game that seems frozen - a world that never finishes saving when you leave it, say. If one",
                    "server tick, or the stopping of the world, takes longer than this many seconds, a report is written to the logs",
                    "folder (flowerdisease-stall-<time>): what every thread is doing, memory and garbage collection, and the state of",
                    "the chunk system of each dimension. It costs nothing while the game is fine. Send that folder when it happens.",
                    "0 = off."
            )
            .defineInRange("stallReportSeconds", 30, 0, 3600);

    static final ModConfigSpec SPEC = BUILDER.build();
}
