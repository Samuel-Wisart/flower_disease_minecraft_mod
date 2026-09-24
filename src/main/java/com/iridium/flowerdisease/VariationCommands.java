package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.common.ModConfigSpec;

// /diseasedflower variation: the knobs of the natural variation (see Variation) - to look at, to change while testing (the
// values are written to the config too), and a map of the two fields around the player, drawn in the chat, so a setting can
// be judged before any garden has grown on it.
final class VariationCommands {
    private static final int COLUMNS = 57;
    private static final int ROWS = 17;
    private static final int DEFAULT_STEP = 4;
    // A chat glyph is about twice as tall as it is wide, so a row covers twice the blocks of a column to keep the map square.
    private static final int ROW_STRETCH = 2;

    // The full block character, written as an escape so the source does not depend on the compiler's encoding.
    private static final String BLOCK = "█";

    private static final int MARKER_COLOUR = 0xFFFFFF;
    private static final int THICKET_COLOUR = 0x0E5A26;
    private static final int USUAL_COLOUR = 0x7CCB4C;
    private static final int CLEARING_COLOUR = 0xF2E7B0;
    private static final int MIXED_COLOUR = 0x383838;
    private static final int[] SPECIES_COLOURS = {0xE8433F, 0xF7D23E, 0x3F8CF2, 0xC24FE0, 0xF28C28, 0x36C9C0, 0xA5DA4A, 0xF5F5F5};

    private VariationCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("variation")
                .executes(VariationCommands::show)
                .then(doubleSetting("spacing", 0.0, 1.5, Config.SPACING_VARIATION))
                .then(intSetting("spacingscale", 8, 512, Config.SPACING_VARIATION_SCALE))
                .then(doubleSetting("spacingmin", 0.1, 1.0, Config.SPACING_MIN_FACTOR))
                .then(doubleSetting("spacingmax", 1.0, 10.0, Config.SPACING_MAX_FACTOR))
                .then(doubleSetting("species", 0.0, 3.0, Config.SPECIES_VARIATION))
                .then(intSetting("speciesscale", 8, 512, Config.SPECIES_VARIATION_SCALE))
                .then(doubleSetting("detail", 0.0, 1.0, Config.VARIATION_DETAIL))
                .then(intSetting("seed", Integer.MIN_VALUE, Integer.MAX_VALUE, Config.VARIATION_SEED))
                .then(Commands.literal("map")
                        .executes(context -> map(context, false, DEFAULT_STEP))
                        .then(stepArgument(false))
                        .then(Commands.literal("species")
                                .executes(context -> map(context, true, DEFAULT_STEP))
                                .then(stepArgument(true))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> stepArgument(boolean species) {
        return Commands.argument("step", IntegerArgumentType.integer(1, 64))
                .executes(context -> map(context, species, IntegerArgumentType.getInteger(context, "step")));
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
                "Flower Disease: variation " + name + " set to " + shown + ", from the next plant tick on (/diseasedflower variation map draws it)"
        ), true);
        return 1;
    }

    // ---- Show ---------------------------------------------------------------------------------------

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        for (String line : describe(source.getLevel(), BlockPos.containing(source.getPosition()))) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    // The settings in force, and what they make of the spot `here` (against the server's default density).
    static List<String> describe(ServerLevel level, BlockPos here) {
        Variation.Settings settings = Variation.settings();
        int density = SpreadMath.resolveDensity(-1);
        double factor = Variation.spacingFactor(level, here);
        double nominal = SpreadMath.minSpacing(density);
        return List.of(
                "Flower Disease: natural variation (/diseasedflower variation <spacing|spacingscale|spacingmin|spacingmax|species|speciesscale|detail|seed> <value> changes one; map draws it)",
                "  spacing " + two(settings.spacing()) + " over about " + Math.round(settings.spacingScale()) + " blocks: the spacing between plants goes from x"
                        + two(settings.minFactor()) + " (thickets) to x" + two(settings.maxFactor()) + " (clearings) of the usual"
                        + (settings.spacing() > 0 ? ", scaled by x" + two(Variation.calibration(settings)) + " to keep the average density" : " - off"),
                "  species " + two(settings.species()) + " over about " + Math.round(settings.speciesScale()) + " blocks: each species of a pool is likelier in some places than others"
                        + (settings.species() > 0 ? "" : " - off"),
                "  detail " + two(settings.detail()) + ", seed " + settings.seed(),
                "  where you stand: spacing x" + two(factor) + " (" + String.format(Locale.ROOT, "%.1f", SpreadMath.spacing(density, factor))
                        + " blocks instead of " + String.format(Locale.ROOT, "%.1f", nominal) + " at the default density " + density + ")"
        );
    }

