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

    private static final double MIN_MOSS_CHANCE = 0.001;
    private static final double CHUNK_SIDE = 16.0;
    // A garden never fills its lattice completely - every plant lives only so many attempts, and the gaps its parents
    // leave behind stay gaps - so the spacing is a little tighter than the lattice that would hold exactly `density`.
    // Measured with the game tests (default lifetime): at the plain lattice spacing a garden reached 0.72-0.83 of its density;
    // with this scale it lands at 0.85-0.9 at densities 16 and 64 (the grid of blocks makes it step rather than slide).
    private static final double SPACING_SCALE = 0.88;

    private SpreadMath() {
    }

    // ---- Density -------------------------------------------------------------------------------------

    // Flowers per 16x16 area: the bag's Slime Ball count when there is one, the server default otherwise.
    static int resolveDensity(int densityOverride) {
        int density = densityOverride >= 0 ? densityOverride : Config.FLOWER_DEFAULT_DENSITY.getAsInt();
        return Mth.clamp(density, 1, 256);
    }

    // The closest two plants of a garden may be: about 16 / sqrt(density) blocks. A square lattice with exactly that
    // spacing holds `density` plants per 16x16 area (one per 16 blocks at 1, one per 4 at 16, one every other block at
    // 64), and a garden that fills in by putting every child at the nearest place that keeps the spacing ends up at a
    // steady fraction of the lattice - SPACING_SCALE makes up for it, so "density per chunk" means what it says.
    static double minSpacing(int density) {
        return SPACING_SCALE * CHUNK_SIDE / Math.sqrt(density);
    }

    // ---- Spread distance -----------------------------------------------------------------------------

    static int resolveMaxDistance(int distanceOverride, int density) {
        if (distanceOverride >= 0) {
            return Mth.clamp(distanceOverride, 1, MAX_MANUAL_DISTANCE);
        }
        return autoMaxDistance(density, Config.FLOWER_AUTO_SPREAD_REACH.getAsDouble(), Config.FLOWER_SPREAD_DISTANCE.getAsInt());
    }

    // A child has to keep the minimum spacing from every plant, its parent included, so it can only go between the
    // spacing and the maximum distance: reaching `reach` spacings out leaves room to find a gap, and denser gardens
    // correctly get a shorter reach so they walk across the map. Never below one block past the spacing, or nothing
    // would fit at all - though the cap has the last word.
    static int autoMaxDistance(int density, double reach, int cap) {
        double spacing = minSpacing(density);
        int distance = Math.max((int) Math.ceil(reach * spacing), (int) Math.ceil(spacing) + 1);
        return Math.max(2, Math.min(distance, cap));
    }

    // How far from the parent the search can possibly read: the maximum distance plus the spacing around the farthest
    // candidate. Every chunk within this many blocks must be loaded before searching, since reading a block in an
    // unloaded chunk would force the server to load - and possibly generate - it.
    static int searchReach(GardenBagContents profile) {
        int density = resolveDensity(profile.densityPer16x16());
        return resolveMaxDistance(profile.spreadDistance(), density) + (int) Math.ceil(minSpacing(density)) + 1;
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

    // `cap` counts generations the way a player does: the planted flower is generation 1, its children 2, and so on
    // (so Bone Meal x1 is a single flower). `depth` is 0-based - 0 for the planted flower - which makes a plant's own
    // generation depth + 1. A negative cap means unlimited (reported as -1); otherwise how many more generations may
    // still be born below this plant.
    static long generationsLeft(long cap, long depth) {
        return cap < 0 ? SpreadProfileBlockEntity.INFINITE_GENERATIONS : Math.max(0, cap - depth - 1);
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

    // A new Flower Block's own generation cap, in the same counting as everything else (the block itself is
    // generation 1): between 1 (never spreads) and `flowerBlockMaxGenerations` + 1 (spreads that many generations).
    static int rollFlowerBlockGenerations(RandomSource random) {
        return 1 + random.nextInt(Config.FLOWER_BLOCK_MAX_GENERATIONS.getAsInt() + 1);
    }
}
