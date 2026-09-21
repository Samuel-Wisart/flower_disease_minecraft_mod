package com.iridium.flowerdisease;

import java.util.Arrays;
import java.util.List;

import javax.annotation.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

// Debug-only tooling for testing the spread mechanic without having to keep teleporting to reset an area,
// and for testing the (future Garden Bag's) per-planting override system before the bag item exists.
final class FlowerDiseaseCommands {
    private static final int DEFAULT_VERTICAL_RANGE = 24;
    private static final double LOOK_DISTANCE = 6.0;

    // Toggled by /diseasedflower debug - see DiseasedPlantLogic, which spawns a particle at a plant's
    // position every time it's actually random-ticked (a settled plant never is, see SettleTable.SETTLED/
    // isRandomlyTicking), so this doubles as a live "is this one still reproducing?" indicator without
    // needing any bookkeeping of its own. Server-wide, not per-player, and not persisted across restarts -
    // matches every other debug toggle in this class.
    private static boolean debugParticlesEnabled = false;

    private FlowerDiseaseCommands() {
    }

    static boolean debugParticlesEnabled() {
        return debugParticlesEnabled;
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("cleargarden")
                        // No radius given: clear every currently loaded chunk instead of guessing a fixed
                        // area - this is what Ctrl+P sends, so it always reaches the whole test area.
                        .executes(context -> clearLoadedChunks(context, DEFAULT_VERTICAL_RANGE))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                                .executes(context -> clear(context, IntegerArgumentType.getInteger(context, "radius"), DEFAULT_VERTICAL_RANGE))
                                .then(Commands.argument("verticalRange", IntegerArgumentType.integer(1, 320))
                                        .executes(context -> clear(
                                                context,
                                                IntegerArgumentType.getInteger(context, "radius"),
                                                IntegerArgumentType.getInteger(context, "verticalRange")
                                        ))))
        );

        dispatcher.register(
                Commands.literal("diseasedflower")
                        .then(Commands.literal("profile")
                                .then(Commands.literal("clear").executes(FlowerDiseaseCommands::clearProfile))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("generations", LongArgumentType.longArg(-1))
                                                .then(Commands.argument("spreadChance", DoubleArgumentType.doubleArg(-1, 1))
                                                        .then(Commands.argument("spreadDistance", IntegerArgumentType.integer(-1, 64))
                                                                .then(Commands.argument("densityPer16x16", IntegerArgumentType.integer(-1, 999))
                                                                        .then(Commands.argument("territorial", BoolArgumentType.bool())
                                                                                .then(Commands.argument("climbing", BoolArgumentType.bool())
                                                                                        .then(Commands.argument("spawnsFlowerBlocks", BoolArgumentType.bool())
                                                                                                .executes(context -> setProfile(context, ""))
                                                                                                .then(Commands.argument("species", StringArgumentType.greedyString())
                                                                                                        .executes(context -> setProfile(context, StringArgumentType.getString(context, "species")))))))))))))
                        .then(Commands.literal("debug")
                                .then(Commands.argument("enabled", BoolArgumentType.bool())
                                        .executes(FlowerDiseaseCommands::setDebugParticles)))
        );
    }

    private static int clear(CommandContext<CommandSourceStack> context, int radius, int verticalRange) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());

        int cleared = clearColumns(level, center.getX() - radius, center.getZ() - radius, center.getX() + radius, center.getZ() + radius, center.getY(), verticalRange);
        reportCleared(source, cleared);
        return cleared;
    }

    // Bounds the search by the server's view distance around the command source instead of a guessed
    // radius, then skips any column whose chunk isn't actually loaded (hasChunkAt is a cheap lookup and,
    // critically, never forces a chunk to load/generate the way blindly calling getBlockState would).
    private static int clearLoadedChunks(CommandContext<CommandSourceStack> context, int verticalRange) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos center = BlockPos.containing(source.getPosition());
        int radius = (source.getServer().getPlayerList().getViewDistance() + 1) * 16;

        int cleared = clearColumns(level, center.getX() - radius, center.getZ() - radius, center.getX() + radius, center.getZ() + radius, center.getY(), verticalRange);
        reportCleared(source, cleared);
        return cleared;
    }

    private static int clearColumns(ServerLevel level, int minX, int minZ, int maxX, int maxZ, int centerY, int verticalRange) {
        int minY = Math.max(level.getMinBuildHeight(), centerY - verticalRange);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centerY + verticalRange);

        int cleared = 0;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))) {
                    continue;
                }

                for (int y = minY; y <= maxY; y++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    // Covers vanilla and Diseased flowers/grass/ferns/dead bush alike, plus our
                    // decorative tops - all of them extend BushBlock. Creeping flowers (Stage 2 Fase 2)
                    // don't, so they're checked separately - added ahead of the rest of Fase 4 on purpose,
                    // otherwise every test of those features leaves residue Ctrl+P can't touch. No drops
                    // either way, straight to air.
                    if (state.getBlock() instanceof BushBlock || state.getBlock() instanceof CreepingFlowerBlock) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), SettleTable.PLACEMENT_FLAGS);
                        cleared++;
                    } else if (state.getBlock() instanceof FlowerMassBlock) {
                        // Restores whatever terrain this corrupted instead of leaving a hole (see
                        // PLANNING_STAGE2.md 3.4) - falls back to air only if the BlockEntity somehow
                        // isn't there to ask.
                        BlockState restored = level.getBlockEntity(cursor) instanceof FlowerMassBlockEntity flowerMass
                                ? flowerMass.replacedState()
                                : Blocks.AIR.defaultBlockState();
                        level.setBlock(cursor, restored, SettleTable.PLACEMENT_FLAGS);
                        cleared++;
                    }
                }
            }
        }
        return cleared;
    }

    private static void reportCleared(CommandSourceStack source, int cleared) {
        source.sendSuccess(() -> Component.literal("Flower Disease: cleared " + cleared + " plant blocks"), false);
    }

    // "-1" means "no override, use Config.java/blockstate for that field" for every numeric argument
    // here except generations, where "-1" means "infinite" (see SpreadProfileBlockEntity). species is a
    // comma-separated list of "<block id> <weight>" outcome entries - a vanilla species id (e.g.
    // "minecraft:rose_bush") or one of the Top/Bottom block ids (e.g. "flowerdisease:rose_bush_top",
    // its own independent species) both work the same way, same as the Garden Bag's species grid.
    // climbing/spawnsFlowerBlocks mirror the bag's Twisting Vines/Moss Block toggles (see
    // PLANNING_STAGE2.md).
    private static int setProfile(CommandContext<CommandSourceStack> context, String speciesArg) throws CommandSyntaxException {
        SpreadProfileBlockEntity profile = profileLookedAt(context.getSource());
        if (profile == null) {
            context.getSource().sendFailure(Component.literal("Flower Disease: not looking at a Diseased Flower"));
            return 0;
        }

        long generations = LongArgumentType.getLong(context, "generations");
        double spreadChance = DoubleArgumentType.getDouble(context, "spreadChance");
        int spreadDistance = IntegerArgumentType.getInteger(context, "spreadDistance");
        int densityPer16x16 = IntegerArgumentType.getInteger(context, "densityPer16x16");
        boolean territorial = BoolArgumentType.getBool(context, "territorial");
        boolean climbing = BoolArgumentType.getBool(context, "climbing");
        boolean spawnsFlowerBlocks = BoolArgumentType.getBool(context, "spawnsFlowerBlocks");
        List<String> species = speciesArg.isBlank()
                ? List.of()
                : Arrays.stream(speciesArg.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();

        profile.configure(new GardenBagContents(generations, spreadChance, spreadDistance, densityPer16x16, territorial, climbing, spawnsFlowerBlocks, species));
        context.getSource().sendSuccess(() -> Component.literal("Flower Disease: profile set on the flower you're looking at"), false);
        return 1;
    }

    private static int clearProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        SpreadProfileBlockEntity profile = profileLookedAt(context.getSource());
        if (profile == null) {
            context.getSource().sendFailure(Component.literal("Flower Disease: not looking at a Diseased Flower"));
            return 0;
        }

        profile.configure(new GardenBagContents(SpreadProfileBlockEntity.NO_GENERATIONS_OVERRIDE, -1, -1, -1, true, false, false, List.of()));
        context.getSource().sendSuccess(() -> Component.literal("Flower Disease: profile cleared, back to global config"), false);
        return 1;
    }

    private static int setDebugParticles(CommandContext<CommandSourceStack> context) {
        debugParticlesEnabled = BoolArgumentType.getBool(context, "enabled");
        context.getSource().sendSuccess(() -> Component.literal(
                "Flower Disease: debug particles " + (debugParticlesEnabled ? "ON" : "OFF")
                        + " (sparkle = still reproducing; a settled plant never shows one again)"
        ), false);
        return 1;
    }

    @Nullable
    private static SpreadProfileBlockEntity profileLookedAt(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        HitResult hit = player.pick(LOOK_DISTANCE, 1.0F, false);
        if (!(hit instanceof BlockHitResult blockHit)) {
            return null;
        }

        return player.level().getBlockEntity(blockHit.getBlockPos()) instanceof SpreadProfileBlockEntity profile ? profile : null;
    }
}
