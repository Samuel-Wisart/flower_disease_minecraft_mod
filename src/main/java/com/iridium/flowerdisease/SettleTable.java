package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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

    // Draws one option, each with its own weight from `weights` (index for index with `options`) - the pool's weights as
    // they stand at some place, once the species fields have had their say (see Variation#weights).
    @Nullable
    static Option pickWeighted(List<Option> options, double[] weights, RandomSource random) {
        double total = 0.0;
        for (double weight : weights) {
            total += weight;
        }
        if (total <= 0.0) {
            return null;
        }

        double roll = random.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0.0) {
                return options.get(i);
            }
        }
        // Only rounding gets here: the roll was within an ulp of the total.
        return options.get(options.size() - 1);
    }

    // "Same species" for density purposes: this block, its fallback, or any of its settle outcomes - and the
    // Diseased form of any of the pool's species, which is the same plant until it settles into the vanilla one.
    // A two-block plant's upper half is skipped since its paired lower half (scanned separately) already
    // represents the same physical plant - otherwise every tall flower would count double.
    static boolean isSameSpecies(BlockState state, Block self, Block fallback, List<Option> options) {
        if (state.isAir()) {
            return false;
        }
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

        // Only plants can be a Diseased form, and most of what a crowding scan looks at is not a plant.
        Block block = state.getBlock();
        if (block instanceof BushBlock || block instanceof CreepingFlowerBlock) {
            Block vanilla = FlowerDisease.fallbackByDiseased().get(block);
            if (vanilla != null) {
                for (Option option : options) {
                    if (option.block() == vanilla) {
                        return true;
                    }
                }
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

    // A plant that is not of this garden's pool: what a garden that ignores the others (Fermented Spider Eye) grows over. A
    // two-block plant is only ever met through its lower half (see isAnyPlant), and its species is whatever the state says - a
    // Diseased plant of another garden counts as foreign too, unless it is one of this pool's species.
    static boolean isForeignPlant(BlockState state, Block self, Block fallback, List<Option> options) {
        return isAnyPlant(state) && !isSameSpecies(state, self, fallback, options);
    }

    // Takes the plant at `pos` out of the world for good, the way growing over it does: no drops, no neighbour updates, and a
    // two-block plant goes whole so that its other half is not left floating. What was there had a block entity only if it was
    // an active Diseased plant, and that goes with the block.
    static void clearPlant(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)) {
            BlockPos other = state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
            if (level.getBlockState(other).is(state.getBlock())) {
                level.setBlock(other, Blocks.AIR.defaultBlockState(), PLACEMENT_FLAGS);
            }
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), PLACEMENT_FLAGS);
    }
}
