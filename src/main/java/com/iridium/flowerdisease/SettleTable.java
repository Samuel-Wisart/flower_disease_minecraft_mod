package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Shared species-pool parsing/weighting logic for every Diseased plant (see DiseasedPlantLogic for what
// actually spreads/settles). Config entries look like "<block id> <weight>" - every entry names the exact
// final block it means (a vanilla species for a full plant, or one of the decorative/spreading Top/Bottom
// blocks for those - they're independent species in their own right, not a half of something else).
final class SettleTable {

    // UPDATE_CLIENTS alone still lets the engine reactively re-check canSurvive on neighbors (that's
    // gated separately by UPDATE_KNOWN_SHAPE) - without it, placing/clearing one half of a two-block
    // plant made the engine "notice" the other half looks invalid and destroy it on the spot, complete
    // with an item drop. UPDATE_SUPPRESS_DROPS is extra insurance against that same class of bug.
    static final int PLACEMENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    // Whether this exact plant has stopped random-ticking for good (see DiseasedPlantLogic#settle). A
    // settled plant keeps whatever block/shape/facing it already had - it doesn't necessarily become a
    // distinct vanilla block, since some species (climbing onto non-plantable ground, or the creeping
    // species entirely, see Stage 2 planning) have no vanilla equivalent that could survive there. Shared
    // across every spreading block class; doesn't affect the model, so blockstate JSON uses "multipart"
    // to ignore it entirely instead of needing a variant per value.
    static final BooleanProperty SETTLED = BooleanProperty.create("settled");

    record Option(Block block, int weight) {
    }

    private SettleTable() {
    }

    static List<Option> parse(List<? extends String> entries) {
        List<Option> options = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length != 2) {
                continue;
            }

            try {
                ResourceLocation id = ResourceLocation.parse(parts[0]);
                if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                    continue;
                }

                Block block = BuiltInRegistries.BLOCK.get(id);
                int weight = Integer.parseInt(parts[1]);
                if (block != Blocks.AIR && weight > 0) {
                    options.add(new Option(block, weight));
                }
            } catch (Exception ignored) {
                // Malformed entry (bad id, bad weight); skip it rather than crash the server over a typo.
            }
        }
        return options;
    }

    @Nullable
    static Option pickWeighted(List<Option> options, RandomSource random) {
        int total = 0;
        for (Option option : options) {
            total += option.weight();
        }
        if (total <= 0) {
            return null;
        }

        int roll = random.nextInt(total);
        for (Option option : options) {
            roll -= option.weight();
            if (roll < 0) {
                return option;
            }
        }
        return null;
    }

    // "Same species" for density purposes: this block, its fallback, or any of its settle outcomes.
    // A two-block plant's upper half is skipped since its paired lower half (scanned separately) already
    // represents the same physical plant - otherwise every tall flower would count double.
    static boolean isSameSpecies(BlockState state, Block self, Block fallback, List<Option> options) {
        if (state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return false;
        }

        if (state.is(self) || state.is(fallback)) {
            return true;
        }

        for (Option option : options) {
            if (state.is(option.block())) {
                return true;
            }
        }
        return false;
    }

    // "Territorial" mode (Fence in the bag): any plant at all counts as crowding, not just this
    // species' own family - so a rose garden won't spread into the gaps of an existing peony garden.
    // Same upper-half skip as isSameSpecies, so a two-block plant still only counts once. Recognizes our
    // own CreepingFlowerBlock too (Stage 2 Fase 2) - deliberately NOT every MultifaceBlock, so vanilla Glow
    // Lichen/Sculk Vein in the world don't count as "a plant" for this unrelated mechanic.
    static boolean isAnyPlant(BlockState state) {
        if (state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return false;
        }
        return state.getBlock() instanceof BushBlock || state.getBlock() instanceof CreepingFlowerBlock;
    }

    // Converts a player-facing "desired flowers per 16x16 area" target into the maxNearby cap actually
    // used by the (fixed) internal density-check radius, so the mechanic can stay the same while the
    // number the player configures means something intuitive regardless of that internal radius.
    static int densityTargetToMaxNearby(int desiredPer16x16, int radius) {
        int side = 2 * radius + 1;
        double area = (double) side * side;
        return Math.max(1, (int) Math.round(desiredPer16x16 * (area / 256.0)));
    }
}
