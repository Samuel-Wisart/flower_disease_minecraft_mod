package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
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
// climbable tag on purpose. One class covers all 4 species; what distinguishes them is just which instance
// gets registered (sunflower_creeper, lilac_creeper, rose_bush_creeper, peony_creeper), same as every other
// Diseased plant class here. Unlike those, though, there is no separate vanilla block this settles into -
// a creeping flower has no vanilla equivalent at all, so it always settles in place (see
// DiseasedPlantLogic#randomTickCreeping passing itself as its own fallback, and DiseasedPlantLogic#settle's
// "vanillaSpecies == diseasedSpecies" branch).
//
// Spreading is driven by the same shared random-tick engine as every other species (DiseasedPlantLogic),
// NOT vanilla's own MultifaceSpreader/bonemeal mechanic - an independent random jump within spreadDistance
// reads more like "the disease creeping across surfaces" than vanilla's strict face-to-face growth, and
// keeps every species (tilted, tall, creeping) governed by one engine instead of three. getSpreader() is
// still implemented (MultifaceBlock requires it), it's just never actually called by anything of ours.
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
}
