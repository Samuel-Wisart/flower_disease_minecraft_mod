package com.iridium.flowerdisease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

// What a creeping flower adds to the garden and depth every Diseased plant has (see SpreadProfileBlockEntity): the
// energy of the patch it is growing (see PatchGrowth), and whether its own life as a member of the lineage is over.
//
// A creeper that is planted or born by reproduction is a SEED: it draws an energy, keeps reproducing like any other
// plant, and grows a patch of pieces around itself. Each piece the patch grows is created with one energy less than the
// piece it grew from, and is not a member of the lineage at all - it is "done" from birth, so it never reproduces and
// never corrupts terrain. When a piece has no energy left (or nowhere left to grow) and its life is over, it settles and
// its block entity goes; a finished patch costs nothing.
public class CreeperBlockEntity extends SpreadProfileBlockEntity {
    private int energy;
    private boolean lineageDone;

    public CreeperBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    // How many more pieces out from this one the patch may still grow; 0 = the patch has nothing more to do here.
    int energy() {
        return energy;
    }

    void setEnergy(int energy) {
        if (this.energy != energy) {
            this.energy = energy;
            setChanged();
        }
    }

    // Whether this block is done reproducing as a plant: always true for a patch piece, and true for a seed once its life
    // has ended but its patch is still growing.
    boolean lineageDone() {
        return lineageDone;
    }

    void markLineageDone() {
        if (!lineageDone) {
            lineageDone = true;
            setChanged();
        }
    }

    // A piece grown by a patch: no lineage to continue, just its share of the energy.
    void startPiece(int energy) {
        this.energy = energy;
        this.lineageDone = true;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (energy != 0) {
            tag.putByte("Energy", (byte) energy);
        }
        if (lineageDone) {
            tag.putBoolean("LineageDone", true);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = tag.getByte("Energy");
        lineageDone = tag.getBoolean("LineageDone");
    }
}
