package com.iridium.flowerdisease;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

// A plain, single-block, non-spreading flower - used as the "upper" settle outcome for two-block
// plants (Sunflower/Lilac/Rose Bush/Peony). Placing only the real upper half of one of those would be
// fragile: DoublePlantBlock.canSurvive requires a matching lower half directly underneath, so it can
// get destroyed (with a drop) the moment anything nearby triggers a neighbor update. This is a genuinely
// standalone block using the same "top" texture, so it behaves like any other ordinary flower instead.
public class DecorativeFlowerBlock extends BushBlock {
    public static final MapCodec<DecorativeFlowerBlock> CODEC = simpleCodec(DecorativeFlowerBlock::new);
    // Package-visible so DiseasedDecorativeFlowerBlock (the spreading counterpart) can render identically.
    static final VoxelShape SHAPE = Block.box(5.0, 0.0, 5.0, 11.0, 10.0, 11.0);

    @Override
    public MapCodec<DecorativeFlowerBlock> codec() {
        return CODEC;
    }

    public DecorativeFlowerBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }
}
