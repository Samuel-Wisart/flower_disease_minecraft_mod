package com.iridium.flowerdisease;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(FlowerDisease.MODID)
public class FlowerDisease {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "flowerdisease";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    // Create a Deferred Register to hold Blocks which will all be registered under the "flowerdisease" namespace
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
    // Create a Deferred Register to hold Items which will all be registered under the "flowerdisease" namespace
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    // Create a Deferred Register to hold Block Entity Types which will all be registered under the "flowerdisease" namespace
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, MODID);
    // Create a Deferred Register to hold Data Component Types which will all be registered under the "flowerdisease" namespace
    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENT_TYPES = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MODID);
    // Create a Deferred Register to hold Menu Types which will all be registered under the "flowerdisease" namespace
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, MODID);

    // Each Diseased Flower shares the DiseasedFlowerBlock behavior; the suspicious stew effect matches the
    // vanilla flower it's based on. What it settles into is entirely driven by the Garden Bag now (see
    // SpreadProfileBlockEntity/SettleTable) - a hand-placed one with no bag profile just settles into
    // fallbackBlock, its own plain vanilla self.
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_DANDELION =
            registerDiseased("diseased_dandelion", MobEffects.SATURATION, 0.35F, Blocks.DANDELION);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_POPPY =
            registerDiseased("diseased_poppy", MobEffects.NIGHT_VISION, 5.0F, Blocks.POPPY);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_BLUE_ORCHID =
            registerDiseased("diseased_blue_orchid", MobEffects.SATURATION, 0.35F, Blocks.BLUE_ORCHID);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_ALLIUM =
            registerDiseased("diseased_allium", MobEffects.FIRE_RESISTANCE, 4.0F, Blocks.ALLIUM);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_AZURE_BLUET =
            registerDiseased("diseased_azure_bluet", MobEffects.BLINDNESS, 8.0F, Blocks.AZURE_BLUET);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_RED_TULIP =
            registerDiseased("diseased_red_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.RED_TULIP);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_ORANGE_TULIP =
            registerDiseased("diseased_orange_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.ORANGE_TULIP);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_WHITE_TULIP =
            registerDiseased("diseased_white_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.WHITE_TULIP);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_PINK_TULIP =
            registerDiseased("diseased_pink_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.PINK_TULIP);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_OXEYE_DAISY =
            registerDiseased("diseased_oxeye_daisy", MobEffects.REGENERATION, 8.0F, Blocks.OXEYE_DAISY);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_CORNFLOWER =
            registerDiseased("diseased_cornflower", MobEffects.JUMP, 6.0F, Blocks.CORNFLOWER);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_LILY_OF_THE_VALLEY =
            registerDiseased("diseased_lily_of_the_valley", MobEffects.POISON, 12.0F, Blocks.LILY_OF_THE_VALLEY);

    // Same spread/settle behavior, but based on WitherRoseBlock so it keeps the wither-damage-on-touch
    // and the "also grows on netherrack/soul sand/soul soil" ground rules of a real Wither Rose.
    public static final DeferredBlock<DiseasedWitherRoseBlock> DISEASED_WITHER_ROSE = BLOCKS.registerBlock(
            "diseased_wither_rose",
            properties -> new DiseasedWitherRoseBlock(MobEffects.WITHER, 8.0F, Blocks.WITHER_ROSE, properties),
            flowerProperties()
    );

    // Two-block flowers: no suspicious stew effect (vanilla doesn't give these one either).
    public static final DeferredBlock<DiseasedTallFlowerBlock> DISEASED_SUNFLOWER = registerDiseasedTall("diseased_sunflower", Blocks.SUNFLOWER);
    public static final DeferredBlock<DiseasedTallFlowerBlock> DISEASED_LILAC = registerDiseasedTall("diseased_lilac", Blocks.LILAC);
    public static final DeferredBlock<DiseasedTallFlowerBlock> DISEASED_ROSE_BUSH = registerDiseasedTall("diseased_rose_bush", Blocks.ROSE_BUSH);
    public static final DeferredBlock<DiseasedTallFlowerBlock> DISEASED_PEONY = registerDiseasedTall("diseased_peony", Blocks.PEONY);

    // Creeping variant of the same 4 tall flowers (Stage 2 Fase 2, see PLANNING_STAGE2.md and
    // CreepingFlowerBlock) - spreads across any solid face like Glow Lichen/Sculk Vein instead of standing
    // in the ground. No "vanilla" counterpart at all (diseasedByFallback() maps each to itself below), and
    // - unlike every other species here - the item form IS meant to be obtainable: it's the only way to
    // pick this species in the Garden Bag, there being no plain vanilla item to reuse for it (see
    // bagOutcomeItems() and addCreative()).
    public static final DeferredBlock<CreepingFlowerBlock> SUNFLOWER_CREEPER = registerCreeping("sunflower_creeper");
    public static final DeferredBlock<CreepingFlowerBlock> LILAC_CREEPER = registerCreeping("lilac_creeper");
    public static final DeferredBlock<CreepingFlowerBlock> ROSE_BUSH_CREEPER = registerCreeping("rose_bush_creeper");
    public static final DeferredBlock<CreepingFlowerBlock> PEONY_CREEPER = registerCreeping("peony_creeper");

    // The Flower Block (Stage 2 Fase 3, see PLANNING_STAGE2.md and FlowerBlockLogic) - a reproductive
    // Diseased Flower with the bag's Moss Block modifier has a small chance of corrupting the ground below
    // it into one of these, which then slowly corrupts its own neighbors on its own. Looks/acts like Moss
    // Block for now (placeholder texture), including the properties below, mirroring Blocks.MOSS_BLOCK
    // plus .randomTicks() (vanilla Moss Block never random-ticks; ours has to, to spread on its own).
    public static final DeferredBlock<FlowerMassBlock> FLOWER_BLOCK = BLOCKS.registerBlock(
            "flower_block",
            FlowerMassBlock::new,
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_GREEN)
                    .strength(0.1F)
                    .sound(SoundType.MOSS)
                    .pushReaction(PushReaction.DESTROY)
                    .randomTicks()
    );

    // Short Grass and Fern both use vanilla's plain TallGrassBlock class, so one block class
    // (DiseasedGrassBlock) covers both - only the fallback differs per registration.
    public static final DeferredBlock<DiseasedGrassBlock> DISEASED_SHORT_GRASS = BLOCKS.registerBlock(
            "diseased_short_grass",
            properties -> new DiseasedGrassBlock(Blocks.SHORT_GRASS, properties),
            grassProperties()
    );
    public static final DeferredBlock<DiseasedGrassBlock> DISEASED_FERN = BLOCKS.registerBlock(
            "diseased_fern",
            properties -> new DiseasedGrassBlock(Blocks.FERN, properties),
            grassProperties()
    );
    public static final DeferredBlock<DiseasedDeadBushBlock> DISEASED_DEAD_BUSH = BLOCKS.registerBlock(
            "diseased_dead_bush",
            properties -> new DiseasedDeadBushBlock(Blocks.DEAD_BUSH, properties),
            deadBushProperties()
    );
    // Tall Grass and Large Fern both use vanilla's plain DoublePlantBlock class directly (no bonemeal
    // behavior, unlike the TallFlowerBlock-based species above).
    public static final DeferredBlock<DiseasedTallGrassBlock> DISEASED_TALL_GRASS = BLOCKS.registerBlock(
            "diseased_tall_grass",
            properties -> new DiseasedTallGrassBlock(Blocks.TALL_GRASS, properties),
            tallGrassProperties()
    );
    public static final DeferredBlock<DiseasedTallGrassBlock> DISEASED_LARGE_FERN = BLOCKS.registerBlock(
            "diseased_large_fern",
            properties -> new DiseasedTallGrassBlock(Blocks.LARGE_FERN, properties),
            tallGrassProperties()
    );

    // Standalone "just the top half"/"just the bottom half" decorative single-block plants. They're
    // their own separate Garden Bag species grid choices, each with its own weight, distinct from the
    // full two-block "Full" species item - and each has its own spreading DiseasedDecorativeFlowerBlock
    // counterpart below, exactly like any other species (see DISEASED_BY_FALLBACK).
    public static final DeferredBlock<DecorativeFlowerBlock> SUNFLOWER_TOP =
            BLOCKS.registerBlock("sunflower_top", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> SUNFLOWER_BOTTOM =
            BLOCKS.registerBlock("sunflower_bottom", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> LILAC_TOP =
            BLOCKS.registerBlock("lilac_top", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> LILAC_BOTTOM =
            BLOCKS.registerBlock("lilac_bottom", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> ROSE_BUSH_TOP =
            BLOCKS.registerBlock("rose_bush_top", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> ROSE_BUSH_BOTTOM =
            BLOCKS.registerBlock("rose_bush_bottom", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> PEONY_TOP =
            BLOCKS.registerBlock("peony_top", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> PEONY_BOTTOM =
            BLOCKS.registerBlock("peony_bottom", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> TALL_GRASS_TOP =
            BLOCKS.registerBlock("tall_grass_top", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> TALL_GRASS_BOTTOM =
            BLOCKS.registerBlock("tall_grass_bottom", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> LARGE_FERN_TOP =
            BLOCKS.registerBlock("large_fern_top", DecorativeFlowerBlock::new, decorativeFlowerProperties());
    public static final DeferredBlock<DecorativeFlowerBlock> LARGE_FERN_BOTTOM =
            BLOCKS.registerBlock("large_fern_bottom", DecorativeFlowerBlock::new, decorativeFlowerProperties());

    // Spreading counterparts of the decorative Top/Bottom blocks above - each is its own independent
    // species (not a variant of the full two-block plant): plantable by the bag, spreads like any other
    // single-block flower, and settles back into its own plain DecorativeFlowerBlock (its "fallback",
    // same pattern as every other species in this mod).
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_SUNFLOWER_TOP =
            registerDiseasedDecorative("diseased_sunflower_top", SUNFLOWER_TOP);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_SUNFLOWER_BOTTOM =
            registerDiseasedDecorative("diseased_sunflower_bottom", SUNFLOWER_BOTTOM);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_LILAC_TOP =
            registerDiseasedDecorative("diseased_lilac_top", LILAC_TOP);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_LILAC_BOTTOM =
            registerDiseasedDecorative("diseased_lilac_bottom", LILAC_BOTTOM);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_ROSE_BUSH_TOP =
            registerDiseasedDecorative("diseased_rose_bush_top", ROSE_BUSH_TOP);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_ROSE_BUSH_BOTTOM =
            registerDiseasedDecorative("diseased_rose_bush_bottom", ROSE_BUSH_BOTTOM);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_PEONY_TOP =
            registerDiseasedDecorative("diseased_peony_top", PEONY_TOP);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_PEONY_BOTTOM =
            registerDiseasedDecorative("diseased_peony_bottom", PEONY_BOTTOM);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_TALL_GRASS_TOP =
            registerDiseasedDecorative("diseased_tall_grass_top", TALL_GRASS_TOP);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_TALL_GRASS_BOTTOM =
            registerDiseasedDecorative("diseased_tall_grass_bottom", TALL_GRASS_BOTTOM);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_LARGE_FERN_TOP =
            registerDiseasedDecorative("diseased_large_fern_top", LARGE_FERN_TOP);
    public static final DeferredBlock<DiseasedDecorativeFlowerBlock> DISEASED_LARGE_FERN_BOTTOM =
            registerDiseasedDecorative("diseased_large_fern_bottom", LARGE_FERN_BOTTOM);

    // Reverse lookup from a "full species" block (as named in a Garden Bag/settle outcome pool) to the
    // Diseased block that actually spreads as that species. Includes both vanilla blocks (the 22 normal
    // species) and our own decorative Top/Bottom blocks (each its own independent species, see above) -
    // anything absent from this map has no Diseased counterpart and can never be picked as a spreading
    // child or a plantable bag root. Built lazily (see bagOutcomeItems() below for why): the Top/Bottom
    // keys need SUNFLOWER_TOP.get() etc, which isn't safe to resolve until registration has actually run.
    private static Map<Block, DeferredBlock<? extends Block>> diseasedByFallback;

    public static Map<Block, DeferredBlock<? extends Block>> diseasedByFallback() {
        if (diseasedByFallback == null) {
            diseasedByFallback = Map.ofEntries(
                    Map.entry(Blocks.DANDELION, DISEASED_DANDELION),
                    Map.entry(Blocks.POPPY, DISEASED_POPPY),
                    Map.entry(Blocks.BLUE_ORCHID, DISEASED_BLUE_ORCHID),
                    Map.entry(Blocks.ALLIUM, DISEASED_ALLIUM),
                    Map.entry(Blocks.AZURE_BLUET, DISEASED_AZURE_BLUET),
                    Map.entry(Blocks.RED_TULIP, DISEASED_RED_TULIP),
                    Map.entry(Blocks.ORANGE_TULIP, DISEASED_ORANGE_TULIP),
                    Map.entry(Blocks.WHITE_TULIP, DISEASED_WHITE_TULIP),
                    Map.entry(Blocks.PINK_TULIP, DISEASED_PINK_TULIP),
                    Map.entry(Blocks.OXEYE_DAISY, DISEASED_OXEYE_DAISY),
                    Map.entry(Blocks.CORNFLOWER, DISEASED_CORNFLOWER),
                    Map.entry(Blocks.LILY_OF_THE_VALLEY, DISEASED_LILY_OF_THE_VALLEY),
                    Map.entry(Blocks.WITHER_ROSE, DISEASED_WITHER_ROSE),
                    Map.entry(Blocks.SUNFLOWER, DISEASED_SUNFLOWER),
                    Map.entry(Blocks.LILAC, DISEASED_LILAC),
                    Map.entry(Blocks.ROSE_BUSH, DISEASED_ROSE_BUSH),
                    Map.entry(Blocks.PEONY, DISEASED_PEONY),
                    Map.entry(Blocks.SHORT_GRASS, DISEASED_SHORT_GRASS),
                    Map.entry(Blocks.FERN, DISEASED_FERN),
                    Map.entry(Blocks.DEAD_BUSH, DISEASED_DEAD_BUSH),
                    Map.entry(Blocks.TALL_GRASS, DISEASED_TALL_GRASS),
                    Map.entry(Blocks.LARGE_FERN, DISEASED_LARGE_FERN),
                    Map.entry(SUNFLOWER_TOP.get(), DISEASED_SUNFLOWER_TOP),
                    Map.entry(SUNFLOWER_BOTTOM.get(), DISEASED_SUNFLOWER_BOTTOM),
                    Map.entry(LILAC_TOP.get(), DISEASED_LILAC_TOP),
                    Map.entry(LILAC_BOTTOM.get(), DISEASED_LILAC_BOTTOM),
                    Map.entry(ROSE_BUSH_TOP.get(), DISEASED_ROSE_BUSH_TOP),
                    Map.entry(ROSE_BUSH_BOTTOM.get(), DISEASED_ROSE_BUSH_BOTTOM),
                    Map.entry(PEONY_TOP.get(), DISEASED_PEONY_TOP),
                    Map.entry(PEONY_BOTTOM.get(), DISEASED_PEONY_BOTTOM),
                    Map.entry(TALL_GRASS_TOP.get(), DISEASED_TALL_GRASS_TOP),
                    Map.entry(TALL_GRASS_BOTTOM.get(), DISEASED_TALL_GRASS_BOTTOM),
                    Map.entry(LARGE_FERN_TOP.get(), DISEASED_LARGE_FERN_TOP),
                    Map.entry(LARGE_FERN_BOTTOM.get(), DISEASED_LARGE_FERN_BOTTOM),
                    // Creeping species map to themselves - there's no separate vanilla-ish fallback block
                    // for them at all (see the field comment above DISEASED_PEONY/SUNFLOWER_CREEPER), so
                    // this is what makes DiseasedPlantLogic#settle always settle these in place.
                    Map.entry(SUNFLOWER_CREEPER.get(), SUNFLOWER_CREEPER),
                    Map.entry(LILAC_CREEPER.get(), LILAC_CREEPER),
                    Map.entry(ROSE_BUSH_CREEPER.get(), ROSE_BUSH_CREEPER),
                    Map.entry(PEONY_CREEPER.get(), PEONY_CREEPER)
            );
        }
        return diseasedByFallback;
    }

    // The other direction of diseasedByFallback() - what a given Diseased block's own vanilla-ish
    // counterpart is (for the 22 normal species, the real vanilla block; for the 12 Top/Bottom entries,
    // our own plain decorative block, since there's no vanilla equivalent for those). Used by JadeCompat
    // to show that name once a plant has settled, instead of the "Diseased X" name the block itself is
    // never meant to display anywhere (see PLANNING_STAGE2.md - Diseased Flower is a concept, not a thing
    // players collect or see labeled as such).
    private static Map<Block, Block> fallbackByDiseased;

    public static Map<Block, Block> fallbackByDiseased() {
        if (fallbackByDiseased == null) {
            Map<Block, Block> reversed = new HashMap<>();
            for (Map.Entry<Block, DeferredBlock<? extends Block>> entry : diseasedByFallback().entrySet()) {
                reversed.put(entry.getValue().get(), entry.getKey());
            }
            fallbackByDiseased = Map.copyOf(reversed);
        }
        return fallbackByDiseased;
    }

    // Optional per-planting spread overrides (see SpreadProfileBlockEntity) - every spreading Diseased
    // Flower block has one, hand-placed included, but it only ever does anything once something (the
    // debug command for now, the Garden Bag later) actually configures it.
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SpreadProfileBlockEntity>> SPREAD_PROFILE_BLOCK_ENTITY = BLOCK_ENTITY_TYPES.register(
            "spread_profile",
            () -> BlockEntityType.Builder.of(
                    SpreadProfileBlockEntity::new,
                    DISEASED_DANDELION.get(), DISEASED_POPPY.get(), DISEASED_BLUE_ORCHID.get(), DISEASED_ALLIUM.get(),
                    DISEASED_AZURE_BLUET.get(), DISEASED_RED_TULIP.get(), DISEASED_ORANGE_TULIP.get(), DISEASED_WHITE_TULIP.get(),
                    DISEASED_PINK_TULIP.get(), DISEASED_OXEYE_DAISY.get(), DISEASED_CORNFLOWER.get(), DISEASED_LILY_OF_THE_VALLEY.get(),
                    DISEASED_WITHER_ROSE.get(),
                    DISEASED_SUNFLOWER.get(), DISEASED_LILAC.get(), DISEASED_ROSE_BUSH.get(), DISEASED_PEONY.get(),
                    SUNFLOWER_CREEPER.get(), LILAC_CREEPER.get(), ROSE_BUSH_CREEPER.get(), PEONY_CREEPER.get(),
                    FLOWER_BLOCK.get(),
                    DISEASED_SHORT_GRASS.get(), DISEASED_FERN.get(), DISEASED_DEAD_BUSH.get(),
                    DISEASED_TALL_GRASS.get(), DISEASED_LARGE_FERN.get(),
                    DISEASED_SUNFLOWER_TOP.get(), DISEASED_SUNFLOWER_BOTTOM.get(),
                    DISEASED_LILAC_TOP.get(), DISEASED_LILAC_BOTTOM.get(),
                    DISEASED_ROSE_BUSH_TOP.get(), DISEASED_ROSE_BUSH_BOTTOM.get(),
                    DISEASED_PEONY_TOP.get(), DISEASED_PEONY_BOTTOM.get(),
                    DISEASED_TALL_GRASS_TOP.get(), DISEASED_TALL_GRASS_BOTTOM.get(),
                    DISEASED_LARGE_FERN_TOP.get(), DISEASED_LARGE_FERN_BOTTOM.get()
            ).build(null)
    );

    // Garden Bag menu: constructed the same way on both sides from (windowId, playerInventory, hand) - the
    // hand is the only "extra data" the client needs to know which held stack the menu is backed by.
    public static final DeferredHolder<MenuType<?>, MenuType<GardenBagMenu>> GARDEN_BAG_MENU = MENU_TYPES.register(
            "garden_bag",
            () -> IMenuTypeExtension.create((windowId, inventory, buf) -> new GardenBagMenu(windowId, inventory, buf.readEnum(InteractionHand.class)))
    );

    public static final DeferredItem<GardenBagItem> GARDEN_BAG = ITEMS.register(
            "garden_bag",
            () -> new GardenBagItem(new Item.Properties().stacksTo(1))
    );

    public static final DeferredItem<BlockItem> SUNFLOWER_TOP_ITEM = ITEMS.registerSimpleBlockItem("sunflower_top", SUNFLOWER_TOP);
    public static final DeferredItem<BlockItem> SUNFLOWER_BOTTOM_ITEM = ITEMS.registerSimpleBlockItem("sunflower_bottom", SUNFLOWER_BOTTOM);
    public static final DeferredItem<BlockItem> LILAC_TOP_ITEM = ITEMS.registerSimpleBlockItem("lilac_top", LILAC_TOP);
    public static final DeferredItem<BlockItem> LILAC_BOTTOM_ITEM = ITEMS.registerSimpleBlockItem("lilac_bottom", LILAC_BOTTOM);
    public static final DeferredItem<BlockItem> ROSE_BUSH_TOP_ITEM = ITEMS.registerSimpleBlockItem("rose_bush_top", ROSE_BUSH_TOP);
    public static final DeferredItem<BlockItem> ROSE_BUSH_BOTTOM_ITEM = ITEMS.registerSimpleBlockItem("rose_bush_bottom", ROSE_BUSH_BOTTOM);
    public static final DeferredItem<BlockItem> PEONY_TOP_ITEM = ITEMS.registerSimpleBlockItem("peony_top", PEONY_TOP);
    public static final DeferredItem<BlockItem> PEONY_BOTTOM_ITEM = ITEMS.registerSimpleBlockItem("peony_bottom", PEONY_BOTTOM);
    public static final DeferredItem<BlockItem> TALL_GRASS_TOP_ITEM = ITEMS.registerSimpleBlockItem("tall_grass_top", TALL_GRASS_TOP);
    public static final DeferredItem<BlockItem> TALL_GRASS_BOTTOM_ITEM = ITEMS.registerSimpleBlockItem("tall_grass_bottom", TALL_GRASS_BOTTOM);
    public static final DeferredItem<BlockItem> LARGE_FERN_TOP_ITEM = ITEMS.registerSimpleBlockItem("large_fern_top", LARGE_FERN_TOP);
    public static final DeferredItem<BlockItem> LARGE_FERN_BOTTOM_ITEM = ITEMS.registerSimpleBlockItem("large_fern_bottom", LARGE_FERN_BOTTOM);
    public static final DeferredItem<BlockItem> SUNFLOWER_CREEPER_ITEM = ITEMS.registerSimpleBlockItem("sunflower_creeper", SUNFLOWER_CREEPER);
    public static final DeferredItem<BlockItem> LILAC_CREEPER_ITEM = ITEMS.registerSimpleBlockItem("lilac_creeper", LILAC_CREEPER);
    public static final DeferredItem<BlockItem> ROSE_BUSH_CREEPER_ITEM = ITEMS.registerSimpleBlockItem("rose_bush_creeper", ROSE_BUSH_CREEPER);
    public static final DeferredItem<BlockItem> PEONY_CREEPER_ITEM = ITEMS.registerSimpleBlockItem("peony_creeper", PEONY_CREEPER);
    public static final DeferredItem<BlockItem> FLOWER_BLOCK_ITEM = ITEMS.registerSimpleBlockItem("flower_block", FLOWER_BLOCK);

    // What dropping a given item into one of the Garden Bag's species grid slots means - the stack count
    // in that slot becomes that outcome's relative weight (see GardenBagItem). Every item here maps to a
    // "full species" fallback block that has its own Diseased counterpart (via diseasedByFallback()), so
    // every grid choice - including Top/Bottom - is spreadable AND a valid settle target. Built lazily
    // (not a plain static field) because the keys/values here include mod-registered DeferredItem/
    // DeferredBlock instances that aren't safe to resolve via .get() until registration has actually run,
    // which happens well after this class's own static fields are initialized.
    private static Map<Item, Block> bagOutcomeItems;

    public static Map<Item, Block> bagOutcomeItems() {
        if (bagOutcomeItems == null) {
            bagOutcomeItems = Map.ofEntries(
                    Map.entry(Items.DANDELION, Blocks.DANDELION),
                    Map.entry(Items.POPPY, Blocks.POPPY),
                    Map.entry(Items.BLUE_ORCHID, Blocks.BLUE_ORCHID),
                    Map.entry(Items.ALLIUM, Blocks.ALLIUM),
                    Map.entry(Items.AZURE_BLUET, Blocks.AZURE_BLUET),
                    Map.entry(Items.RED_TULIP, Blocks.RED_TULIP),
                    Map.entry(Items.ORANGE_TULIP, Blocks.ORANGE_TULIP),
                    Map.entry(Items.WHITE_TULIP, Blocks.WHITE_TULIP),
                    Map.entry(Items.PINK_TULIP, Blocks.PINK_TULIP),
                    Map.entry(Items.OXEYE_DAISY, Blocks.OXEYE_DAISY),
                    Map.entry(Items.CORNFLOWER, Blocks.CORNFLOWER),
                    Map.entry(Items.LILY_OF_THE_VALLEY, Blocks.LILY_OF_THE_VALLEY),
                    Map.entry(Items.WITHER_ROSE, Blocks.WITHER_ROSE),
                    Map.entry(Items.SHORT_GRASS, Blocks.SHORT_GRASS),
                    Map.entry(Items.FERN, Blocks.FERN),
                    Map.entry(Items.DEAD_BUSH, Blocks.DEAD_BUSH),
                    Map.entry(Items.SUNFLOWER, Blocks.SUNFLOWER),
                    Map.entry(SUNFLOWER_TOP_ITEM.get(), SUNFLOWER_TOP.get()),
                    Map.entry(SUNFLOWER_BOTTOM_ITEM.get(), SUNFLOWER_BOTTOM.get()),
                    Map.entry(Items.LILAC, Blocks.LILAC),
                    Map.entry(LILAC_TOP_ITEM.get(), LILAC_TOP.get()),
                    Map.entry(LILAC_BOTTOM_ITEM.get(), LILAC_BOTTOM.get()),
                    Map.entry(Items.ROSE_BUSH, Blocks.ROSE_BUSH),
                    Map.entry(ROSE_BUSH_TOP_ITEM.get(), ROSE_BUSH_TOP.get()),
                    Map.entry(ROSE_BUSH_BOTTOM_ITEM.get(), ROSE_BUSH_BOTTOM.get()),
                    Map.entry(Items.PEONY, Blocks.PEONY),
                    Map.entry(PEONY_TOP_ITEM.get(), PEONY_TOP.get()),
                    Map.entry(PEONY_BOTTOM_ITEM.get(), PEONY_BOTTOM.get()),
                    Map.entry(Items.TALL_GRASS, Blocks.TALL_GRASS),
                    Map.entry(TALL_GRASS_TOP_ITEM.get(), TALL_GRASS_TOP.get()),
                    Map.entry(TALL_GRASS_BOTTOM_ITEM.get(), TALL_GRASS_BOTTOM.get()),
                    Map.entry(Items.LARGE_FERN, Blocks.LARGE_FERN),
                    Map.entry(LARGE_FERN_TOP_ITEM.get(), LARGE_FERN_TOP.get()),
                    Map.entry(LARGE_FERN_BOTTOM_ITEM.get(), LARGE_FERN_BOTTOM.get()),
                    // No vanilla item to reuse here (see diseasedByFallback() above), so the creeper's own
                    // item is the only way to pick this species in the bag - outcome block is the creeper
                    // itself, same self-mapping as diseasedByFallback().
                    Map.entry(SUNFLOWER_CREEPER_ITEM.get(), SUNFLOWER_CREEPER.get()),
                    Map.entry(LILAC_CREEPER_ITEM.get(), LILAC_CREEPER.get()),
                    Map.entry(ROSE_BUSH_CREEPER_ITEM.get(), ROSE_BUSH_CREEPER.get()),
                    Map.entry(PEONY_CREEPER_ITEM.get(), PEONY_CREEPER.get())
            );
        }
        return bagOutcomeItems;
    }

    private static DeferredBlock<DiseasedFlowerBlock> registerDiseased(
            String name,
            Holder<MobEffect> suspiciousStewEffect,
            float effectSeconds,
            Block fallbackBlock
    ) {
        return BLOCKS.registerBlock(
                name,
                properties -> new DiseasedFlowerBlock(suspiciousStewEffect, effectSeconds, fallbackBlock, properties),
                flowerProperties()
        );
    }

    private static BlockBehaviour.Properties flowerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .noCollission()
                .instabreak()
                .sound(SoundType.GRASS)
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .pushReaction(PushReaction.DESTROY)
                .randomTicks();
    }

    private static DeferredBlock<DiseasedTallFlowerBlock> registerDiseasedTall(
            String name,
            Block fallbackBlock
    ) {
        return BLOCKS.registerBlock(
                name,
                properties -> new DiseasedTallFlowerBlock(fallbackBlock, properties),
                tallFlowerProperties()
        );
    }

    private static BlockBehaviour.Properties tallFlowerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .noCollission()
                .instabreak()
                .sound(SoundType.GRASS)
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .ignitedByLava()
                .pushReaction(PushReaction.DESTROY)
                .randomTicks();
    }

    private static DeferredBlock<CreepingFlowerBlock> registerCreeping(String name) {
        return BLOCKS.registerBlock(name, CreepingFlowerBlock::new, creepingFlowerProperties());
    }

    // Mirrors vanilla Glow Lichen's own properties (see Blocks.GLOW_LICHEN) rather than the flower
    // properties above - it's a thin face-hugging overlay, not a ground plant, so noCollission/replaceable/
    // no offsetType (offsets only make sense for something standing freely on a single spot) all carry
    // over; light/ignitedByLava don't apply to us and are left out.
    private static BlockBehaviour.Properties creepingFlowerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .replaceable()
                .noCollission()
                .strength(0.2F)
                .sound(SoundType.GRASS)
                .pushReaction(PushReaction.DESTROY)
                .randomTicks();
    }

    // Short Grass/Fern: matches vanilla's TallGrassBlock properties, including .replaceable() and the
    // XYZ offset (a little extra vertical jitter, on top of the XZ horizontal jitter flowers get).
    private static BlockBehaviour.Properties grassProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .replaceable()
                .noCollission()
                .instabreak()
                .sound(SoundType.GRASS)
                .offsetType(BlockBehaviour.OffsetType.XYZ)
                .ignitedByLava()
                .pushReaction(PushReaction.DESTROY)
                .randomTicks();
    }

    private static BlockBehaviour.Properties deadBushProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .replaceable()
                .noCollission()
                .instabreak()
                .sound(SoundType.GRASS)
                .ignitedByLava()
                .pushReaction(PushReaction.DESTROY)
                .randomTicks();
    }

    // Tall Grass/Large Fern: matches vanilla's DoublePlantBlock properties (XZ offset, like the tall
    // flowers, not XYZ like their own single-block versions above).
    private static BlockBehaviour.Properties tallGrassProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .replaceable()
                .noCollission()
                .instabreak()
                .sound(SoundType.GRASS)
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .ignitedByLava()
                .pushReaction(PushReaction.DESTROY)
                .randomTicks();
    }

    // No .randomTicks() - these are purely decorative and never spread or settle on their own. Their
    // spreading counterpart is DiseasedDecorativeFlowerBlock (see diseasedDecorativeFlowerProperties()).
    private static BlockBehaviour.Properties decorativeFlowerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .noCollission()
                .instabreak()
                .sound(SoundType.GRASS)
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .pushReaction(PushReaction.DESTROY);
    }

    private static DeferredBlock<DiseasedDecorativeFlowerBlock> registerDiseasedDecorative(
            String name,
            DeferredBlock<DecorativeFlowerBlock> fallback
    ) {
        return BLOCKS.registerBlock(
                name,
                properties -> new DiseasedDecorativeFlowerBlock(fallback.get(), properties),
                diseasedDecorativeFlowerProperties()
        );
    }

    // Same look/footprint as decorativeFlowerProperties(), but spreads like any other single-block flower.
    private static BlockBehaviour.Properties diseasedDecorativeFlowerProperties() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.PLANT)
                .noCollission()
                .instabreak()
                .sound(SoundType.GRASS)
                .offsetType(BlockBehaviour.OffsetType.XZ)
                .pushReaction(PushReaction.DESTROY)
                .randomTicks();
    }

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public FlowerDisease(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so block entity types get registered
        BLOCK_ENTITY_TYPES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so data component types get registered
        DATA_COMPONENT_TYPES.register(modEventBus);
        // Register the Deferred Register to the mod event bus so menu types get registered
        MENU_TYPES.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
        NeoForge.EVENT_BUS.register(this);

        // Register the item to a creative tab
        modEventBus.addListener(this::addCreative);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Flower Disease loaded");
    }

    // Diseased Flowers are never obtainable as an item (no BlockItem registered for them at all - see the
    // block registrations above) - they only ever exist as something the Garden Bag or a spreading plant
    // places directly in the world, never as a creative-tab/JEI entry or a "diseased" holdable in any
    // form. Only the plain decorative Top/Bottom halves (legitimate standalone decoration, not diseased)
    // and the 4 creeper items (no vanilla item exists to select that species in the bag instead - see
    // bagOutcomeItems()) get a creative-tab spot here, grouped right after their vanilla flower. The Flower
    // Block is obtainable too (right after Moss Block, which it currently looks like) - it's a real
    // standalone block players can inspect/build with, not a bag-only construct. The Garden Bag itself
    // goes in Tools and Utilities (it's a utility item, not a flower).
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(GARDEN_BAG);
            return;
        }

        if (event.getTabKey() != CreativeModeTabs.NATURAL_BLOCKS) {
            return;
        }

        insertAfter(event, Items.SUNFLOWER, SUNFLOWER_TOP_ITEM);
        insertAfter(event, SUNFLOWER_TOP_ITEM.get(), SUNFLOWER_BOTTOM_ITEM);
        insertAfter(event, SUNFLOWER_BOTTOM_ITEM.get(), SUNFLOWER_CREEPER_ITEM);
        insertAfter(event, SUNFLOWER_CREEPER_ITEM.get(), LILAC_TOP_ITEM);
        insertAfter(event, LILAC_TOP_ITEM.get(), LILAC_BOTTOM_ITEM);
        insertAfter(event, LILAC_BOTTOM_ITEM.get(), LILAC_CREEPER_ITEM);
        insertAfter(event, LILAC_CREEPER_ITEM.get(), ROSE_BUSH_TOP_ITEM);
        insertAfter(event, ROSE_BUSH_TOP_ITEM.get(), ROSE_BUSH_BOTTOM_ITEM);
        insertAfter(event, ROSE_BUSH_BOTTOM_ITEM.get(), ROSE_BUSH_CREEPER_ITEM);
        insertAfter(event, ROSE_BUSH_CREEPER_ITEM.get(), PEONY_TOP_ITEM);
        insertAfter(event, PEONY_TOP_ITEM.get(), PEONY_BOTTOM_ITEM);
        insertAfter(event, PEONY_BOTTOM_ITEM.get(), PEONY_CREEPER_ITEM);
        insertAfter(event, PEONY_CREEPER_ITEM.get(), TALL_GRASS_TOP_ITEM);
        insertAfter(event, TALL_GRASS_TOP_ITEM.get(), TALL_GRASS_BOTTOM_ITEM);
        insertAfter(event, TALL_GRASS_BOTTOM_ITEM.get(), LARGE_FERN_TOP_ITEM);
        insertAfter(event, LARGE_FERN_TOP_ITEM.get(), LARGE_FERN_BOTTOM_ITEM);
        insertAfter(event, Items.MOSS_BLOCK, FLOWER_BLOCK_ITEM);
    }

    private static void insertAfter(BuildCreativeModeTabContentsEvent event, Item anchor, DeferredItem<BlockItem> item) {
        event.insertAfter(new ItemStack(anchor), new ItemStack(item.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }

    // Debug-only "/cleargarden [radius]" command, bound to Ctrl+P client-side (see FlowerDiseaseClient).
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        FlowerDiseaseCommands.register(event.getDispatcher());
    }
}