    private static String two(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    // ---- Map ----------------------------------------------------------------------------------------

    // The spacing field around the player as coloured blocks (dark green = thicket, pale = clearing), or - for a pool of two
    // species or more, taken from the plant looked at - which species the fields favour where, a species one colour, dimmer
    // where the stretch is mixed. North is up, the player is the white cross in the middle.
    private static int map(CommandContext<CommandSourceStack> context, boolean species, int step) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();

        List<SettleTable.Option> pool = List.of();
        if (species) {
            SpreadProfileBlockEntity plant = FlowerDiseaseCommands.profileLookedAt(source);
            if (plant == null) {
                source.sendFailure(Component.literal("Flower Disease: look at a plant of the garden whose species you want to map - its pool is what gets drawn"));
                return 0;
            }
            pool = DiseasedPlantLogic.spreadableOptions(SettleTable.parse(plant.profile().speciesWeights()));
            if (pool.size() < 2) {
                source.sendFailure(Component.literal("Flower Disease: that garden's pool has fewer than two species, there is nothing to vary"));
                return 0;
            }
        }

        for (Component line : render(Variation.settings(), source.getLevel().getSeed(), BlockPos.containing(source.getPosition()), step, pool)) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    // The lines of a map centred on `here`: a header, ROWS rows of COLUMNS cells and a legend. `pool` empty draws the spacing
    // field; two species or more draws which of them the species fields favour.
    static List<Component> render(Variation.Settings settings, long seed, BlockPos here, int step, List<SettleTable.Option> pool) {
        boolean species = !pool.isEmpty();
        int rowStep = step * ROW_STRETCH;

        List<Component> lines = new ArrayList<>();
        boolean off = species ? settings.species() <= 0.0 : settings.spacing() <= 0.0;
        lines.add(Component.literal(
                "Flower Disease: " + (species ? "species" : "spacing") + " map, " + COLUMNS * step + " x " + ROWS * rowStep + " blocks around you, north up ("
                        + step + " blocks per column, " + rowStep + " per row)" + (off ? " - this variation is off (0), so the map is flat" : "")
        ));
        for (int row = 0; row < ROWS; row++) {
            int[] colours = new int[COLUMNS];
            for (int column = 0; column < COLUMNS; column++) {
                double x = here.getX() + 0.5 + (column - COLUMNS / 2) * step;
                double z = here.getZ() + 0.5 + (row - ROWS / 2) * rowStep;
                colours[column] = species
                        ? speciesColour(settings, seed, x, z, pool)
                        : spacingColour(settings, Variation.spacingFactor(settings, seed, x, z));
            }
            lines.add(row(colours, row == ROWS / 2 ? COLUMNS / 2 : -1));
        }
        lines.add(species ? speciesLegend(pool) : spacingLegend(settings));
        return lines;
    }

    // Dark green at the thicket end of the scale the settings allow, light green at the usual spacing, pale at the clearing end.
    private static int spacingColour(Variation.Settings settings, double factor) {
        double thicket = Math.log(settings.minFactor());
        double clearing = Math.log(settings.maxFactor());
        double range = Math.max(clearing - thicket, 1.0e-9);
        double position = Mth.clamp((Math.log(factor) - thicket) / range, 0.0, 1.0);
        // A few steps only: it keeps the runs of one colour long, so the line is a handful of components, not fifty-seven.
        position = Math.round(position * 16.0) / 16.0;

        double usual = (0.0 - thicket) / range;
        return position < usual
                ? blend(THICKET_COLOUR, USUAL_COLOUR, position / usual)
                : blend(USUAL_COLOUR, CLEARING_COLOUR, (position - usual) / (1.0 - usual));
    }

    // The colour of the species the fields favour most at (x, z), brighter the more it dominates.
    private static int speciesColour(Variation.Settings settings, long seed, double x, double z, List<SettleTable.Option> pool) {
        double total = 0.0;
        double best = -1.0;
        int bestIndex = 0;
        for (int i = 0; i < pool.size(); i++) {
            SettleTable.Option option = pool.get(i);
            double weight = option.weight() * Variation.speciesFactor(settings, seed, x, z, option.block());
            total += weight;
            if (weight > best) {
                best = weight;
                bestIndex = i;
            }
        }

        double share = total > 0.0 ? best / total : 1.0;
        double even = 1.0 / pool.size();
        double dominance = Mth.clamp((share - even) / (1.0 - even), 0.0, 1.0);
        dominance = Math.round(dominance * 6.0) / 6.0;
        return blend(MIXED_COLOUR, SPECIES_COLOURS[bestIndex % SPECIES_COLOURS.length], 0.35 + 0.65 * dominance);
    }

    private static int blend(int from, int to, double amount) {
        int red = (int) Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * amount);
        int green = (int) Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * amount);
        int blue = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
        return (red << 16) | (green << 8) | blue;
    }

    // One line of the map: runs of the same colour are one component each, and the player's cell (when this is the middle
    // row) is a white cross.
    private static Component row(int[] colours, int markerColumn) {
        MutableComponent line = Component.empty();
        if (markerColumn < 0) {
            appendRuns(line, colours, 0, colours.length);
            return line;
        }

        appendRuns(line, colours, 0, markerColumn);
        line.append(Component.literal("+").withStyle(Style.EMPTY.withColor(MARKER_COLOUR)));
        appendRuns(line, colours, markerColumn + 1, colours.length);
        return line;
    }

    private static void appendRuns(MutableComponent line, int[] colours, int from, int to) {
        int runStart = from;
        for (int column = from + 1; column <= to; column++) {
            if (column == to || colours[column] != colours[runStart]) {
                line.append(Component.literal(BLOCK.repeat(column - runStart)).withStyle(Style.EMPTY.withColor(colours[runStart])));
                runStart = column;
            }
        }
    }

    private static Component spacingLegend(Variation.Settings settings) {
        return Component.empty()
                .append(swatch(THICKET_COLOUR)).append(" x" + two(settings.minFactor()) + " thicket   ")
                .append(swatch(USUAL_COLOUR)).append(" x1.00 the usual   ")
                .append(swatch(CLEARING_COLOUR)).append(" x" + two(settings.maxFactor()) + " clearing");
    }

    private static Component speciesLegend(List<SettleTable.Option> pool) {
        MutableComponent legend = Component.empty();
        for (int i = 0; i < pool.size(); i++) {
            legend.append(swatch(SPECIES_COLOURS[i % SPECIES_COLOURS.length])).append(" ").append(pool.get(i).block().getName()).append("   ");
        }
        return legend.append(swatch(MIXED_COLOUR)).append(" mixed");
    }

    private static Component swatch(int colour) {
        return Component.literal(BLOCK + BLOCK).withStyle(Style.EMPTY.withColor(colour));
    }
}
