package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

// Reads the Garden Bag's "cauldron" inventory into actual spread parameters. There are no dedicated
// slots (player request: throw everything in together, like brewing a potion, instead of a form with one
// slot per setting) - every stack anywhere in the bag is either one of the fixed MODIFIER items below
// (identified by item, counted across however many stacks/slots it ends up split into) or a species item
// (see FlowerDisease.bagOutcomeItems()); GardenBagMenu's slot filter keeps out anything else. Shared by
// GardenBagItem (server, at planting time) and GardenBagScreen (client, for the live preview panel), so
// both always agree on what a given pile of items means.
final class GardenBagContents {
    static final Item GENERATIONS_ITEM = Items.BONE_MEAL;
    static final Item INFINITE_GENERATIONS_ITEM = Items.NETHER_STAR;
    static final Item SPEED_ITEM = Items.SCULK;
    static final Item DENSITY_ITEM = Items.SLIME_BALL;
    static final Item RANGE_ITEM = Items.FEATHER;
    // Respecting other species is the default now (player request); this is the opt-OUT item, not an
    // opt-in - its presence means "ignore other flowers", its absence means the (new) default of respecting
    // them. A Fence doesn't read as "ignore everyone" so this needed a different item - a witchy/corrupting
    // ingredient fits the cauldron theme and the "this one doesn't play nice" meaning.
    static final Item IGNORE_OTHERS_ITEM = Items.FERMENTED_SPIDER_EYE;

    final long generations;
    final double spreadChance;
    final int spreadDistance;
    final int densityPer16x16;
    final boolean respectAllSpecies;
    final List<String> speciesWeights;

    private GardenBagContents(
            long generations,
            double spreadChance,
            int spreadDistance,
            int densityPer16x16,
            boolean respectAllSpecies,
            List<String> speciesWeights
    ) {
        this.generations = generations;
        this.spreadChance = spreadChance;
        this.spreadDistance = spreadDistance;
        this.densityPer16x16 = densityPer16x16;
        this.respectAllSpecies = respectAllSpecies;
        this.speciesWeights = speciesWeights;
    }

    static GardenBagContents read(List<ItemStack> slots) {
        long boneMeal = countOf(slots, GENERATIONS_ITEM);
        long netherStar = countOf(slots, INFINITE_GENERATIONS_ITEM);
        long sculk = countOf(slots, SPEED_ITEM);
        long slimeBall = countOf(slots, DENSITY_ITEM);
        long feather = countOf(slots, RANGE_ITEM);
        long fermentedSpiderEye = countOf(slots, IGNORE_OTHERS_ITEM);

        long generations = netherStar > 0
                ? SpreadProfileBlockEntity.INFINITE_GENERATIONS
                : (boneMeal == 0 ? SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE : boneMeal);
        double spreadChance = sculk == 0 ? -1 : Mth.clamp(sculk / 64.0, 0.0, 1.0);
        int spreadDistance = feather == 0 ? -1 : (int) Math.min(feather, 32);
        int densityPer16x16 = slimeBall == 0 ? -1 : (int) slimeBall;
        boolean respectAllSpecies = fermentedSpiderEye == 0;

        return new GardenBagContents(generations, spreadChance, spreadDistance, densityPer16x16, respectAllSpecies, speciesWeights(slots));
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
        return result;
    }
}
