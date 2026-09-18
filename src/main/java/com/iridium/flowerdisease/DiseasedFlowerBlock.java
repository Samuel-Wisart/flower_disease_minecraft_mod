package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

// Spreads via vanilla's random-tick sampler instead of a custom scheduler, so idle flowers cost nothing.
// One class is shared by every single-block diseased flower type; what it settles into is entirely
// driven by the Garden Bag's per-planting profile (see SettleTable/SpreadProfileBlockEntity). The
// two-block counterpart is DiseasedTallFlowerBlock. The actual spread/settle logic lives in
// DiseasedPlantLogic, shared with every other Diseased plant class.
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
        this.registerDefaultState(this.stateDefinition.any().setValue(SettleTable.SETTLED, false));
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
        DiseasedPlantLogic.randomTickSingle(state, level, pos, random, this, fallbackBlock);
    }
}
