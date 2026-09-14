package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.registries.DeferredBlock;

// Shared "what does this diseased plant settle into" logic, used by both DiseasedFlowerBlock
// (single-block flowers) and DiseasedTallFlowerBlock (two-block flowers like Rose Bush). Config
// entries look like "<block id> <weight> [full|lower|upper]"; the half only matters when the
// target itself is a two-block plant.
final class SettleTable {

    // UPDATE_CLIENTS alone still lets the engine reactively re-check canSurvive on neighbors (that's
    // gated separately by UPDATE_KNOWN_SHAPE) - without it, placing/clearing one half of a two-block
    // plant made the engine "notice" the other half looks invalid and destroy it on the spot, complete
    // with an item drop. UPDATE_SUPPRESS_DROPS is extra insurance against that same class of bug.
    static final int PLACEMENT_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    // How many more generations of children a Diseased Flower may still produce (see Config.FLOWER_MAX_GENERATIONS
    // and DiseasedFlowerBlock/DiseasedTallFlowerBlock). Shared here since both block classes need the same
    // property, and it doesn't affect the model, so blockstate JSON uses "multipart" to ignore it entirely
    // instead of needing a variant per generation value.
    static final IntegerProperty GENERATION = IntegerProperty.create("generation", 0, 64);

    enum Half {
        FULL, LOWER, UPPER
    }

    record Option(Block block, int weight, Half half) {
    }

    private SettleTable() {
    }

    static List<Option> parse(List<? extends String> entries) {
        List<Option> options = new ArrayList<>();
        for (String entry : entries) {
            String[] parts = entry.trim().split("\\s+");
            if (parts.length < 2 || parts.length > 3) {
                continue;
            }

            try {
                ResourceLocation id = ResourceLocation.parse(parts[0]);
                if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                    continue;
                }

                Block block = BuiltInRegistries.BLOCK.get(id);
                int weight = Integer.parseInt(parts[1]);
                Half half = parts.length == 3 ? Half.valueOf(parts[2].toUpperCase(Locale.ROOT)) : Half.FULL;
                if (block != Blocks.AIR && weight > 0) {
                    options.add(new Option(block, weight, half));
                }
            } catch (Exception ignored) {
                // Malformed entry (bad id, bad weight, unknown half); skip it rather than crash
                // the server over a typo in the config.
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

    // Places the chosen outcome at pos. For a two-block target, "full" places both halves properly
    // (the correct, botanically complete result); "lower" places just the bottom half - a deliberately
    // partial, shorter look some players like better in a dense field, and it's stable on its own since
    // a lower half's canSurvive only cares about the ground below it, same as any single-block flower.
    static void place(ServerLevel level, BlockPos pos, Option option) {
        Block block = option.block();
        if (block instanceof DoublePlantBlock) {
            switch (option.half()) {
                case LOWER -> level.setBlock(pos, block.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), PLACEMENT_FLAGS);
                case UPPER -> placeTop(level, pos, block);
                case FULL -> DoublePlantBlock.placeAt(level, block.defaultBlockState(), pos, PLACEMENT_FLAGS);
            }
        } else {
            level.setBlock(pos, block.defaultBlockState(), PLACEMENT_FLAGS);
        }
    }

    // An upper half's canSurvive requires an actual lower half of the same block directly beneath it,
    // so an orphaned upper half is inherently fragile (any later neighbor update near it can pop it with
    // a drop). Placing the matching standalone decorative "top" flower instead sidesteps that entirely.
    // Falls back to a full two-block placement for any DoublePlantBlock we don't have one registered for.
    private static void placeTop(ServerLevel level, BlockPos pos, Block tallFlower) {
        DeferredBlock<DecorativeFlowerBlock> decorativeTop = FlowerDisease.DECORATIVE_TOPS.get(tallFlower);
        if (decorativeTop != null) {
            level.setBlock(pos, decorativeTop.get().defaultBlockState(), PLACEMENT_FLAGS);
        } else {
            DoublePlantBlock.placeAt(level, tallFlower.defaultBlockState(), pos, PLACEMENT_FLAGS);
        }
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

            // An "upper" option is actually represented on the ground by its decorative top stand-in
            // (see placeTop), not by the tall flower block named in the option itself.
            if (option.half() == Half.UPPER) {
                DeferredBlock<DecorativeFlowerBlock> decorativeTop = FlowerDisease.DECORATIVE_TOPS.get(option.block());
                if (decorativeTop != null && state.is(decorativeTop.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    // "Territorial" mode (Fence in the bag): any plant at all counts as crowding, not just this
    // species' own family - so a rose garden won't spread into the gaps of an existing peony garden.
    // Same upper-half skip as isSameSpecies, so a two-block plant still only counts once.
    static boolean isAnyPlant(BlockState state) {
        if (state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            return false;
        }
        return state.getBlock() instanceof BushBlock;
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
