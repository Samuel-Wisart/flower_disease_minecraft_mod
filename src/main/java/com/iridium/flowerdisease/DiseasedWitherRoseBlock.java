package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.WitherRoseBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;

// Same spread/settle behavior as DiseasedFlowerBlock (delegated to the shared DiseasedPlantLogic), but
// based on WitherRoseBlock instead of plain FlowerBlock, so it keeps the wither-damage-on-touch and the
// "can also grow on netherrack/soul sand/soul soil" ground rules of a real Wither Rose.
public class DiseasedWitherRoseBlock extends WitherRoseBlock implements EntityBlock {

    private final Block fallbackBlock;

    public DiseasedWitherRoseBlock(
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
