package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
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
// share a superclass with this class, only the static logic).
public class DiseasedTallFlowerBlock extends TallFlowerBlock implements EntityBlock {

    private final Block fallbackBlock;

    public DiseasedTallFlowerBlock(
            Block fallbackBlock,
            BlockBehaviour.Properties properties
    ) {
        super(properties);
        this.fallbackBlock = fallbackBlock;
        this.registerDefaultState(this.stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(SettleTable.GENERATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SettleTable.GENERATION);
    }

    // A hand-planted flower always starts with a full budget of generations (see Config.FLOWER_MAX_GENERATIONS).
    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        return state == null ? null : state.setValue(SettleTable.GENERATION, Config.FLOWER_MAX_GENERATIONS.getAsInt());
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
