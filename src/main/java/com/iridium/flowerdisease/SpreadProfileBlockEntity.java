package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// Optional per-planting overrides for a Diseased Flower's spread behavior - this is what the Garden Bag
// configures. A hand-placed flower's block entity stays entirely "no override" (generationsRemaining
// excepted, see below) and behaves exactly like before, reading everything else from Config.java. Every
// diseased flower block gets one of these regardless of how it was planted: it's small and pure data (no
// ticker, nothing runs on it directly), so this avoids needing two versions of every block/resource file
// just to support the bag.
//
// generationsRemaining is the one field that's ALWAYS meaningful, bag or not: it used to live in the
// blockstate (an IntegerProperty), but that didn't scale once other per-plant properties were added (see
// PLANNING_STAGE2.md), so it moved here. DiseasedPlantLogic reads Config.FLOWER_MAX_GENERATIONS fresh
// whenever this is still NO_GENERATIONS_OVERRIDE (nothing has decremented it yet), and writes the
// decremented value onto every spreading child's own BlockEntity, bag or no bag - see
// DiseasedPlantLogic#placeChild.
public class SpreadProfileBlockEntity extends BlockEntity {
    static final long NO_GENERATIONS_OVERRIDE = -2;
    static final long INFINITE_GENERATIONS = -1;
    private static final double NO_CHANCE_OVERRIDE = -1;
    private static final int NO_INT_OVERRIDE = -1;

    private long generationsRemaining = NO_GENERATIONS_OVERRIDE;
    private double spreadChanceOverride = NO_CHANCE_OVERRIDE;
    private int spreadDistanceOverride = NO_INT_OVERRIDE;
    private int densityTargetPer16x16 = NO_INT_OVERRIDE;
    private List<String> speciesWeights = List.of();
    // "Territorial": when true, density counting treats ANY nearby plant (any species, ours or vanilla)
    // as crowding, instead of only this species' own family - keeps different bag-plantings from
    // spreading into each other's gaps. ON by default (player request - see GardenBagContents#
    // IGNORE_OTHERS_ITEM, the opt-OUT item); a hand-planted flower with no bag gets this default too,
    // since it's the same shared field/default for every Diseased plant.
    private boolean respectAllSpecies = true;
    // Stage 2 (see PLANNING_STAGE2.md): whether spreading may target the top/side of a non-plantable
    // "climbable" block (logs, leaves, moss...) in addition to ordinary ground. Off by default - a
    // hand-planted flower or a bag without Twisting Vines never leaves ordinary ground.
    private boolean climbing = false;
    // Stage 2: whether a reproductive plant has a (very low, config-controlled) chance per random tick of
    // converting the block it's rooted in into a flower block. Off by default.
    private boolean spawnsFlowerBlocks = false;

    public SpreadProfileBlockEntity(BlockPos pos, BlockState state) {
        super(FlowerDisease.SPREAD_PROFILE_BLOCK_ENTITY.get(), pos, state);
    }

    long generationsRemaining() {
        return generationsRemaining;
    }

    // Used when a spreading child is born with no active bag profile to copy from (a hand-planted
    // lineage) - only the generation countdown needs to persist onto the child's own BlockEntity now that
    // it's not tracked in the blockstate anymore; everything else correctly stays at "no override".
    void setGenerationsRemaining(long value) {
        this.generationsRemaining = value;
        setChanged();
    }

    double spreadChanceOverride() {
        return spreadChanceOverride;
    }

    int spreadDistanceOverride() {
        return spreadDistanceOverride;
    }

    int densityTargetPer16x16() {
        return densityTargetPer16x16;
    }

    List<String> speciesWeights() {
        return speciesWeights;
    }

    boolean respectAllSpecies() {
        return respectAllSpecies;
    }

    boolean climbing() {
        return climbing;
    }

    boolean spawnsFlowerBlocks() {
        return spawnsFlowerBlocks;
    }

    // Used by the debug command and by GardenBagItem when planting the root flower. Also how a spreading
    // child inherits its parent's profile: DiseasedPlantLogic calls configure(parent.toContents(childGen))
    // rather than a separate "copy" method, so there's exactly one place that lists every field instead of
    // two mutators that both need updating in lockstep whenever a knob is added.
    void configure(GardenBagContents contents) {
        this.generationsRemaining = contents.generations();
        this.spreadChanceOverride = contents.spreadChance();
        this.spreadDistanceOverride = contents.spreadDistance();
        this.densityTargetPer16x16 = contents.densityPer16x16();
        this.speciesWeights = List.copyOf(contents.speciesWeights());
        this.respectAllSpecies = contents.respectAllSpecies();
        this.climbing = contents.climbing();
        this.spawnsFlowerBlocks = contents.spawnsFlowerBlocks();
        setChanged();
    }

    // A snapshot of this profile's current fields, with the generation countdown swapped for the child's
    // already-decremented value - the source a spreading child's own configure(...) copies from.
    GardenBagContents toContents(long generationsOverride) {
        return new GardenBagContents(
                generationsOverride,
                spreadChanceOverride,
                spreadDistanceOverride,
                densityTargetPer16x16,
                respectAllSpecies,
                climbing,
                spawnsFlowerBlocks,
                speciesWeights
        );
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("GenerationsRemaining", generationsRemaining);
        tag.putDouble("SpreadChanceOverride", spreadChanceOverride);
        tag.putInt("SpreadDistanceOverride", spreadDistanceOverride);
        tag.putInt("DensityTargetPer16x16", densityTargetPer16x16);
        tag.putBoolean("RespectAllSpecies", respectAllSpecies);
        tag.putBoolean("Climbing", climbing);
        tag.putBoolean("SpawnsFlowerBlocks", spawnsFlowerBlocks);
        ListTag list = new ListTag();
        for (String entry : speciesWeights) {
            list.add(StringTag.valueOf(entry));
        }
        tag.put("SpeciesWeights", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        generationsRemaining = tag.contains("GenerationsRemaining") ? tag.getLong("GenerationsRemaining") : NO_GENERATIONS_OVERRIDE;
        spreadChanceOverride = tag.contains("SpreadChanceOverride") ? tag.getDouble("SpreadChanceOverride") : NO_CHANCE_OVERRIDE;
        spreadDistanceOverride = tag.contains("SpreadDistanceOverride") ? tag.getInt("SpreadDistanceOverride") : NO_INT_OVERRIDE;
        densityTargetPer16x16 = tag.contains("DensityTargetPer16x16") ? tag.getInt("DensityTargetPer16x16") : NO_INT_OVERRIDE;
        respectAllSpecies = tag.getBoolean("RespectAllSpecies");
        climbing = tag.getBoolean("Climbing");
        spawnsFlowerBlocks = tag.getBoolean("SpawnsFlowerBlocks");
        if (tag.contains("SpeciesWeights", Tag.TAG_LIST)) {
            ListTag list = tag.getList("SpeciesWeights", Tag.TAG_STRING);
            List<String> values = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                values.add(list.getString(i));
            }
            speciesWeights = values;
        } else {
            speciesWeights = List.of();
        }
    }
}
