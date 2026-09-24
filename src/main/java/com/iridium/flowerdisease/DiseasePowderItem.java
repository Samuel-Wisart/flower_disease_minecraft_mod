package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;

// The Bone Meal of this mod: instead of growing one plant it lets a garden live a day (Config diseasePowderDays) in an
// instant, the same fast-forward as /diseasedflower day (see DayAdvance) but aimed. Used on a growing plant, a creeper or a
// Flower Block, it advances the garden that block belongs to (see GardenRegistry) - every plant of it within
// diseasePowderRadius blocks, and its creeper patches; used on anything else, every growing plant within that radius, of
// whatever garden. A plant that has already settled into a plain flower keeps no record of its garden, which is why
// clicking one of those falls back to the whole area.
//
// It is used up only when there was something to advance, and only one fast-forward runs at a time, so it can't be
// stacked on top of another one.
public class DiseasePowderItem extends Item {

    enum Outcome { STARTED, NOTHING_TO_ADVANCE, BUSY }

    public DiseasePowderItem(Properties properties) {
        super(properties);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.flowerdisease.disease_powder.tooltip.garden", Config.POWDER_DAYS.getAsInt()));
        tooltip.add(Component.translatable("item.flowerdisease.disease_powder.tooltip.area", Config.POWDER_RADIUS.getAsInt()));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        BlockPos clicked = context.getClickedPos();
        Outcome outcome = advance(level, clicked, player instanceof ServerPlayer serverPlayer ? serverPlayer : null);
        if (outcome != Outcome.STARTED) {
            if (player != null) {
                player.displayClientMessage(Component.translatable("item.flowerdisease.disease_powder.message." + outcome.name().toLowerCase(java.util.Locale.ROOT)), true);
            }
            return InteractionResult.FAIL;
        }

        level.playSound(null, clicked, SoundEvents.BONE_MEAL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, clicked.getX() + 0.5, clicked.getY() + 1.0, clicked.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.0);
        if (player == null || !player.getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    // Starts the fast-forward for a click at `clicked` - the part that isn't about the item, so the game tests can drive
    // it. `player`, when there is one, sees the progress on the action bar and the summary in chat.
    static Outcome advance(ServerLevel level, BlockPos clicked, @Nullable ServerPlayer player) {
        if (DayAdvance.isRunning()) {
            return Outcome.BUSY;
        }

        int garden = gardenAt(level, clicked);
        int radius = Config.POWDER_RADIUS.getAsInt();
        int days = Config.POWDER_DAYS.getAsInt();
        DayAdvance.Scope scope = new DayAdvance.Scope(clicked, radius, garden);
        List<ChunkPos> chunks = chunksAround(clicked, radius);
        if (DayAdvance.countActive(level, chunks, scope) == 0) {
            return Outcome.NOTHING_TO_ADVANCE;
        }

        DayAdvance.start(level, chunks, days, new DayAdvance.Listener() {
            @Override
            public void progress(int step, int totalSteps, int activePlants) {
                if (player != null) {
                    player.displayClientMessage(Component.translatable("item.flowerdisease.disease_powder.progress", step, totalSteps), true);
                }
            }

            @Override
            public void finished(DayAdvance.Result result) {
                if (player != null) {
                    Component where = garden == GardenRegistry.NO_GARDEN
                            ? Component.translatable("item.flowerdisease.disease_powder.everything")
                            : Component.translatable("item.flowerdisease.disease_powder.garden", garden);
                    player.displayClientMessage(Component.translatable("item.flowerdisease.disease_powder.message.done", where, days,
                            result.after().active - result.before().active), false);
                }
            }
        }, scope);
        return Outcome.STARTED;
    }

    // The garden of what was clicked: the block itself, or the plant standing on it or hanging above it (the click is
    // usually on the ground under a flower, or on the top half of a tall one). NO_GARDEN when none of them has one.
    static int gardenAt(ServerLevel level, BlockPos clicked) {
        for (BlockPos candidate : new BlockPos[]{clicked, clicked.above(), clicked.below()}) {
            if (level.getBlockEntity(candidate) instanceof SpreadProfileBlockEntity plant && plant.garden() != GardenRegistry.NO_GARDEN) {
                return plant.garden();
            }
        }
        return GardenRegistry.NO_GARDEN;
    }

    // Every chunk within `radius` blocks of the click.
    private static List<ChunkPos> chunksAround(BlockPos center, int radius) {
        ChunkPos min = new ChunkPos(center.offset(-radius, 0, -radius));
        ChunkPos max = new ChunkPos(center.offset(radius, 0, radius));
        List<ChunkPos> chunks = new ArrayList<>();
        for (int x = min.x; x <= max.x; x++) {
            for (int z = min.z; z <= max.z; z++) {
                chunks.add(new ChunkPos(x, z));
            }
        }
        return chunks;
    }
}
