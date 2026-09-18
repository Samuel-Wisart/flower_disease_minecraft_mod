package com.iridium.flowerdisease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

// Where a plant can climb, and what the flower block (Stage 2) is allowed to corrupt - both resolved by
// datapack tag rather than a hardcoded block list, so modpack authors and other mods' blocks (custom
// wood/leaves, custom stone) work without a single line of code, and a pack can restrict or extend either
// list without a recompile. See PLANNING_STAGE2.md "Checklist de compatibilidade" for the reasoning.
final class PlantSupport {
    // Logs, leaves, moss, mangrove/muddy mangrove roots by default (see the tag's own json for the exact
    // list) - anywhere a Diseased plant with "climbing" on in its profile is allowed to grow on top of or
    // tilted against, in addition to its own normal (dirt/grass/etc) ground rules.
    static final TagKey<Block> CLIMBABLE = tag("climbable");

    // What the flower block is allowed to convert: dirt-family, sand, gravel, overworld stone, logs,
    // leaves by default.
    static final TagKey<Block> CONVERTIBLE = tag("convertible");

    // Explicit veto, checked in ADDITION to (not instead of) the structural safety checks in
    // isConvertible() below - bedrock/obsidian/portal frames/spawners/command & structure blocks by
    // default. A modpack can add to this to protect its own special blocks without needing CONVERTIBLE to
    // exclude them structurally.
    static final TagKey<Block> CONVERSION_IMMUNE = tag("conversion_immune");

    private PlantSupport() {
    }

    static boolean isClimbable(BlockState state) {
        return state.is(CLIMBABLE);
    }

    // Defense in depth for a feature that permanently destroys world content: even a modpack that
    // mistags something into CONVERTIBLE can't have the flower block eat a block with saved data (a
    // chest, any other mod's machine) or something meant to be indestructible.
    static boolean isConvertible(LevelReader level, BlockPos pos, BlockState state) {
        if (!state.is(CONVERTIBLE) || state.is(CONVERSION_IMMUNE)) {
            return false;
        }
        if (level.getBlockEntity(pos) != null) {
            return false;
        }
        return state.getDestroySpeed(level, pos) >= 0;
    }

    private static TagKey<Block> tag(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(FlowerDisease.MODID, path));
    }
}
