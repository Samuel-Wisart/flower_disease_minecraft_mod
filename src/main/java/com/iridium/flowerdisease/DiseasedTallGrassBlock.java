package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// Two-block diseased grass/fern (Tall Grass and Large Fern both use vanilla's plain DoublePlantBlock
// class directly - no bonemeal behavior, unlike TallFlowerBlock), so one class covers both species. The
// actual spread/settle logic lives in DiseasedPlantLogic, shared with DiseasedTallFlowerBlock.
public class DiseasedTallGrassBlock extends DoublePlantBlock implements EntityBlock {

    private final Block fallbackBlock;

    public DiseasedTallGrassBlock(
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

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SpreadProfileBlockEntity(pos, state);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        DiseasedPlantLogic.randomTickTall(state, level, pos, random, this, fallbackBlock);
    }
}
