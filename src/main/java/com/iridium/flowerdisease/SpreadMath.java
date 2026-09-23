package com.iridium.flowerdisease;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

// The formulas behind a Diseased plant's life cycle, kept free of any world access so they're easy to reason
// about (and to check in isolation). The overloads taking explicit numbers are the actual maths; the ones
// reading Config/GardenBagContents just feed them. See PLANNING_STAGE2.md for why each formula looks the way
// it does.
final class SpreadMath {
    static final int MAX_MANUAL_DISTANCE = 32;
    static final int MAX_MOSS_BLOCKS = 64;

    private static final double TARGET_FLOWERS_PER_WINDOW = 6.0;
    private static final int MIN_WINDOW_RADIUS = 2;
    private static final int MAX_WINDOW_RADIUS = 8;
    private static final double MIN_MOSS_CHANCE = 0.001;
    private static final double CHUNK_SIDE = 16.0;

    private SpreadMath() {
    }

    // ---- Density -------------------------------------------------------------------------------------

    // Flowers per 16x16 area: the bag's Slime Ball count when there is one, the server default otherwise.
    static int resolveDensity(int densityOverride) {
        int density = densityOverride >= 0 ? densityOverride : Config.FLOWER_DEFAULT_DENSITY.getAsInt();
        return Mth.clamp(density, 1, 256);
    }

    static int windowRadius(int density) {
        return windowRadius(density, Config.FLOWER_DENSITY_RADIUS.getAsInt());
    }

    // The window used to count nearby flowers scales with the density, so the crowding limit stays a
    // statistically meaningful ~6 flowers instead of collapsing to "1" for every low density (a fixed 9x9
    // window holds 0.3 flowers on average at 1 per chunk, which the integer limit can't tell apart from 4 per chunk).
    static int windowRadius(int density, int manualRadius) {
        if (manualRadius > 0) {
            return manualRadius;
        }
        double raw = (CHUNK_SIDE * Math.sqrt(TARGET_FLOWERS_PER_WINDOW / density) - 1.0) / 2.0;
        return Mth.clamp((int) Math.round(raw), MIN_WINDOW_RADIUS, MAX_WINDOW_RADIUS);
    }

    static int crowdLimit(int density, int radius) {
        double side = 2.0 * radius + 1.0;
        return Math.max(1, (int) Math.round(density * side * side / 256.0));
    }

    // ---- Spread distance -----------------------------------------------------------------------------

    static int resolveMaxDistance(int distanceOverride, int density) {
        if (distanceOverride >= 0) {
            return Mth.clamp(distanceOverride, 1, MAX_MANUAL_DISTANCE);
        }
        return autoMaxDistance(density, Config.FLOWER_AUTO_SPREAD_REACH.getAsDouble(), Config.FLOWER_SPREAD_DISTANCE.getAsInt());
    }

    // Average spacing between flowers at this density is 16 / sqrt(density); reaching a bit over one spacing
    // is enough to find a gap, and denser gardens correctly get a shorter reach so they walk across the map.
    static int autoMaxDistance(int density, double reach, int cap) {
        int distance = (int) Math.ceil(reach * CHUNK_SIDE / Math.sqrt(density));
        return Mth.clamp(distance, 2, Math.max(2, cap));
    }

    // ---- Life cycle ----------------------------------------------------------------------------------

    static double halfGenerations(int decayStrength) {
        return halfGenerations(Config.FLOWER_DECAY_HALF_GENERATIONS.getAsDouble(), decayStrength);
    }

    static double halfGenerations(double base, int decayStrength) {
        return base / (1.0 + decayStrength / 4.0);
    }

    // Hyperbolic on purpose: it never reaches zero (a garden keeps spreading, just ever slower - the radius grows
    // roughly with the square root of time) where an exponential would stall it at a finite size.
    static double reproductionChance(double baseChance, long depth, double halfGenerations, boolean noDecay) {
        if (noDecay) {
            return baseChance;
        }
        return baseChance / (1.0 + depth / halfGenerations);
    }

    // A negative cap means unlimited (reported as -1); otherwise how many more generations may still be born
    // below this depth.
    static long generationsLeft(long cap, long depth) {
        return cap < 0 ? SpreadProfileBlockEntity.INFINITE_GENERATIONS : Math.max(0, cap - depth);
    }

    static int resolveLifetimeAttempts(int override) {
        return override > 0 ? override : Config.FLOWER_DEFAULT_LIFETIME.getAsInt();
    }

    // Each reproduction attempt lets the plant keep going with probability N / (N + 1), so it makes N attempts on
    // average before the test fails and it settles.
    static double continueProbability(int attempts) {
        return attempts / (attempts + 1.0);
    }

    // ---- Flower blocks -------------------------------------------------------------------------------

    // Exponential in the number of Moss Blocks: 1 -> 0.1%, doubling roughly every 6.3 blocks, 64 -> 100%.
    static double flowerBlockChance(int mossBlocks) {
        if (mossBlocks <= 0) {
            return 0.0;
        }
        if (mossBlocks >= MAX_MOSS_BLOCKS) {
            return 1.0;
        }
        return Math.pow(MIN_MOSS_CHANCE, (MAX_MOSS_BLOCKS - mossBlocks) / (MAX_MOSS_BLOCKS - 1.0));
    }

    static int rollFlowerBlockGenerations(RandomSource random) {
        return random.nextInt(Config.FLOWER_BLOCK_MAX_GENERATIONS.getAsInt() + 1);
    }
}
