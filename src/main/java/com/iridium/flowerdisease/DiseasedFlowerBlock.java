package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

// Spreads via vanilla's random-tick sampler instead of a custom scheduler, so idle flowers cost nothing.
// One class is shared by every single-block diseased flower type; what it settles into is entirely
// driven by the Garden Bag's per-planting profile (see SettleTable/SpreadProfileBlockEntity). The
// two-block counterpart is DiseasedTallFlowerBlock. The actual spread/settle logic lives in
// DiseasedPlantLogic, shared with every other Diseased plant class. Climbing (PlantSupport.FACING) is
// handled here rather than delegated, since canSurvive/getShape/getStateForPlacement all need to be
// overridden on the Block itself, not the shared logic class.
public class DiseasedFlowerBlock extends FlowerBlock implements EntityBlock {

    private final Block fallbackBlock;

    public DiseasedFlowerBlock(
            Holder<MobEffect> suspiciousStewEffect,
            float effectSeconds,
            Block fallbackBlock,
            BlockBehaviour.Properties properties
    ) {
        super(suspiciousStewEffect, effectSeconds, properties);
        this.fallbackBlock = fallbackBlock;
        this.registerDefaultState(this.stateDefinition.any().setValue(SettleTable.SETTLED, false).setValue(PlantSupport.FACING, Direction.UP));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SettleTable.SETTLED, PlantSupport.FACING);
    }

    // A settled plant stops costing random ticks entirely instead of just no-op'ing on them - see
    // DiseasedPlantLogic#settle.
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return !state.getValue(SettleTable.SETTLED);
    }

    // Standing upright works exactly like before (ordinary ground rules), OR on top of a climbable
    // block; tilted only needs a climbable block with a sturdy face behind it. This is unconditional -
    // whether a plant actually SEEKS a climbing spot when spreading is gated by the bag profile
    // (DiseasedPlantLogic), but once a plant already exists in a climbing spot, it must never lose
    // canSurvive just because nothing configured it to climb (that would make it vanish on reload).
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(PlantSupport.FACING);
        if (facing == Direction.UP) {
            return super.canSurvive(state, level, pos) || PlantSupport.canStandOn(level, pos.below());
        }
        return PlantSupport.canClingTo(level, pos, facing);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(PlantSupport.FACING);
        return facing == Direction.UP ? super.getShape(state, level, pos, context) : PlantSupport.tiltedShape(facing);
    }

    // Clicking the side of a climbable block tilts the flower away from it; clicking a top (or anything
    // else) stands it upright, same as before. Falls back from tilted to upright (never the reverse) if
    // the tilt wouldn't actually survive there.
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (context.getClickedFace().getAxis().isHorizontal()) {
            BlockState tilted = this.defaultBlockState().setValue(PlantSupport.FACING, context.getClickedFace());
            if (tilted.canSurvive(context.getLevel(), context.getClickedPos())) {
                return tilted;
            }
        }

        BlockState upright = this.defaultBlockState();
        return upright.canSurvive(context.getLevel(), context.getClickedPos()) ? upright : null;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpreadProfileBlockEntity(pos, state);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        DiseasedPlantLogic.randomTickSingle(state, level, pos, random, this, fallbackBlock);
    }
}
