package com.iridium.flowerdisease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.VoxelShape;

// Where a plant can climb, and what the flower block (Stage 2) is allowed to corrupt - both resolved by
// datapack tag rather than a hardcoded block list, so modpack authors and other mods' blocks (custom
// wood/leaves, custom stone) work without a single line of code, and a pack can restrict or extend either
// list without a recompile. See PLANNING_STAGE2.md "Checklist de compatibilidade" for the reasoning. Also
// hosts the FACING property and shape/canSurvive helpers shared by every climbing-capable (single-block)
// species, since all of that only exists to serve this one feature.
final class PlantSupport {
    // Which way a single-block Diseased plant is oriented: UP is the ordinary "standing in the ground"
    // case (matches the pre-Stage-2 default in every way), a horizontal value means it's tilted, growing
    // out of the side of a climbable block that direction points AWAY from - the support block is always
    // pos.relative(facing.getOpposite()). No DOWN: hanging from a ceiling wasn't asked for. Only the 5
    // single-block spreading classes have this property; two-block species never tilt (see
    // PLANNING_STAGE2.md Fase 1 "Espécies de 2 blocos nunca inclinam").
    static final DirectionProperty FACING = DirectionProperty.create(
            "facing", Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    );

    private static final int TILT_MARGIN = 2;
    private static final int TILT_HEIGHT = 13;
    private static final int TILT_DEPTH = 6;

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

    // FACING=UP structural permission: either this plant's own ordinary ground rule already allows it
    // (unchanged case - dirt, grass, etc), or the block directly below is climbable, letting it stand on
    // top of a log/leaf pile the same way it'd stand on dirt.
    static boolean canStandOn(LevelReader level, BlockPos below) {
        return isClimbable(level.getBlockState(below));
    }

    // Horizontal FACING structural permission: the block behind this plant (opposite the direction it
    // points) needs to be climbable AND have a solid face pointing at this plant, the same requirement
    // vanilla uses for things like ladders/vines - a half-slab or a fence post isn't enough to cling to.
    static boolean canClingTo(LevelReader level, BlockPos pos, Direction facing) {
        BlockPos supportPos = pos.relative(facing.getOpposite());
        BlockState support = level.getBlockState(supportPos);
        return isClimbable(support) && support.isFaceSturdy(level, supportPos, facing);
    }

    // Deliberately reuses the SAME small box for every horizontal direction (just translated toward the
    // relevant wall) rather than a true rotated cross - getting an exact tilted silhouette right without
    // being able to render the game is unrealistic, see PLANNING_STAGE2.md "Riscos conhecidos". Good
    // enough for collision/selection; the tilted VISUAL comes entirely from the block model's rotation
    // (see the blockstate json), not from this shape.
    static VoxelShape tiltedShape(Direction facing) {
        return switch (facing) {
            case NORTH -> Block.box(TILT_MARGIN, 0, 16 - TILT_DEPTH, 16 - TILT_MARGIN, TILT_HEIGHT, 16);
            case SOUTH -> Block.box(TILT_MARGIN, 0, 0, 16 - TILT_MARGIN, TILT_HEIGHT, TILT_DEPTH);
            case WEST -> Block.box(16 - TILT_DEPTH, 0, TILT_MARGIN, 16, TILT_HEIGHT, 16 - TILT_MARGIN);
            case EAST -> Block.box(0, 0, TILT_MARGIN, TILT_DEPTH, TILT_HEIGHT, 16 - TILT_MARGIN);
            default -> throw new IllegalArgumentException("No tilted shape for " + facing);
        };
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
