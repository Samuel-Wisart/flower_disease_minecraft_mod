package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

// Creeping variant of the 4 big flowers (see PLANNING_STAGE2.md Fase 2) - same base as vanilla Glow Lichen/
// Sculk Vein (MultifaceBlock): occupies any combination of a block's 6 faces, grows onto any structurally
// solid face, organic or not, unlike the tilt/climb feature (DiseasedFlowerBlock), which is gated to the
// climbable tag on purpose. One class covers every species; what distinguishes them is just which instance
// gets registered (sunflower_creeper, lilac_creeper, rose_bush_creeper, peony_creeper, oxeye_daisy_creeper), same as every other
// Diseased plant class here. Unlike those, though, there is no separate vanilla block this settles into -
// a creeping flower has no vanilla equivalent at all, so it always settles in place (see
// DiseasedPlantLogic#randomTickCreeping passing itself as its own fallback, and DiseasedPlantLogic#settle's
// "vanillaSpecies == diseasedSpecies" branch).
//
// Reproduction is driven by the same shared random-tick engine as every other species (DiseasedPlantLogic): an
// independent jump within the spread distance reads more like "the disease creeping across surfaces" than vanilla's
// strict face-to-face growth, and keeps every species (tilted, tall, creeping) governed by one engine. On top of that, a
// creeper grows a small PATCH of pieces around itself with vanilla's own MultifaceSpreader (see PatchGrowth), which is
// what getSpreader() is for - MultifaceBlock requires it, and the patch reuses its rules for where a piece may grow.
//
// One rule differs from every other multiface block: a piece on a SIDE face may also be held by the piece above it carrying the
// same face - the way a vine hangs from the vine above it - instead of by something solid. That is what lets a patch hang strands
// below the edge of a wall (see PatchGrowth), and it works like a vine's: nothing else in the mod places such a piece, and taking
// away what holds the top of a strand (the wall, or the piece above) brings the strand down piece by piece, as a vine falls.
public class CreepingFlowerBlock extends MultifaceBlock implements EntityBlock {

    private static final MapCodec<CreepingFlowerBlock> CODEC = simpleCodec(CreepingFlowerBlock::new);
    private final MultifaceSpreader spreader = new MultifaceSpreader(this);

    public CreepingFlowerBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(SettleTable.SETTLED, false));
    }

    @Override
    protected MapCodec<? extends MultifaceBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SettleTable.SETTLED);
    }

    // A settled creeping flower stops costing random ticks entirely, same as every other Diseased plant -
    // see DiseasedPlantLogic#settle.
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return !state.getValue(SettleTable.SETTLED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return SpreadProfileBlockEntity.create(pos, state);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        DiseasedPlantLogic.randomTickCreeping(state, level, pos, random, this);
    }

    @Override
    public MultifaceSpreader getSpreader() {
        return this.spreader;
    }

    // ---- Hanging ---------------------------------------------------------------------------------------

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        boolean any = false;
        for (Direction direction : DIRECTIONS) {
            if (hasFace(state, direction)) {
                if (!isHeld(level, pos, direction, null, null)) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    // As vanilla's, except that a face that stopped being held is only lost if it does not hang: a change beside or below a piece
    // costs the face on that side its hold, and one above it can cost every side face that hung from the piece there.
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!hasAnyFace(state)) {
            return Blocks.AIR.defaultBlockState();
        }

        BlockState result = state;
        for (Direction face : DIRECTIONS) {
            boolean affected = face == direction || (direction == Direction.UP && face.getAxis().isHorizontal());
            if (affected && hasFace(result, face) && !isHeld(level, pos, face, direction, neighborState)) {
                result = result.setValue(getFaceProperty(face), false);
                if (!hasAnyFace(result)) {
                    return Blocks.AIR.defaultBlockState();
                }
            }
        }
        return result;
    }

    // Whether the piece at `pos` has something to hold its face on `face` in place: a block to attach to, as for any multiface
    // block, or - on a side face - the piece above carrying the same face. `changed` is the direction of the neighbour an update
    // is about, and `changedState` what it has become (the level may not show it yet); null when the whole block is being asked.
    private static boolean isHeld(BlockGetter level, BlockPos pos, Direction face, @Nullable Direction changed, @Nullable BlockState changedState) {
        BlockPos support = pos.relative(face);
        BlockState supportState = changed == face ? changedState : level.getBlockState(support);
        if (canAttachTo(level, face, support, supportState)) {
            return true;
        }
        return face.getAxis().isHorizontal() && hangsFrom(changed == Direction.UP ? changedState : level.getBlockState(pos.above()), face);
    }

    // Whether the piece in `above` holds a side face hanging under it: it is one of ours and has that very face.
    static boolean hangsFrom(BlockState above, Direction face) {
        return above.getBlock() instanceof CreepingFlowerBlock && hasFace(above, face);
    }
}
