package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.TallFlowerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Two-block counterpart to DiseasedFlowerBlock, for the four TallFlowerBlock-based species (Sunflower,
// Lilac, Rose Bush, Peony). The actual spread/settle logic lives in DiseasedPlantLogic, shared with
// DiseasedTallGrassBlock (Tall Grass/Large Fern extend plain DoublePlantBlock instead, so they can't
// share a superclass with this class, only the static logic). Two-block species can stand on TOP of a
// climbable block (PlantSupport) but never tilt onto its side - no FACING property here, unlike the
// single-block classes (see PLANNING_STAGE2.md Fase 1 "Espécies de 2 blocos nunca inclinam").
public class DiseasedTallFlowerBlock extends TallFlowerBlock implements EntityBlock {

    private final Block fallbackBlock;

    public DiseasedTallFlowerBlock(
            Block fallbackBlock,
            BlockBehaviour.Properties properties
    ) {
        super(properties);
        this.fallbackBlock = fallbackBlock;
        this.registerDefaultState(this.stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(SettleTable.SETTLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SettleTable.SETTLED);
    }

    // A settled plant stops costing random ticks entirely instead of just no-op'ing on them - see
    // DiseasedPlantLogic#settle.
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return !state.getValue(SettleTable.SETTLED);
    }

    // Vanilla's own canSurvive already handles both halves correctly (lower checks the ground, upper
    // checks its lower neighbor is this same block) - this just widens the LOWER half's ground rule to
    // also accept standing on a climbable block. The OR is a no-op for the upper half: its own "ground"
    // is the lower half of this same plant, which is never itself tagged climbable.
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return super.canSurvive(state, level, pos) || PlantSupport.canStandOn(level, pos.below());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return SpreadProfileBlockEntity.create(pos, state);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        DiseasedPlantLogic.randomTickTall(state, level, pos, random, this, fallbackBlock);
    }
}
