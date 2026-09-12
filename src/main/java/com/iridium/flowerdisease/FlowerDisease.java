package com.iridium.flowerdisease;

import java.util.List;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
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

    // Each Diseased Flower shares the DiseasedFlowerBlock behavior; the suspicious stew effect matches the
    // vanilla flower it's based on, and what it settles into once it can't spread is configured in Config.java.
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_DANDELION =
            registerDiseased("diseased_dandelion", MobEffects.SATURATION, 0.35F, Blocks.DANDELION, Config.DANDELION_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_POPPY =
            registerDiseased("diseased_poppy", MobEffects.NIGHT_VISION, 5.0F, Blocks.POPPY, Config.POPPY_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_BLUE_ORCHID =
            registerDiseased("diseased_blue_orchid", MobEffects.SATURATION, 0.35F, Blocks.BLUE_ORCHID, Config.BLUE_ORCHID_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_ALLIUM =
            registerDiseased("diseased_allium", MobEffects.FIRE_RESISTANCE, 4.0F, Blocks.ALLIUM, Config.ALLIUM_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_AZURE_BLUET =
            registerDiseased("diseased_azure_bluet", MobEffects.BLINDNESS, 8.0F, Blocks.AZURE_BLUET, Config.AZURE_BLUET_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_RED_TULIP =
            registerDiseased("diseased_red_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.RED_TULIP, Config.RED_TULIP_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_ORANGE_TULIP =
            registerDiseased("diseased_orange_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.ORANGE_TULIP, Config.ORANGE_TULIP_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_WHITE_TULIP =
            registerDiseased("diseased_white_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.WHITE_TULIP, Config.WHITE_TULIP_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_PINK_TULIP =
            registerDiseased("diseased_pink_tulip", MobEffects.WEAKNESS, 9.0F, Blocks.PINK_TULIP, Config.PINK_TULIP_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_OXEYE_DAISY =
            registerDiseased("diseased_oxeye_daisy", MobEffects.REGENERATION, 8.0F, Blocks.OXEYE_DAISY, Config.OXEYE_DAISY_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_CORNFLOWER =
            registerDiseased("diseased_cornflower", MobEffects.JUMP, 6.0F, Blocks.CORNFLOWER, Config.CORNFLOWER_SETTLE_WEIGHTS);
    public static final DeferredBlock<DiseasedFlowerBlock> DISEASED_LILY_OF_THE_VALLEY =
            registerDiseased("diseased_lily_of_the_valley", MobEffects.POISON, 12.0F, Blocks.LILY_OF_THE_VALLEY, Config.LILY_OF_THE_VALLEY_SETTLE_WEIGHTS);

    public static final DeferredItem<BlockItem> DISEASED_DANDELION_ITEM = ITEMS.registerSimpleBlockItem("diseased_dandelion", DISEASED_DANDELION);
    public static final DeferredItem<BlockItem> DISEASED_POPPY_ITEM = ITEMS.registerSimpleBlockItem("diseased_poppy", DISEASED_POPPY);
    public static final DeferredItem<BlockItem> DISEASED_BLUE_ORCHID_ITEM = ITEMS.registerSimpleBlockItem("diseased_blue_orchid", DISEASED_BLUE_ORCHID);
    public static final DeferredItem<BlockItem> DISEASED_ALLIUM_ITEM = ITEMS.registerSimpleBlockItem("diseased_allium", DISEASED_ALLIUM);
    public static final DeferredItem<BlockItem> DISEASED_AZURE_BLUET_ITEM = ITEMS.registerSimpleBlockItem("diseased_azure_bluet", DISEASED_AZURE_BLUET);
    public static final DeferredItem<BlockItem> DISEASED_RED_TULIP_ITEM = ITEMS.registerSimpleBlockItem("diseased_red_tulip", DISEASED_RED_TULIP);
    public static final DeferredItem<BlockItem> DISEASED_ORANGE_TULIP_ITEM = ITEMS.registerSimpleBlockItem("diseased_orange_tulip", DISEASED_ORANGE_TULIP);
    public static final DeferredItem<BlockItem> DISEASED_WHITE_TULIP_ITEM = ITEMS.registerSimpleBlockItem("diseased_white_tulip", DISEASED_WHITE_TULIP);
    public static final DeferredItem<BlockItem> DISEASED_PINK_TULIP_ITEM = ITEMS.registerSimpleBlockItem("diseased_pink_tulip", DISEASED_PINK_TULIP);
    public static final DeferredItem<BlockItem> DISEASED_OXEYE_DAISY_ITEM = ITEMS.registerSimpleBlockItem("diseased_oxeye_daisy", DISEASED_OXEYE_DAISY);
    public static final DeferredItem<BlockItem> DISEASED_CORNFLOWER_ITEM = ITEMS.registerSimpleBlockItem("diseased_cornflower", DISEASED_CORNFLOWER);
    public static final DeferredItem<BlockItem> DISEASED_LILY_OF_THE_VALLEY_ITEM = ITEMS.registerSimpleBlockItem("diseased_lily_of_the_valley", DISEASED_LILY_OF_THE_VALLEY);

    private static DeferredBlock<DiseasedFlowerBlock> registerDiseased(
            String name,
            Holder<MobEffect> suspiciousStewEffect,
            float effectSeconds,
            Block fallbackBlock,
            ModConfigSpec.ConfigValue<List<? extends String>> settleWeights
    ) {
        return BLOCKS.registerBlock(
                name,
                properties -> new DiseasedFlowerBlock(suspiciousStewEffect, effectSeconds, fallbackBlock, settleWeights, properties),
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

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public FlowerDisease(IEventBus modEventBus, ModContainer modContainer) {
        // Register the commonSetup method for modloading
        modEventBus.addListener(this::commonSetup);

        // Register the Deferred Register to the mod event bus so blocks get registered
        BLOCKS.register(modEventBus);
        // Register the Deferred Register to the mod event bus so items get registered
        ITEMS.register(modEventBus);

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

    // Place each Diseased Flower right after its vanilla counterpart in the Natural Blocks tab
    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() != CreativeModeTabs.NATURAL_BLOCKS) {
            return;
        }

        insertDiseasedAfter(event, Items.DANDELION, DISEASED_DANDELION_ITEM);
        insertDiseasedAfter(event, Items.POPPY, DISEASED_POPPY_ITEM);
        insertDiseasedAfter(event, Items.BLUE_ORCHID, DISEASED_BLUE_ORCHID_ITEM);
        insertDiseasedAfter(event, Items.ALLIUM, DISEASED_ALLIUM_ITEM);
        insertDiseasedAfter(event, Items.AZURE_BLUET, DISEASED_AZURE_BLUET_ITEM);
        insertDiseasedAfter(event, Items.RED_TULIP, DISEASED_RED_TULIP_ITEM);
        insertDiseasedAfter(event, Items.ORANGE_TULIP, DISEASED_ORANGE_TULIP_ITEM);
        insertDiseasedAfter(event, Items.WHITE_TULIP, DISEASED_WHITE_TULIP_ITEM);
        insertDiseasedAfter(event, Items.PINK_TULIP, DISEASED_PINK_TULIP_ITEM);
        insertDiseasedAfter(event, Items.OXEYE_DAISY, DISEASED_OXEYE_DAISY_ITEM);
        insertDiseasedAfter(event, Items.CORNFLOWER, DISEASED_CORNFLOWER_ITEM);
        insertDiseasedAfter(event, Items.LILY_OF_THE_VALLEY, DISEASED_LILY_OF_THE_VALLEY_ITEM);
    }

    private static void insertDiseasedAfter(BuildCreativeModeTabContentsEvent event, Item anchor, DeferredItem<BlockItem> diseased) {
        event.insertAfter(new ItemStack(anchor), new ItemStack(diseased.get()), CreativeModeTab.TabVisibility.PARENT_AND_SEARCH_TABS);
    }

    // You can use SubscribeEvent and let the Event Bus discover methods to call
    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        // Do something when the server starts
        LOGGER.info("HELLO from server starting");
    }
}
