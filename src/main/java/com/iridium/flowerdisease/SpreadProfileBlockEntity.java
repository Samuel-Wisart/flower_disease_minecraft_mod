package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// What a Diseased plant remembers: which garden it belongs to (the profile the Garden Bag configured lives in the
// GardenRegistry, shared by every plant of the garden) and how deep in the lineage it is. A plant nothing configured
// has no garden and uses the server defaults. The block entity is small pure data - no ticker, nothing runs on it
// directly.
//
// Only a plant that still has something to do keeps one: a plant that has settled in place is never ticked again, so
// it drops its block entity (see DiseasedPlantLogic#settle), and the upper half of a two-block plant never had a use
// for one (see #create).
//
// The remaining generation budget is never stored: it's the profile's cap minus the depth, so a child only needs its
// depth bumped - see DiseasedPlantLogic#generationsLeft.
public class SpreadProfileBlockEntity extends BlockEntity {
    static final long NO_GENERATIONS_OVERRIDE = -2;
    static final long INFINITE_GENERATIONS = -1;

    private int garden = GardenRegistry.NO_GARDEN;
    private int depth;
    // A profile read out of a save from before gardens existed, waiting to be folded into the registry - which needs
    // the level, and so can only happen once the block entity is in one.
    @Nullable
    private GardenBagContents legacyProfile;
    private GardenBagContents cachedProfile = GardenBagContents.DEFAULT;
    private int cachedVersion = -1;

    public SpreadProfileBlockEntity(BlockPos pos, BlockState state) {
        super(FlowerDisease.SPREAD_PROFILE_BLOCK_ENTITY.get(), pos, state);
    }

    // The factory of the block entity TYPE, which every block that shares it goes through when a chunk loads: a saved
    // block entity is rebuilt from its tag by BlockEntity#loadStatic, which asks the type - never the block - to make
    // the empty one. So this has to produce the right class for each of them, or a Flower Block would come back as a
    // plain plant entity that has forgotten the ground it replaced.
    static SpreadProfileBlockEntity forBlock(BlockPos pos, BlockState state) {
        Block block = state.getBlock();
        if (block instanceof FlowerMassBlock) {
            return new FlowerMassBlockEntity(pos, state);
        }
        if (block instanceof CreepingFlowerBlock) {
            return new CreeperBlockEntity(pos, state);
        }
        return new SpreadProfileBlockEntity(pos, state);
    }

    // What EntityBlock#newBlockEntity of every Diseased block returns: nothing for a plant that has settled, and
    // nothing for the upper half of a tall one (only the lower half ever acts).
    @Nullable
    static SpreadProfileBlockEntity create(BlockPos pos, BlockState state) {
        boolean upperHalf = state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER;
        if (upperHalf || DiseasedPlantLogic.isSettled(state)) {
            return null;
        }
        return forBlock(pos, state);
    }

    int garden() {
        return garden;
    }

    // How many generations separate this plant from the one the bag planted (0 for that one).
    long depth() {
        return depth;
    }

    Lineage lineage() {
        return new Lineage(garden, profile(), depth);
    }

    // The garden's profile, or the server defaults for a plant with none. Read through the registry, but cached: the
    // registry says when a profile has been replaced (its version), and until then this is a plain field read.
    GardenBagContents profile() {
        if (!(level instanceof ServerLevel server)) {
            return legacyProfile != null ? legacyProfile : GardenBagContents.DEFAULT;
        }

        GardenRegistry registry = GardenRegistry.of(server);
        if (legacyProfile != null) {
            garden = registry.intern(legacyProfile);
            legacyProfile = null;
            setChanged();
        }
        if (garden == GardenRegistry.NO_GARDEN) {
            return GardenBagContents.DEFAULT;
        }

        if (cachedVersion != registry.version()) {
            cachedProfile = registry.profileOf(garden);
            cachedVersion = registry.version();
        }
        return cachedProfile;
    }

    // The root of a planting: a garden of its own, made from what the bag held.
    void startGarden(ServerLevel level, GardenBagContents contents, BlockPos origin) {
        this.garden = GardenRegistry.of(level).create(contents, origin, level.getGameTime());
        this.depth = 0;
        this.legacyProfile = null;
        this.cachedVersion = -1;
        setChanged();
    }

    // How a spreading child inherits: it joins its parent's garden, one generation deeper.
    void inherit(int parentGarden, long childDepth) {
        this.garden = parentGarden;
        this.depth = (int) Math.min(childDepth, Integer.MAX_VALUE);
        this.legacyProfile = null;
        this.cachedVersion = -1;
        setChanged();
    }

    // Forgets how deep in the lineage this was, for a block that has stopped acting and keeps only its garden.
    void forgetDepth() {
        if (depth != 0) {
            depth = 0;
            setChanged();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (garden != GardenRegistry.NO_GARDEN) {
            tag.putInt("Garden", garden);
        }
        if (depth != 0) {
            tag.putInt("Depth", depth);
        }
        if (legacyProfile != null) {
            // Not folded in yet (nothing has needed it since it was loaded): keep it as it was.
            legacyProfile.writeTo(tag);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        garden = tag.getInt("Garden");
        depth = tag.getInt("Depth");
        legacyProfile = null;
        cachedVersion = -1;
        if (garden == GardenRegistry.NO_GARDEN) {
            GardenBagContents embedded = GardenBagContents.readFrom(tag);
            if (!embedded.equals(GardenBagContents.DEFAULT)) {
                legacyProfile = embedded;
            }
        }
    }
}
