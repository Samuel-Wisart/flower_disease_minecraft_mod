package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.TallGrassBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

// Single-block diseased grass/fern (Short Grass and Fern both use vanilla's plain TallGrassBlock class,
// so one class here covers both species - just registered twice with different fallback blocks).
// Delegates to DiseasedPlantLogic, same as DiseasedFlowerBlock/DiseasedWitherRoseBlock. Climbing
// (PlantSupport.FACING) is handled here rather than delegated, same reasoning as DiseasedFlowerBlock.
public class DiseasedGrassBlock extends TallGrassBlock implements EntityBlock {

    private final Block fallbackBlock;

    public DiseasedGrassBlock(
            Block fallbackBlock,
            BlockBehaviour.Properties properties
    ) {
        super(properties);
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
