package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

// A spreading counterpart to DecorativeFlowerBlock: the player asked for Top/Bottom species (e.g. "Rose
// Bush Top") to behave as their own independent flowers in the Garden Bag - plantable, spreadable, and
// settling back into their own plain DecorativeFlowerBlock - rather than only existing as a settle-only
// stand-in for the two-block species' half. Same single-block shape/rendering as DecorativeFlowerBlock
// (reuses its SHAPE), same spread/settle machinery as every other single-block Diseased plant.
public class DiseasedDecorativeFlowerBlock extends BushBlock implements EntityBlock {
    public static final MapCodec<DiseasedDecorativeFlowerBlock> CODEC = simpleCodec(DiseasedDecorativeFlowerBlock::new);

    private final Block fallbackBlock;

    // Compatibility with BushBlock::simpleCodec (single-Properties constructor); only used indirectly via
    // the codec machinery, never called directly - real registration always goes through the two-arg
    // constructor below via FlowerDisease#registerDiseasedDecorative. AIR is a harmless placeholder for
    // fallbackBlock here since this path never produces the actual registered singleton.
    private DiseasedDecorativeFlowerBlock(BlockBehaviour.Properties properties) {
        this(Blocks.AIR, properties);
    }

    public DiseasedDecorativeFlowerBlock(Block fallbackBlock, BlockBehaviour.Properties properties) {
        super(properties);
        this.fallbackBlock = fallbackBlock;
        this.registerDefaultState(this.stateDefinition.any().setValue(SettleTable.GENERATION, 0));
    }

    @Override
    public MapCodec<DiseasedDecorativeFlowerBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return DecorativeFlowerBlock.SHAPE;
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
        DiseasedPlantLogic.randomTickSingle(state, level, pos, random, this, fallbackBlock);
    }
}
