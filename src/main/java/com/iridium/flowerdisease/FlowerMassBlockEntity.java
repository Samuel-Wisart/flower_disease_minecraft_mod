package com.iridium.flowerdisease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

// The garden and depth every Diseased plant has (see SpreadProfileBlockEntity), plus what is unique to a Flower Block:
// the exact BlockState it replaced when it corrupted this position (see PLANNING_STAGE2.md 3.4), which lets /cleargarden
// restore the original terrain instead of leaving a hole and leaves room for a future "cure" item that reverts the
// corruption, and the number of generations THIS block may spread (its own draw, not the garden's - see
// SpreadMath#rollFlowerBlockGenerations). Once it settles it keeps only its garden and the replaced state.
//
// Kept off the shared base class since every other Diseased plant has nothing to restore - there was never a
// non-diseased block here to begin with. Reuses SpreadProfileBlockEntity's own BlockEntityType (see FlowerDisease#
// SPREAD_PROFILE_BLOCK_ENTITY) rather than registering a new one - the type's own factory is never actually used for
// construction (every block here builds its BlockEntity itself via EntityBlock#newBlockEntity, see FlowerMassBlock),
// only for the block-to-type compatibility check, which a subclass satisfies the same way the base class does.
public class FlowerMassBlockEntity extends SpreadProfileBlockEntity {
    private BlockState replacedState = Blocks.AIR.defaultBlockState();
    // 0 = not set (a hand-placed one, or an old save): the server default applies.
    private int cap;

    public FlowerMassBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    BlockState replacedState() {
        return replacedState;
    }

    void setReplacedState(BlockState state) {
        this.replacedState = state;
        setChanged();
    }

    int cap() {
        return cap;
    }

    void inheritFlowerBlock(int garden, int cap, long depth) {
        inherit(garden, depth);
        this.cap = cap;
    }

    // It has stopped spreading for good: nothing left to remember but where it came from and what it replaced.
    void settled() {
        forgetDepth();
        if (cap != 0) {
            cap = 0;
            setChanged();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (cap != 0) {
            tag.putInt("Cap", cap);
        }
        tag.put("ReplacedState", NbtUtils.writeBlockState(replacedState));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        cap = tag.getInt("Cap");
        replacedState = tag.contains("ReplacedState")
                ? NbtUtils.readBlockState(registries.lookupOrThrow(Registries.BLOCK), tag.getCompound("ReplacedState"))
                : Blocks.AIR.defaultBlockState();
    }
}
