package com.iridium.flowerdisease;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;

// The natural irregularity of a garden (see PLANNING_STAGE2.md, "Variação natural"). Left alone, "put every child at the
// nearest spot that keeps the minimum spacing" grows an even, tidy lattice. Two kinds of smooth noise laid over the world
// break that up the way a real flower field is broken up:
//
//  - the SPACING field stretches and shrinks the minimum spacing from place to place: thickets where it is small,
//    clearings where it is large. It is scaled so that the average density over the world stays what the bag asked for.
//  - one SPECIES field per species, independent of each other, scales that species' weight in the pool, so a garden
//    has stretches where poppies dominate, others where dandelions do, and where a species' field is low, hardly any.
//
// Both are plain functions of the position, the world seed and a configurable seed offset - nothing is stored, and the
// same place always has the same character. The position asked about is always the PARENT's: a plant decides the spacing
// and the species of its child from where it stands, so one whole search works with one consistent number.
final class Variation {

    // The knobs as one value: the game tests pin them (see override), and a whole calculation reads one consistent
    // snapshot even if the config is edited in the middle of it.
    //
    // `minFactor` and `maxFactor` are how far the spacing may go from the bag's: a thicket is at most 1 / minFactor times
    // tighter (and 1 / minFactor squared times as dense - which bounds how many plants and block entities it can hold), a
    // clearing at most maxFactor times wider. SpreadMath#spacing also caps the spacing itself, so the crowding scan around a
    // candidate stays cheap.
    record Settings(double spacing, double spacingScale, double minFactor, double maxFactor, double species, double speciesScale, double detail, int seed) {
        static final Settings OFF = new Settings(0.0, 48.0, 0.4, 3.0, 0.0, 64.0, 0.4, 0);

        static Settings fromConfig() {
            return new Settings(
                    Config.SPACING_VARIATION.getAsDouble(),
                    Config.SPACING_VARIATION_SCALE.getAsInt(),
                    Config.SPACING_MIN_FACTOR.getAsDouble(),
                    Config.SPACING_MAX_FACTOR.getAsDouble(),
                    Config.SPECIES_VARIATION.getAsDouble(),
                    Config.SPECIES_VARIATION_SCALE.getAsInt(),
                    Config.VARIATION_DETAIL.getAsDouble(),
                    Config.VARIATION_SEED.getAsInt()
            );
        }
    }

    private static final long SPACING_SALT = 0x5EED_0001_C0FFEEL;
    private static final long SPECIES_SALT = 0x5EED_0002_BEEFL;
    private static final long FINE_SALT = 0x0DDBA11_F1DEL;

    // Sixty-four directions are plenty: the noise only has to look round, not be isotropic to the last digit.
    private static final int DIRECTIONS = 64;
    private static final double[] GRADIENT_X = new double[DIRECTIONS];
    private static final double[] GRADIENT_Z = new double[DIRECTIONS];

    static {
        for (int i = 0; i < DIRECTIONS; i++) {
            double angle = i * (2.0 * Math.PI / DIRECTIONS);
            GRADIENT_X[i] = Math.cos(angle);
            GRADIENT_Z[i] = Math.sin(angle);
        }
    }

    // The measured standard deviation of one octave of gradient noise (mean 0, 98% of it within +-2.2 of these, extremes
    // about +-3.1) - what turns it into a value with a variance of one, so that the config numbers mean the same at any
    // detail.
    private static final double GRADIENT_STD = 0.2162;

    // Set by the game tests, which need a field they know; null in the real game, where the config is the source.
    @Nullable
    private static volatile Settings override;

    private Variation() {
    }

    static void override(@Nullable Settings settings) {
        override = settings;
    }

    static Settings settings() {
        Settings pinned = override;
        return pinned != null ? pinned : Settings.fromConfig();
    }

    // ---- The two fields, in the world -----------------------------------------------------------------

