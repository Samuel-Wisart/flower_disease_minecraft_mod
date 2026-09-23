package com.iridium.flowerdisease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// Per-planting data of a Diseased plant: the profile the Garden Bag configured (an immutable GardenBagContents,
// shared as-is by every plant of the same lineage) plus the one thing that's different for each plant - how deep
// in that lineage it is. Every diseased block gets one of these regardless of how it was planted: it's small and
// pure data (no ticker, nothing runs on it directly), so this avoids needing two versions of every block/resource
// file just to support the bag. A plant nothing configured simply has GardenBagContents.DEFAULT ("use the server
// defaults for everything").
//
// The remaining generation budget is never stored: it's the profile's cap minus the depth, so a child only needs
// its depth bumped - see DiseasedPlantLogic#generationsLeft.
public class SpreadProfileBlockEntity extends BlockEntity {
    static final long NO_GENERATIONS_OVERRIDE = -2;
    static final long INFINITE_GENERATIONS = -1;

    private GardenBagContents profile = GardenBagContents.DEFAULT;
    private long depth = 0;

    public SpreadProfileBlockEntity(BlockPos pos, BlockState state) {
        super(FlowerDisease.SPREAD_PROFILE_BLOCK_ENTITY.get(), pos, state);
    }

    GardenBagContents profile() {
        return profile;
    }

    // How many generations separate this plant from the one the bag planted (0 for the root).
    long depth() {
        return depth;
    }

    // Used by the debug command and by GardenBagItem when planting the root plant.
    void configure(GardenBagContents contents) {
        this.profile = contents;
        setChanged();
    }

    // How a spreading child inherits: it shares its parent's profile and sits one generation deeper.
    void inherit(GardenBagContents parentProfile, long childDepth) {
        this.profile = parentProfile;
        this.depth = childDepth;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (depth != 0) {
            tag.putLong("Depth", depth);
        }
        profile.writeTo(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        depth = tag.getLong("Depth");
        profile = GardenBagContents.readFrom(tag);
    }
}
