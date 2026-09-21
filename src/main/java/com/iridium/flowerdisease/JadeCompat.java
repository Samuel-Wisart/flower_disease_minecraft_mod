package com.iridium.flowerdisease;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.JadeIds;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

// Optional Jade integration. Diseased Flowers are never obtainable as an item and never show up in
// creative/JEI (see FlowerDisease#addCreative) - "Diseased Flower" is meant to be a concept a plant is
// temporarily going through, not a distinct thing the player collects or sees labeled as such once it's
// done spreading (see PLANNING_STAGE2.md). Jade's look-at overlay is the only place a player ever sees a
// name for a placed block at all, so this swaps that one line: still spreading -> the generic "Diseased
// Flower"; settled -> the real species name (SettleTable#SETTLED), even though the block itself is
// technically still ours.
//
// This class is only ever loaded by Jade's own plugin scanner (triggered by the @WailaPlugin annotation),
// and only when Jade is actually installed - nothing in the rest of this mod references it. The
// compileOnly Jade dependency in build.gradle makes snownee.jade.api visible while building; without Jade
// present at runtime, this class is simply never classloaded, so its references to Jade's API never get
// resolved and never need to.
@WailaPlugin
public class JadeCompat implements IWailaPlugin {

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(new SpeciesNameProvider(), Block.class);
    }

    private static final class SpeciesNameProvider implements IBlockComponentProvider {
        private static final ResourceLocation UID = ResourceLocation.fromNamespaceAndPath(FlowerDisease.MODID, "species_name");

        @Override
        public ResourceLocation getUid() {
            return UID;
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            BlockState state = accessor.getBlockState();
            if (!state.hasProperty(SettleTable.SETTLED)) {
                // Every other block in the game takes this early exit - see the Block.class target above.
                return;
            }

            Component name = state.getValue(SettleTable.SETTLED)
                    ? settledName(accessor.getBlock())
                    : Component.translatable("flowerdisease.jade.spreading");
            tooltip.replace(JadeIds.CORE_OBJECT_NAME, name);
        }

        private static Component settledName(Block diseased) {
            Block vanillaLike = FlowerDisease.fallbackByDiseased().get(diseased);
            return (vanillaLike != null ? vanillaLike : diseased).getName();
        }
    }
}