    // What the bag's minimum spacing is multiplied by around `pos`: 1.0 with the spacing variation off.
    static double spacingFactor(ServerLevel level, BlockPos pos) {
        Settings settings = settings();
        if (settings.spacing() <= 0.0) {
            return 1.0;
        }
        return spacingFactor(settings, level.getSeed(), pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    // The pool weights around `pos`, each scaled by its species' own field: the nominal weights when the species
    // variation is off. Index for index with `options`.
    static double[] weights(ServerLevel level, BlockPos pos, List<SettleTable.Option> options) {
        Settings settings = settings();
        boolean varied = settings.species() > 0.0;
        long seed = varied ? level.getSeed() : 0L;
        double x = pos.getX() + 0.5;
        double z = pos.getZ() + 0.5;

        double[] weights = new double[options.size()];
        for (int i = 0; i < weights.length; i++) {
            SettleTable.Option option = options.get(i);
            weights[i] = varied ? option.weight() * speciesFactor(settings, seed, x, z, option.block()) : option.weight();
        }
        return weights;
    }

    // ---- The two fields, as maths ----------------------------------------------------------------------

    // exp(spacing x noise), scaled so the mean density (which goes with 1 / factor squared) over the world is the nominal one,
    // and kept within [minFactor, maxFactor]. The noise is high where the field says "sparse".
    static double spacingFactor(Settings settings, long worldSeed, double x, double z) {
        if (settings.spacing() <= 0.0) {
            return 1.0;
        }

        double noise = field(seedFor(worldSeed, settings.seed(), SPACING_SALT), x, z, settings.spacingScale(), settings.detail());
        double factor = calibration(settings) * Math.exp(settings.spacing() * noise);
        return Mth.clamp(factor, settings.minFactor(), settings.maxFactor());
    }

    // exp(species x noise) for this species' own field. Not scaled: only the ratios between the species of a pool matter.
    static double speciesFactor(Settings settings, long worldSeed, double x, double z, Block block) {
        if (settings.species() <= 0.0) {
            return 1.0;
        }

        long salt = SPECIES_SALT + BuiltInRegistries.BLOCK.getKey(block).hashCode() * 0x9E3779B97F4A7C15L;
        return Math.exp(settings.species() * field(seedFor(worldSeed, settings.seed(), salt), x, z, settings.speciesScale(), settings.detail()));
    }

    // ---- The noise --------------------------------------------------------------------------------------

    // Smooth noise at (x, z) with a variance of one and features about `scale` blocks across. `detail` mixes in a second
    // octave at twice the frequency, which roughens the borders of the big shapes without moving them.
    static double field(long seed, double x, double z, double scale, double detail) {
        double coarse = gradient(seed, x / scale, z / scale);
        if (detail <= 0.0) {
            return coarse / GRADIENT_STD;
        }

        double fine = gradient(seed ^ FINE_SALT, 2.0 * x / scale + 17.3, 2.0 * z / scale + 91.7);
        return (coarse + detail * fine) / (GRADIENT_STD * Math.sqrt(1.0 + detail * detail));
    }

    // Classic 2D gradient noise: a random unit vector at every whole lattice point, the value at a spot being the smooth
    // blend of the four surrounding points' vectors dotted with the offset to them. Zero at the lattice points themselves,
    // the peaks and hollows in between.
    private static double gradient(long seed, double x, double z) {
        int x0 = Mth.floor(x);
        int z0 = Mth.floor(z);
        double fx = x - x0;
        double fz = z - z0;
        double u = fade(fx);
        double v = fade(fz);

        double bottomLeft = corner(seed, x0, z0, fx, fz);
        double bottomRight = corner(seed, x0 + 1, z0, fx - 1.0, fz);
        double topLeft = corner(seed, x0, z0 + 1, fx, fz - 1.0);
        double topRight = corner(seed, x0 + 1, z0 + 1, fx - 1.0, fz - 1.0);

        double bottom = bottomLeft + (bottomRight - bottomLeft) * u;
        double top = topLeft + (topRight - topLeft) * u;
        return bottom + (top - bottom) * v;
    }

    private static double corner(long seed, int x, int z, double dx, double dz) {
        int direction = (int) (hash(seed, x, z) >>> 58);
        return GRADIENT_X[direction] * dx + GRADIENT_Z[direction] * dz;
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static long seedFor(long worldSeed, int variationSeed, long salt) {
        return mix(worldSeed ^ mix(variationSeed * 0x9E3779B97F4A7C15L + salt));
    }

    private static long hash(long seed, int x, int z) {
        return mix(seed ^ mix(x * 0x9E3779B97F4A7C15L + mix(z * 0xC2B2AE3D27D4EB4FL + seed)));
    }

    private static long mix(long h) {
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    // ---- Keeping the average density ----------------------------------------------------------------------

    // Density goes with 1 / spacing squared, so if the spacing only wobbled evenly around the bag's the world would end up
    // denser than asked for - the thickets gain more than the clearings lose. The spacing is scaled by the constant that puts
    // the average of 1 / factor squared back at one, found on a fixed sample of the noise itself (clamps included) - and
    // remembered until the settings change.
    private record Calibration(double sigma, double detail, double minFactor, double maxFactor, double factor) {
        boolean matches(Settings settings) {
            return sigma == settings.spacing() && detail == settings.detail() && minFactor == settings.minFactor() && maxFactor == settings.maxFactor();
        }
    }

    private static final long CALIBRATION_SEED = 0x5EEDCA11B0L;
    private static final int CALIBRATION_SIDE = 96;
    // Under one lattice cell between samples, so neighbours are still related but the sample covers ~4500 independent cells.
    private static final double CALIBRATION_STEP = 0.71;

    @Nullable
    private static volatile Calibration lastCalibration;

    static double calibration(Settings settings) {
        Calibration known = lastCalibration;
        if (known == null || !known.matches(settings)) {
            known = new Calibration(settings.spacing(), settings.detail(), settings.minFactor(), settings.maxFactor(), solveCalibration(settings));
            lastCalibration = known;
        }
        return known.factor();
    }

    private static double solveCalibration(Settings settings) {
        double[] noise = new double[CALIBRATION_SIDE * CALIBRATION_SIDE];
        int index = 0;
        for (int i = 0; i < CALIBRATION_SIDE; i++) {
            for (int j = 0; j < CALIBRATION_SIDE; j++) {
                noise[index++] = field(CALIBRATION_SEED, i * CALIBRATION_STEP, j * CALIBRATION_STEP, 1.0, settings.detail());
            }
        }

        // The mean of 1 / factor squared falls as the constant grows, so this is a plain bisection on its logarithm.
        double low = -4.0;
        double high = 4.0;
        for (int iteration = 0; iteration < 40; iteration++) {
            double middle = 0.5 * (low + high);
            double sum = 0.0;
            for (double value : noise) {
                double factor = Mth.clamp(Math.exp(middle + settings.spacing() * value), settings.minFactor(), settings.maxFactor());
                sum += 1.0 / (factor * factor);
            }
            if (sum / noise.length > 1.0) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return Math.exp(0.5 * (low + high));
    }
}
