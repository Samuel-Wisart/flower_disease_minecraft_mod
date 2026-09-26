package com.iridium.flowerdisease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

// What a creeping flower adds to the garden and depth every Diseased plant has (see SpreadProfileBlockEntity): the
// budget of the patch it is growing (see PatchGrowth), and whether its own life as a member of the lineage is over.
//
// A creeper that is planted or born by reproduction is a SEED: it draws a style and a budget, keeps reproducing like any other
// plant, and grows a patch of pieces around itself. A patch is made of tendrils, and only the growing END of each one keeps a
// block entity: it knows the face it is crawling on and its heading along it (which is how it keeps going in a line and follows
// a surface around an edge), its style, how much budget it still has and - for a hanging strand - how many pieces are left to
// hang. Each piece a tendril grows is created with less budget than the one it grew from and is not a member of the lineage at
// all: it is "done" from birth, so it never reproduces and never corrupts terrain. A piece with no budget left has no use for
// a block entity, so it settles and drops it; a finished patch costs nothing.
public class CreeperBlockEntity extends SpreadProfileBlockEntity {
    private int energy;
    private boolean lineageDone;
    // Grown by a patch rather than planted or born: sterile, and it belongs to the garden of the seed it grew from
    // (which is what lets a garden-wide action reach its patches too) without being one of its plants.
    private boolean piece;
    // The growing end of a tendril: the face of this block it crawls on, the direction along that surface it heads, the style of
    // the seed it belongs to (PatchGrowth.Style) and, for a hanging strand, how many more pieces it is going to hang.
    private Direction face = Direction.DOWN;
    private Direction heading = Direction.NORTH;
    private int style;
    private int hang;

    public CreeperBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    // How many more pieces this end of the tendril may still grow; 0 = the patch has nothing more to do here.
    int energy() {
        return energy;
    }

    void setEnergy(int energy) {
        if (this.energy != energy) {
            this.energy = energy;
            setChanged();
        }
    }

    Direction face() {
        return face;
    }

    Direction heading() {
        return heading;
    }

    int style() {
        return style;
    }

    int hang() {
        return hang;
    }

    // The growing end goes on from here with what it has left.
    void setFront(int energy, Direction face, Direction heading, int hang) {
        this.energy = energy;
        this.face = face;
        this.heading = heading;
        this.hang = hang;
        setChanged();
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

    boolean isPiece() {
        return piece;
    }

    // A seed's patch: its style and budget, and the surface it starts out crawling on.
    void startPatch(int energy, int style, Direction face, Direction heading) {
        this.energy = energy;
        this.style = style;
        this.face = face;
        this.heading = heading;
        this.hang = 0;
        setChanged();
    }

    // The growing end of a tendril, on a piece grown by a patch: no lineage to continue, just its share of the budget - and the
    // garden it came from.
    void startPiece(int energy, int garden, int style, Direction face, Direction heading, int hang) {
        this.energy = energy;
        this.lineageDone = true;
        this.piece = true;
        this.style = style;
        this.face = face;
        this.heading = heading;
        this.hang = hang;
        inherit(garden, 0);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (energy != 0) {
            tag.putByte("Energy", (byte) energy);
            // Only a block that is still growing has a direction to remember.
            tag.putByte("Face", (byte) face.get3DDataValue());
            tag.putByte("Heading", (byte) heading.get3DDataValue());
            if (style != 0) {
                tag.putByte("Style", (byte) style);
            }
            if (hang != 0) {
                tag.putByte("Hang", (byte) hang);
            }
        }
        if (lineageDone) {
            tag.putBoolean("LineageDone", true);
        }
        if (piece) {
            tag.putBoolean("Piece", true);
        }
    }

    // A creeper saved before tendrils has no face, heading or style: it reads as a streak on its down face, which PatchGrowth
    // corrects (to a face the block really has) the first time it grows.
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        energy = tag.getByte("Energy");
        face = Direction.from3DDataValue(tag.getByte("Face"));
        heading = tag.contains("Heading") ? Direction.from3DDataValue(tag.getByte("Heading")) : Direction.NORTH;
        style = tag.getByte("Style");
        hang = tag.getByte("Hang");
        lineageDone = tag.getBoolean("LineageDone");
        piece = tag.getBoolean("Piece");
    }
}
