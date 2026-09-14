package com.iridium.flowerdisease;

import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.common.ModConfigSpec;

// Two-block diseased grass/fern (Tall Grass and Large Fern both use vanilla's plain DoublePlantBlock
// class directly - no bonemeal behavior, unlike TallFlowerBlock), so one class covers both species. The
// actual spread/settle logic lives in DiseasedTallPlantLogic, shared with DiseasedTallFlowerBlock.
public class DiseasedTallGrassBlock extends DoublePlantBlock implements EntityBlock {

    private final Block fallbackBlock;
    private final ModConfigSpec.ConfigValue<List<? extends String>> settleWeights;

    public DiseasedTallGrassBlock(
            Block fallbackBlock,
            ModConfigSpec.ConfigValue<List<? extends String>> settleWeights,
            BlockBehaviour.Properties properties
    ) {
        super(properties);
        this.fallbackBlock = fallbackBlock;
        this.settleWeights = settleWeights;
        this.registerDefaultState(this.stateDefinition.any().setValue(HALF, DoubleBlockHalf.LOWER).setValue(SettleTable.GENERATION, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SettleTable.GENERATION);
    }

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
        DiseasedTallPlantLogic.randomTick(state, level, pos, random, this, fallbackBlock, settleWeights);
    }
}
