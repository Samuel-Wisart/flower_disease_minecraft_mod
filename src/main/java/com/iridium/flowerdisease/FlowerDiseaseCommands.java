package com.iridium.flowerdisease;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

// Debug-only tooling for testing the spread mechanic without having to keep teleporting to reset an area, and for
// inspecting or overriding the per-planting profile of a single plant without going through the Garden Bag. All of
// it needs operator permission: these commands rewrite or delete world content and have no business being available
// to every player of a multiplayer server.
final class FlowerDiseaseCommands {
    private static final int DEFAULT_VERTICAL_RANGE = 24;
    private static final double LOOK_DISTANCE = 6.0;
    private static final int PERMISSION_LEVEL = 2;

    private static final List<String> PROFILE_KEYS = List.of(
            "generations", "density", "distance", "decay", "nodecay", "lifetime", "ignoreothers", "climbing", "moss", "species"
    );

    // Toggled by /diseasedflower debug - see DiseasedPlantLogic, which spawns a particle at a plant's
    // position every time it's actually random-ticked (a settled plant never is, see SettleTable.SETTLED/
    // isRandomlyTicking), so this doubles as a live "is this one still reproducing?" indicator without
    // needing any bookkeeping of its own. Server-wide, not per-player, and not persisted across restarts -
    // matches every other debug toggle in this class.
    private static boolean debugParticlesEnabled = false;

    private FlowerDiseaseCommands() {
    }

    // Off while a day is being fast-forwarded (see DayAdvance): thousands of plants ticking at once would bury the
    // client in particles for no benefit.
    static boolean debugParticlesEnabled() {
        return debugParticlesEnabled && !DayAdvance.isRunning();
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var verticalRange = Commands.argument("verticalRange", IntegerArgumentType.integer(1, 320))
                .executes(context -> clear(
                        context,
                        IntegerArgumentType.getInteger(context, "radius"),
                        IntegerArgumentType.getInteger(context, "verticalRange")
                ));
        var radius = Commands.argument("radius", IntegerArgumentType.integer(1, 512))
                .executes(context -> clear(context, IntegerArgumentType.getInteger(context, "radius"), DEFAULT_VERTICAL_RANGE))
                .then(verticalRange);

        dispatcher.register(
                Commands.literal("cleargarden")
                        .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                        // No radius given: clear every currently loaded chunk instead of guessing a fixed
                        // area - this is what Ctrl+P sends, so it always reaches the whole test area.
                        .executes(context -> clearLoadedChunks(context, DEFAULT_VERTICAL_RANGE))
                        .then(radius)
        );

        var setValue = Commands.argument("value", StringArgumentType.greedyString())
                .executes(FlowerDiseaseCommands::setProfile);
        var setKey = Commands.argument("key", StringArgumentType.word())
                .suggests((context, builder) -> SharedSuggestionProvider.suggest(PROFILE_KEYS, builder))
                .then(setValue);
        var profile = Commands.literal("profile")
                .then(Commands.literal("show").executes(FlowerDiseaseCommands::showProfile))
                .then(Commands.literal("clear").executes(FlowerDiseaseCommands::clearProfile))
                .then(Commands.literal("set").then(setKey));
        var debug = Commands.literal("debug")
                .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(FlowerDiseaseCommands::setDebugParticles));
        var stats = Commands.literal("stats").executes(FlowerDiseaseCommands::showStats);

        var days = Commands.argument("days", IntegerArgumentType.integer(1, DayAdvance.MAX_DAYS))
                .executes(context -> startDay(context, IntegerArgumentType.getInteger(context, "days")));
        var day = Commands.literal("day")
                .executes(context -> startDay(context, 1))
                .then(Commands.literal("stop").executes(FlowerDiseaseCommands::stopDay))
                .then(days);

        dispatcher.register(
                Commands.literal("diseasedflower")
                        .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                        .then(profile)
                        .then(debug)
                        .then(stats)
                        .then(day)
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

    private static final String NOT_LOOKING = "Flower Disease: not looking at an active Diseased Flower or Flower Block (a plant that has settled keeps no data)";

    // /diseasedflower profile set <key> <value> changes ONE field of the garden the plant you're looking at belongs to
    // - and so, from their next tick on, of every plant of that garden (see GardenRegistry). See PROFILE_KEYS for the
    // names; the values mean what they do in GardenBagContents - "-1"/"-2" for generations, "-1" for density and
    // distance meaning "automatic". species takes a comma-separated list of "<block id> <weight>" entries - a vanilla
    // species id (e.g. "minecraft:rose_bush") or one of the Top/Bottom block ids (e.g. "flowerdisease:rose_bush_top",
    // its own independent species) both work the same way, same as the Garden Bag's species grid.
    private static int setProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        SpreadProfileBlockEntity plant = profileLookedAt(source);
        if (plant == null) {
            source.sendFailure(Component.literal(NOT_LOOKING));
            return 0;
        }

        String key = StringArgumentType.getString(context, "key").toLowerCase(Locale.ROOT);
        String value = StringArgumentType.getString(context, "value").trim();

        ProfileFields fields = new ProfileFields(plant.profile());
        try {
            fields.apply(key, value);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Flower Disease: " + e.getMessage()));
            return 0;
        }

        String changed = changeProfile(source.getLevel(), plant, fields.build());
        source.sendSuccess(() -> Component.literal("Flower Disease: " + key + " set on " + changed), false);
        return 1;
    }

