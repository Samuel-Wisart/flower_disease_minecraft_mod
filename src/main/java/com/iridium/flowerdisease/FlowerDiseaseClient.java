package com.iridium.flowerdisease;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = FlowerDisease.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = FlowerDisease.MODID, value = Dist.CLIENT)
public class FlowerDiseaseClient {
    // Debug-only: clears nearby plant blocks so testing spread doesn't require teleporting away and back.
    private static final KeyMapping CLEAR_GARDEN_KEY = new KeyMapping(
            "key.flowerdisease.clear_garden",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_P,
            "key.categories.flowerdisease"
    );

    // Debug-only: fast-forwards the plants around the player by one in-game day (see DayAdvance). Alt rather than
    // Ctrl - Ctrl+D is sprint+strafe-right, which would trigger it mid-run.
    private static final KeyMapping ADVANCE_DAY_KEY = new KeyMapping(
            "key.flowerdisease.advance_day",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.ALT,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_D,
            "key.categories.flowerdisease"
    );

    public FlowerDiseaseClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(CLEAR_GARDEN_KEY);
        event.register(ADVANCE_DAY_KEY);
    }

    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(FlowerDisease.GARDEN_BAG_MENU.get(), GardenBagScreen::new);
    }

    // Our grass/fern blocks aren't in vanilla's BlockColors/ItemColors registrations (those are keyed by
    // exact Block instance), so without this they'd render white instead of biome-green. Mirrors exactly
    // what vanilla registers for Blocks.SHORT_GRASS/FERN/TALL_GRASS/LARGE_FERN.
    @SubscribeEvent
    static void onRegisterBlockColors(RegisterColorHandlersEvent.Block event) {
        event.register(
                (state, level, pos, tintIndex) -> level != null && pos != null
                        ? BiomeColors.getAverageGrassColor(level, pos)
                        : GrassColor.getDefaultColor(),
                FlowerDisease.DISEASED_SHORT_GRASS.get(), FlowerDisease.DISEASED_FERN.get()
        );
        event.register(
                (state, level, pos, tintIndex) -> level != null && pos != null
                        ? BiomeColors.getAverageGrassColor(level, state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos)
                        : GrassColor.getDefaultColor(),
                FlowerDisease.DISEASED_TALL_GRASS.get(), FlowerDisease.DISEASED_LARGE_FERN.get()
        );
        // Tall Grass/Large Fern Top/Bottom (decorative and their spreading Diseased counterparts) are
        // always a single full block at pos (no HALF property to worry about), same as Short Grass/Fern's
        // own tint above.
        event.register(
                (state, level, pos, tintIndex) -> level != null && pos != null
                        ? BiomeColors.getAverageGrassColor(level, pos)
                        : GrassColor.getDefaultColor(),
                FlowerDisease.TALL_GRASS_TOP.get(), FlowerDisease.TALL_GRASS_BOTTOM.get(),
                FlowerDisease.LARGE_FERN_TOP.get(), FlowerDisease.LARGE_FERN_BOTTOM.get(),
                FlowerDisease.DISEASED_TALL_GRASS_TOP.get(), FlowerDisease.DISEASED_TALL_GRASS_BOTTOM.get(),
                FlowerDisease.DISEASED_LARGE_FERN_TOP.get(), FlowerDisease.DISEASED_LARGE_FERN_BOTTOM.get()
        );
    }

    @SubscribeEvent
    static void onRegisterItemColors(RegisterColorHandlersEvent.Item event) {
        event.register(
                (stack, tintIndex) -> GrassColor.get(0.5, 1.0),
                FlowerDisease.DISEASED_TALL_GRASS.get(), FlowerDisease.DISEASED_LARGE_FERN.get(),
                FlowerDisease.TALL_GRASS_TOP.get(), FlowerDisease.TALL_GRASS_BOTTOM.get(),
                FlowerDisease.LARGE_FERN_TOP.get(), FlowerDisease.LARGE_FERN_BOTTOM.get(),
                FlowerDisease.DISEASED_TALL_GRASS_TOP.get(), FlowerDisease.DISEASED_TALL_GRASS_BOTTOM.get(),
                FlowerDisease.DISEASED_LARGE_FERN_TOP.get(), FlowerDisease.DISEASED_LARGE_FERN_BOTTOM.get()
        );

        BlockColors blockColors = event.getBlockColors();
        event.register((stack, tintIndex) -> {
            BlockState state = ((BlockItem) stack.getItem()).getBlock().defaultBlockState();
            return blockColors.getColor(state, null, null, tintIndex);
        }, FlowerDisease.DISEASED_SHORT_GRASS.get(), FlowerDisease.DISEASED_FERN.get());
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        while (CLEAR_GARDEN_KEY.consumeClick()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.connection.sendCommand("cleargarden");
            }
        }
        while (ADVANCE_DAY_KEY.consumeClick()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                player.connection.sendCommand("diseasedflower day");
            }
        }
    }
}
