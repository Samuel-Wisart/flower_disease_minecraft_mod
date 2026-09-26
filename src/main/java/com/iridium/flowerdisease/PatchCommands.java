package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.ModConfigSpec;

// /diseasedflower patch: the knobs of the creeper patches (see PatchGrowth) - to look at, to change while testing (the values are
// written to the config too), and next to them the patches that already exist around the players: how many, how big, how round.
// So a setting can be judged the way it is meant to be: change it, wait for (or Disease Powder) a day of growth, look again.
final class PatchCommands {

    private PatchCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("patch")
                .executes(PatchCommands::show)
                .then(intSetting("maxpieces", 0, 40, Config.PATCH_MAX_PIECES))
                .then(doubleSetting("growth", 0.0, 1.0, Config.PATCH_GROWTH_CHANCE))
                .then(doubleSetting("turns", 0.0, 3.0, Config.PATCH_TURNS))
                .then(doubleSetting("branching", 0.0, 3.0, Config.PATCH_BRANCHING))
                .then(doubleSetting("thickness", 0.0, 3.0, Config.PATCH_THICKNESS))
                .then(intSetting("crowding", 1, 8, Config.PATCH_CROWDING))
                .then(doubleSetting("variety", 0.0, 1.5, Config.PATCH_STYLE_VARIETY))
                .then(doubleSetting("hangchance", 0.0, 1.0, Config.PATCH_HANG_CHANCE))
                .then(intSetting("hanglength", 1, 16, Config.PATCH_HANG_LENGTH));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> doubleSetting(String name, double min, double max, ModConfigSpec.DoubleValue value) {
        return Commands.literal(name).then(Commands.argument("value", DoubleArgumentType.doubleArg(min, max)).executes(context -> {
            double chosen = DoubleArgumentType.getDouble(context, "value");
            return change(context.getSource(), name, String.format(Locale.ROOT, "%.2f", chosen), () -> {
                value.set(chosen);
                value.save();
            });
        }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> intSetting(String name, int min, int max, ModConfigSpec.IntValue value) {
        return Commands.literal(name).then(Commands.argument("value", IntegerArgumentType.integer(min, max)).executes(context -> {
            int chosen = IntegerArgumentType.getInteger(context, "value");
            return change(context.getSource(), name, String.valueOf(chosen), () -> {
                value.set(chosen);
                value.save();
            });
        }));
    }

    private static int change(CommandSourceStack source, String name, String shown, Runnable apply) {
        try {
            apply.run();
        } catch (IllegalStateException e) {
            source.sendFailure(Component.literal("Flower Disease: the config is not loaded yet, could not change it"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(
                "Flower Disease: patch " + name + " set to " + shown + ", for the patches that start growing from now on (/diseasedflower patch shows the result)"
        ), true);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        List<ChunkPos> chunks = GardenScan.chunksAroundPlayers(level);

        List<String> lines = new ArrayList<>(describe());
        lines.add("Flower Disease: the patches in the " + chunks.size() + " chunks around the players");
        List<String> around = GardenStats.collect(level, chunks).patchLines();
        lines.addAll(around.isEmpty() ? List.of("  no creeper blocks there") : around);
        for (String line : lines) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    // The settings in force and what they make of each of the three styles.
    static List<String> describe() {
        PatchGrowth.Settings settings = PatchGrowth.settings();
        List<String> lines = new ArrayList<>();
        lines.add("Flower Disease: creeper patches (/diseasedflower patch <maxpieces|growth|turns|branching|thickness|crowding|variety|hangchance|hanglength> <value> changes one)");
        lines.add("  max " + settings.maxPieces() + " pieces, grows " + percent(settings.growthChance()) + " of the random ticks, turns x" + two(settings.turns())
                + ", branching x" + two(settings.branching()) + ", thickness x" + two(settings.thickness()) + ", crowding " + settings.crowding()
                + ", style variety " + two(settings.variety()));
        lines.add("  hanging strands: " + (settings.hangChance() > 0.0
                ? percent(settings.hangChance()) + " at a wall's lower edge, up to " + settings.hangLength() + " pieces"
                : "off"));

        int total = 0;
        for (PatchGrowth.Style style : PatchGrowth.Style.ALL) {
            total += style.weight;
        }
        for (PatchGrowth.Style style : PatchGrowth.Style.ALL) {
            PatchGrowth.Traits traits = PatchGrowth.traits(settings, style);
            lines.add("  " + style.name().toLowerCase(Locale.ROOT) + " (" + percent((double) style.weight / total) + " of the seeds): up to " + traits.cap()
                    + " pieces, turns " + percent(traits.turn()) + ", splits " + percent(traits.branch()) + ", thickens " + percent(traits.thickness())
                    + " of the steps, puts up with " + traits.crowd() + " neighbours");
        }
        return lines;
    }

    private static String percent(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100.0);
    }

    private static String two(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