    private static int clearProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        SpreadProfileBlockEntity plant = profileLookedAt(source);
        if (plant == null) {
            source.sendFailure(Component.literal(NOT_LOOKING));
            return 0;
        }

        plant.profile();
        String changed = changeProfile(source.getLevel(), plant, GardenBagContents.DEFAULT);
        source.sendSuccess(() -> Component.literal("Flower Disease: profile of " + changed + " cleared, back to the server defaults"), false);
        return 1;
    }

    // A garden's profile is shared by all its plants, so replacing it changes them all; a plant that belongs to none
    // (hand-placed) gets a garden of its own to hold the profile.
    private static String changeProfile(ServerLevel level, SpreadProfileBlockEntity plant, GardenBagContents updated) {
        if (plant.garden() == GardenRegistry.NO_GARDEN) {
            plant.startGarden(level, updated, plant.getBlockPos());
            return "this flower (a new garden of its own, #" + plant.garden() + ")";
        }

        GardenRegistry.of(level).replaceProfile(plant.garden(), updated);
        return "garden #" + plant.garden() + " (every plant of it)";
    }

    // Everything this plant's next random tick would be working with, resolved the same way the mod itself does -
    // the quickest way to check that a bag's contents (or a "profile set") ended up meaning what was intended.
    private static int showProfile(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        SpreadProfileBlockEntity plant = profileLookedAt(source);
        if (plant == null) {
            source.sendFailure(Component.literal(NOT_LOOKING));
            return 0;
        }

        GardenBagContents profile = plant.profile();
        long depth = plant.depth();
        GardenRegistry.Garden garden = plant.garden() == GardenRegistry.NO_GARDEN ? null : GardenRegistry.of(source.getLevel()).get(plant.garden());
        String gardenLine = garden == null
                ? "no garden (hand-placed, server defaults)"
                : garden.legacy()
                        ? "garden #" + plant.garden() + " (folded in from an older save)"
                        : "garden #" + plant.garden() + " (planted at " + garden.origin().toShortString() + ", game tick " + garden.plantedAt() + ")";
        long left = DiseasedPlantLogic.generationsLeft(profile, depth);

        double baseChance = profile.spreadChance() >= 0 ? profile.spreadChance() : Config.FLOWER_SPREAD_CHANCE.getAsDouble();
        double half = SpreadMath.halfGenerations(profile.decayStrength());
        double chance = SpreadMath.reproductionChance(baseChance, depth, half, profile.noDecay());
        int density = SpreadMath.resolveDensity(profile.densityPer16x16());
        int radius = SpreadMath.windowRadius(density);
        int limit = SpreadMath.crowdLimit(density, radius);
        int window = 2 * radius + 1;

        List<String> lines = List.of(
                "Flower Disease: " + gardenLine,
                "  generation " + (depth + 1) + " (depth " + depth + "), generations left " + (left < 0 ? "unlimited" : String.valueOf(left)),
                "  reproduction chance now " + percent(chance) + " (base " + percent(baseChance) + ", "
                        + (profile.noDecay() ? "no decay" : "halves at gen " + String.format(Locale.ROOT, "%.1f", half))
                        + "), lifetime ~" + SpreadMath.resolveLifetimeAttempts(profile.lifetimeAttempts()) + " attempts",
                "  density " + density + " per 16x16 (window " + window + "x" + window + ", limit " + limit
                        + "), max distance " + SpreadMath.resolveMaxDistance(profile.spreadDistance(), density),
                "  ignores others: " + !profile.respectAllSpecies() + ", climbing: " + profile.climbing()
                        + ", moss: " + profile.mossBlocks() + " (" + percent(SpreadMath.flowerBlockChance(profile.mossBlocks())) + ")",
                "  species: " + (profile.speciesWeights().isEmpty() ? "(none)" : String.join(", ", profile.speciesWeights()))
        );
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    // A census of the mod's blocks in the chunks around the players (see GardenStats for what it can and can't see).
    private static int showStats(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        List<ChunkPos> chunks = GardenScan.chunksAroundPlayers(level);

        source.sendSuccess(() -> Component.literal("Flower Disease: stats for " + chunks.size() + " chunks around the players"), false);
        for (String line : GardenStats.collect(level, chunks).describe(null)) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    // Fast-forwards the plants around the players by `days` in-game days (see DayAdvance); Alt+D sends this with one.
    private static int startDay(CommandContext<CommandSourceStack> context, int days) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();

        if (level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING) <= 0) {
            source.sendFailure(Component.literal("Flower Disease: randomTickSpeed is 0, nothing would grow"));
            return 0;
        }
        List<ChunkPos> chunks = GardenScan.chunksAroundPlayers(level);
        if (chunks.isEmpty()) {
            source.sendFailure(Component.literal("Flower Disease: no players in this dimension to simulate around"));
            return 0;
        }

        ServerPlayer reportTo = source.getPlayer();
        boolean started = DayAdvance.start(level, chunks, days, new DayAdvance.Listener() {
            @Override
            public void progress(int step, int totalSteps, int activePlants) {
                if (reportTo != null) {
                    reportTo.displayClientMessage(Component.literal(
                            "Flower Disease: simulating " + days + " day(s) - step " + step + "/" + totalSteps + " (" + activePlants + " active plants)"
                    ), true);
                }
            }

            @Override
            public void finished(DayAdvance.Result result) {
                reportDay(source, result);
            }
        });

        if (!started) {
            source.sendFailure(Component.literal("Flower Disease: a day is already being simulated (/diseasedflower day stop cancels it)"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Flower Disease: simulating " + days + " day(s) for the plants within " + chunks.size() + " chunks of the players"
        ), false);
        return 1;
    }

    private static int stopDay(CommandContext<CommandSourceStack> context) {
        if (!DayAdvance.isRunning()) {
            context.getSource().sendFailure(Component.literal("Flower Disease: no day is being simulated"));
            return 0;
        }
        DayAdvance.cancel();
        return 1;
    }

    private static void reportDay(CommandSourceStack source, DayAdvance.Result result) {
        String outcome = result.cancelled() ? (result.errors() > 0 ? "aborted after errors" : "cancelled") : "simulated";
        source.sendSuccess(() -> Component.literal(
                "Flower Disease: " + outcome + " " + result.days() + " day(s) - " + result.plantTicks() + " plant ticks in "
                        + result.workMillis() + " ms of server time" + (result.errors() > 0 ? ", " + result.errors() + " ERRORS (see the log)" : "")
        ), false);
        for (String line : result.after().describe(result.before())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
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

        return player.level().getBlockEntity(blockHit.getBlockPos()) instanceof SpreadProfileBlockEntity plant ? plant : null;
    }

    // A mutable copy of a profile, so a debug command can change one field without spelling out all eleven.
    private static final class ProfileFields {
        long generations;
        double spreadChance;
        int spreadDistance;
        int density;
        boolean respectAllSpecies;
        boolean climbing;
        int mossBlocks;
        int decayStrength;
        boolean noDecay;
        int lifetimeAttempts;
        List<String> species;

        ProfileFields(GardenBagContents from) {
            generations = from.generations();
            spreadChance = from.spreadChance();
            spreadDistance = from.spreadDistance();
            density = from.densityPer16x16();
            respectAllSpecies = from.respectAllSpecies();
            climbing = from.climbing();
            mossBlocks = from.mossBlocks();
            decayStrength = from.decayStrength();
            noDecay = from.noDecay();
            lifetimeAttempts = from.lifetimeAttempts();
            species = from.speciesWeights();
        }

        // Throws IllegalArgumentException (which includes NumberFormatException) with a message fit to show as is.
        void apply(String key, String value) {
            switch (key) {
                case "generations" -> generations = Long.parseLong(value);
                case "density" -> density = Integer.parseInt(value);
                case "distance" -> spreadDistance = Integer.parseInt(value);
                case "decay" -> decayStrength = Integer.parseInt(value);
                case "nodecay" -> noDecay = parseBoolean(value);
                case "lifetime" -> lifetimeAttempts = Integer.parseInt(value);
                case "ignoreothers" -> respectAllSpecies = !parseBoolean(value);
                case "climbing" -> climbing = parseBoolean(value);
                case "moss" -> mossBlocks = Integer.parseInt(value);
                case "species" -> species = value.isEmpty()
                        ? List.of()
                        : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
                default -> throw new IllegalArgumentException("unknown key '" + key + "' (one of " + String.join(", ", PROFILE_KEYS) + ")");
            }
        }

        GardenBagContents build() {
            return new GardenBagContents(
                    generations, spreadChance, spreadDistance, density, respectAllSpecies, climbing,
                    mossBlocks, decayStrength, noDecay, lifetimeAttempts, species
            );
        }

        private static boolean parseBoolean(String value) {
            if (value.equalsIgnoreCase("true")) {
                return true;
            }
            if (value.equalsIgnoreCase("false")) {
                return false;
            }
            throw new IllegalArgumentException("expected true or false, got '" + value + "'");
        }
    }
}
