package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

// The Flower Block (Stage 2 Fase 3, see PLANNING_STAGE2.md): a slow, low-chance byproduct of a
// reproductive Diseased Flower corrupting the ground it's rooted in (see FlowerBlockLogic#maybeSpawn,
// called from DiseasedPlantLogic's own random tick). Looks/acts like Moss Block for now - a placeholder
// until real art exists - a full solid cube that's valid ground for the flower still growing on top of it
// (see the #minecraft:dirt tag entry in data/minecraft) which itself slowly corrupts its own immediate
// neighbors on its own random tick, independent of and slower than the flower above it (see
// FlowerBlockLogic#randomTick). Named to avoid colliding with vanilla's own FlowerBlock, which this mod
// already imports elsewhere.
public class FlowerMassBlock extends Block implements EntityBlock {

    public FlowerMassBlock(BlockBehaviour.Properties properties) {
        super(properties);
        this.registerDefaultState(this.defaultBlockState().setValue(SettleTable.SETTLED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SettleTable.SETTLED);
    }

    // A settled Flower Block stops costing random ticks entirely, same as every other Diseased plant - see
    // DiseasedPlantLogic#settle.
    @Override
    protected boolean isRandomlyTicking(BlockState state) {
        return !state.getValue(SettleTable.SETTLED);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FlowerMassBlockEntity(pos, state);
    }

    @Override
    protected void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        FlowerBlockLogic.randomTick(state, level, pos, random);
    }
}
