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
// configures. A hand-placed flower's block entity stays entirely "no override" and behaves exactly like
// before (reading everything from Config.java / the blockstate GENERATION property). Every diseased
// flower block gets one of these regardless of how it was planted: it's small and pure data (no ticker,
// nothing runs on it directly), so this avoids needing two versions of every block/resource file just to
// support the bag.
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
    // spreading into each other's gaps. Off by default (matches the old, species-only behavior).
    private boolean respectAllSpecies = false;

    public SpreadProfileBlockEntity(BlockPos pos, BlockState state) {
        super(FlowerDisease.SPREAD_PROFILE_BLOCK_ENTITY.get(), pos, state);
    }

    boolean hasOverride() {
        return generationsRemaining != NO_GENERATIONS_OVERRIDE
                || spreadChanceOverride != NO_CHANCE_OVERRIDE
                || spreadDistanceOverride != NO_INT_OVERRIDE
                || densityTargetPer16x16 != NO_INT_OVERRIDE
                || !speciesWeights.isEmpty()
                || respectAllSpecies;
    }

    long generationsRemaining() {
        return generationsRemaining;
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

    // Used by the debug command and by GardenBagItem when planting the root flower.
    void configure(long generationsRemaining, double spreadChanceOverride, int spreadDistanceOverride, int densityTargetPer16x16, List<String> speciesWeights, boolean respectAllSpecies) {
        this.generationsRemaining = generationsRemaining;
        this.spreadChanceOverride = spreadChanceOverride;
        this.spreadDistanceOverride = spreadDistanceOverride;
        this.densityTargetPer16x16 = densityTargetPer16x16;
        this.speciesWeights = List.copyOf(speciesWeights);
        this.respectAllSpecies = respectAllSpecies;
        setChanged();
    }

    // Copies a parent's profile onto this (freshly placed child) block entity, with the generation
    // count already advanced to the child's value.
    void copyFrom(SpreadProfileBlockEntity parent, long childGenerationsRemaining) {
        this.generationsRemaining = childGenerationsRemaining;
        this.spreadChanceOverride = parent.spreadChanceOverride;
        this.spreadDistanceOverride = parent.spreadDistanceOverride;
        this.densityTargetPer16x16 = parent.densityTargetPer16x16;
        this.speciesWeights = parent.speciesWeights;
        this.respectAllSpecies = parent.respectAllSpecies;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putLong("GenerationsRemaining", generationsRemaining);
        tag.putDouble("SpreadChanceOverride", spreadChanceOverride);
        tag.putInt("SpreadDistanceOverride", spreadDistanceOverride);
        tag.putInt("DensityTargetPer16x16", densityTargetPer16x16);
        tag.putBoolean("RespectAllSpecies", respectAllSpecies);
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
