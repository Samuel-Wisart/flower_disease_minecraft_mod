package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

// Reads the Garden Bag's "cauldron" inventory into actual spread parameters, and doubles as the shape of a
// garden's whole configurable profile - the GardenRegistry stores one of these per planting, so there's exactly one
// place that lists every knob. There are no dedicated bag slots (throw everything in together, like brewing a
// potion): every stack anywhere in the bag is either one of the fixed MODIFIER items below (identified by item,
// counted across however many stacks/slots it ends up split into) or a species item (see
// FlowerDisease.bagOutcomeItems()); GardenBagMenu's slot filter keeps out anything else. Shared by GardenBagItem
// (server, at planting time) and GardenBagScreen (client, for the live preview panel), so both always agree on what
// a given pile of items means.
//
// Immutable on purpose: every plant of a garden shares the one instance, and per-plant state (how deep in the
// lineage it is) lives in the block entity, never in here.
record GardenBagContents(
        long generations,
        double spreadChance,
        int spreadDistance,
        int densityPer16x16,
        boolean respectAllSpecies,
        boolean climbing,
        int mossBlocks,
        int decayStrength,
        boolean noDecay,
        int lifetimeAttempts,
        List<String> speciesWeights
) {
    // Bone Meal: how many generations a planting may have, counting the planted flower as the first (so x1 is a
    // single flower). No Bone Meal means "whatever the server default is", which is unlimited.
    static final Item GENERATIONS_ITEM = Items.BONE_MEAL;
    // Sculk: how fast the reproduction chance decays with each generation (more = decays faster).
    static final Item DECAY_ITEM = Items.SCULK;
    // Nether Star: no decay at all - the chance stays at full strength forever. A hyperbolic decay can't be
    // switched off by a count of anything, so this item is what does it.
    static final Item NO_DECAY_ITEM = Items.NETHER_STAR;
    static final Item DENSITY_ITEM = Items.SLIME_BALL;
    // Feather: the MAXIMUM spread distance. Without one it's computed from the density.
    static final Item RANGE_ITEM = Items.FEATHER;
    // Respecting other species is the default; this is the opt-OUT item, not an opt-in - its presence means
    // "ignore other flowers", its absence means the default of respecting them.
    static final Item IGNORE_OTHERS_ITEM = Items.FERMENTED_SPIDER_EYE;
    // Twisting Vines specifically (not plain Vine) - a Nether plant that grows straight UP, matching the
    // "climb" meaning better than a vine that just clings to whatever it's already touching.
    static final Item CLIMBING_ITEM = Items.TWISTING_VINES;
    // Moss Block: the COUNT sets the chance that a plant corrupts the block it grows on into a flower block
    // when it settles (see SpreadMath#flowerBlockChance).
    static final Item FLOWER_BLOCK_ITEM = Items.MOSS_BLOCK;
    // Rabbit's Foot: the average number of times a plant tries to reproduce before it settles.
    static final Item LIFETIME_ITEM = Items.RABBIT_FOOT;

    // What a hand-placed plant, or one whose bag said nothing, behaves like: every knob at "use the default".
    static final GardenBagContents DEFAULT = new GardenBagContents(
            SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE, -1, -1, -1, true, false, 0, 0, false, 0, List.of()
    );

    static GardenBagContents read(List<ItemStack> slots) {
        long boneMeal = countOf(slots, GENERATIONS_ITEM);
        long sculk = countOf(slots, DECAY_ITEM);
        long netherStar = countOf(slots, NO_DECAY_ITEM);
        long slimeBall = countOf(slots, DENSITY_ITEM);
        long feather = countOf(slots, RANGE_ITEM);
        long fermentedSpiderEye = countOf(slots, IGNORE_OTHERS_ITEM);
        long twistingVines = countOf(slots, CLIMBING_ITEM);
        long mossBlock = countOf(slots, FLOWER_BLOCK_ITEM);
        long rabbitFoot = countOf(slots, LIFETIME_ITEM);

        return new GardenBagContents(
                boneMeal == 0 ? SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE : boneMeal,
                -1,
                feather == 0 ? -1 : (int) Math.min(feather, SpreadMath.MAX_MANUAL_DISTANCE),
                slimeBall == 0 ? -1 : (int) Math.min(slimeBall, 1000),
                fermentedSpiderEye == 0,
                twistingVines > 0,
                (int) Math.min(mossBlock, SpreadMath.MAX_MOSS_BLOCKS),
                (int) Math.min(sculk, 1000),
                netherStar > 0,
                (int) Math.min(rabbitFoot, 1000),
                speciesWeights(slots)
        );
    }

    // Only fields that differ from DEFAULT are written, so an unconfigured plant costs almost nothing on disk.
    void writeTo(CompoundTag tag) {
        if (generations != SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE) {
            tag.putLong("Generations", generations);
        }
        if (spreadChance >= 0) {
            tag.putDouble("SpreadChance", spreadChance);
        }
        if (spreadDistance >= 0) {
            tag.putInt("SpreadDistance", spreadDistance);
        }
        if (densityPer16x16 >= 0) {
            tag.putInt("Density", densityPer16x16);
        }
        if (!respectAllSpecies) {
            tag.putBoolean("IgnoreOthers", true);
        }
        if (climbing) {
            tag.putBoolean("Climbing", true);
        }
        if (mossBlocks > 0) {
            tag.putInt("MossBlocks", mossBlocks);
        }
        if (decayStrength > 0) {
            tag.putInt("DecayStrength", decayStrength);
        }
        if (noDecay) {
            tag.putBoolean("NoDecay", true);
        }
        if (lifetimeAttempts > 0) {
            tag.putInt("Lifetime", lifetimeAttempts);
        }
        if (!speciesWeights.isEmpty()) {
            ListTag list = new ListTag();
            for (String entry : speciesWeights) {
                list.add(StringTag.valueOf(entry));
            }
            tag.put("Species", list);
        }
    }

    // Also understands the pre-lifecycle-rework key names, so worlds saved before it still load sensibly. The old
    // "generations remaining" counted children only, so as a cap that counts the plant itself it is one higher.
    static GardenBagContents readFrom(CompoundTag tag) {
        long generations = tag.contains("Generations") ? tag.getLong("Generations")
                : tag.contains("GenerationsRemaining") ? legacyCap(tag.getLong("GenerationsRemaining"))
                : SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE;
        double spreadChance = tag.contains("SpreadChance") ? tag.getDouble("SpreadChance")
                : tag.contains("SpreadChanceOverride") ? tag.getDouble("SpreadChanceOverride")
                : -1;
        int spreadDistance = tag.contains("SpreadDistance") ? tag.getInt("SpreadDistance")
                : tag.contains("SpreadDistanceOverride") ? tag.getInt("SpreadDistanceOverride")
                : -1;
        int density = tag.contains("Density") ? tag.getInt("Density")
                : tag.contains("DensityTargetPer16x16") ? tag.getInt("DensityTargetPer16x16")
                : -1;
        boolean respectAll = tag.contains("IgnoreOthers") ? !tag.getBoolean("IgnoreOthers")
                : !tag.contains("RespectAllSpecies") || tag.getBoolean("RespectAllSpecies");
        int moss = tag.contains("MossBlocks") ? tag.getInt("MossBlocks")
                : tag.getBoolean("SpawnsFlowerBlocks") ? 1 : 0;

        List<String> species = List.of();
        String speciesKey = tag.contains("Species", Tag.TAG_LIST) ? "Species" : "SpeciesWeights";
        if (tag.contains(speciesKey, Tag.TAG_LIST)) {
            ListTag list = tag.getList(speciesKey, Tag.TAG_STRING);
            List<String> values = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                values.add(list.getString(i));
            }
            species = List.copyOf(values);
        }

        return new GardenBagContents(
                generations, spreadChance, spreadDistance, density, respectAll, tag.getBoolean("Climbing"), moss,
                tag.getInt("DecayStrength"), tag.getBoolean("NoDecay"), tag.getInt("Lifetime"), species
        );
    }

    private static long legacyCap(long remaining) {
        return remaining < 0 ? remaining : remaining + 1;
    }

    private static long countOf(List<ItemStack> slots, Item item) {
        long total = 0;
        for (ItemStack stack : slots) {
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    // Every species item contributes to the SAME outcome's weight, however many stacks/slots it's split
    // into - "60 Poppy in one slot" and "40 + 20 Poppy in two different slots" mean the same thing.
    private static List<String> speciesWeights(List<ItemStack> slots) {
        Map<Block, Long> weightByBlock = new LinkedHashMap<>();
        for (ItemStack stack : slots) {
            Block outcome = FlowerDisease.bagOutcomeItems().get(stack.getItem());
            if (outcome == null) {
                continue;
            }
            weightByBlock.merge(outcome, (long) stack.getCount(), Long::sum);
        }

        List<String> result = new ArrayList<>();
        for (Map.Entry<Block, Long> entry : weightByBlock.entrySet()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(entry.getKey());
            result.add(id + " " + entry.getValue());
        }
        return List.copyOf(result);
    }
}
