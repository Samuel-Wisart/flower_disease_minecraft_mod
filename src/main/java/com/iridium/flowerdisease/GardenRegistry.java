package com.iridium.flowerdisease;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

// Every planting the Garden Bag has made in a dimension, and the profile it was made with. A plant's block entity
// stores only the id of its garden (plus its depth) instead of a copy of the whole profile - the species list alone
// would otherwise be written out again for every single flower of a big garden, and read back into a separate copy of
// its own on every chunk load. It is also what makes a garden manageable as one thing: replacing the profile here
// changes how every plant of that garden behaves from its next tick on, which is exactly the hook a future "special"
// Bone Meal (or any garden-wide command) needs.
//
// One garden per bag use, even when two plantings have identical contents. The exception is the plants of worlds
// saved before this existed, which carry their profile inside the block entity and cannot be traced back to a bag use:
// those are folded in on first use with identical profiles sharing one "legacy" garden (see #intern).
//
// Entries are never removed: a garden costs a couple of hundred bytes, and knowing whether one still has plants
// would mean scanning the world. Server thread only.
final class GardenRegistry extends SavedData {
    // A plant that belongs to no garden - hand-placed, or an old save nothing has folded in yet - uses the server
    // defaults for everything.
    static final int NO_GARDEN = 0;

    private static final String DATA_NAME = FlowerDisease.MODID + "_gardens";
    private static final SavedData.Factory<GardenRegistry> FACTORY = new SavedData.Factory<>(GardenRegistry::new, GardenRegistry::load);

    // What is known about one planting. `legacy` marks a profile folded in from an old save, which has no real origin.
    record Garden(GardenBagContents profile, BlockPos origin, long plantedAt, boolean legacy) {
    }

    private final Int2ObjectMap<Garden> gardens = new Int2ObjectOpenHashMap<>();
    // Identical legacy profiles share a garden, so migrating a big old garden doesn't create thousands of them.
    private final Object2IntMap<GardenBagContents> legacyIds = new Object2IntOpenHashMap<>();
    private int nextId = 1;
    // Bumped whenever a profile is replaced, so plants that cached theirs know to read it again. Not saved.
    private int version;

    static GardenRegistry of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // A new garden for one planting.
    int create(GardenBagContents profile, BlockPos origin, long plantedAt) {
        int id = nextId++;
        gardens.put(id, new Garden(profile, origin.immutable(), plantedAt, false));
        setDirty();
        return id;
    }

    // The garden for an old save's embedded profile: the same profile always gets the same garden.
    int intern(GardenBagContents profile) {
        int existing = legacyIds.getOrDefault(profile, NO_GARDEN);
        if (existing != NO_GARDEN) {
            return existing;
        }

        int id = nextId++;
        gardens.put(id, new Garden(profile, BlockPos.ZERO, 0, true));
        legacyIds.put(profile, id);
        setDirty();
        return id;
    }

    @Nullable
    Garden get(int id) {
        return gardens.get(id);
    }

    GardenBagContents profileOf(int id) {
        Garden garden = gardens.get(id);
        return garden != null ? garden.profile() : GardenBagContents.DEFAULT;
    }

    // Changes how every plant of the garden behaves, from its next tick on.
    void replaceProfile(int id, GardenBagContents profile) {
        Garden garden = gardens.get(id);
        if (garden == null) {
            return;
        }

        if (garden.legacy()) {
            legacyIds.removeInt(garden.profile());
        }
        gardens.put(id, new Garden(profile, garden.origin(), garden.plantedAt(), garden.legacy()));
        version++;
        setDirty();
    }

    int version() {
        return version;
    }

    int size() {
        return gardens.size();
    }

    // ---- Saving --------------------------------------------------------------------------------------

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Int2ObjectMap.Entry<Garden> entry : gardens.int2ObjectEntrySet()) {
            Garden garden = entry.getValue();
            CompoundTag saved = new CompoundTag();
            saved.putInt("Id", entry.getIntKey());
            CompoundTag profile = new CompoundTag();
            garden.profile().writeTo(profile);
            saved.put("Profile", profile);
            if (garden.legacy()) {
                saved.putBoolean("Legacy", true);
            } else {
                saved.putLong("Origin", garden.origin().asLong());
                saved.putLong("PlantedAt", garden.plantedAt());
            }
            list.add(saved);
        }
        tag.put("Gardens", list);
        tag.putInt("Next", nextId);
        return tag;
    }

    static GardenRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        GardenRegistry registry = new GardenRegistry();
        ListTag list = tag.getList("Gardens", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag saved = list.getCompound(i);
            int id = saved.getInt("Id");
            GardenBagContents profile = GardenBagContents.readFrom(saved.getCompound("Profile"));
            boolean legacy = saved.getBoolean("Legacy");
            registry.gardens.put(id, new Garden(profile, legacy ? BlockPos.ZERO : BlockPos.of(saved.getLong("Origin")), saved.getLong("PlantedAt"), legacy));
            if (legacy) {
                registry.legacyIds.put(profile, id);
            }
            registry.nextId = Math.max(registry.nextId, id + 1);
        }
        registry.nextId = Math.max(registry.nextId, tag.getInt("Next"));
        return registry;
    }
}
