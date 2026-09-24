package com.iridium.flowerdisease;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

// A census of this mod's blocks in a set of loaded chunks, for /diseasedflower stats and for the before/after
// summary of /diseasedflower day. It can only see what still IS one of our blocks: a plant that settled into its plain
// vanilla flower has no block entity and looks like any other flower in the world, so it isn't counted anywhere here.
final class GardenStats {
    private static final int SIZE_SAMPLE = 64;

    int active;
    int settledInPlace;
    int creeperPieces;
    int flowerBlocksActive;
    int flowerBlocksSettled;
    // Diseased plants (not flower blocks) - the ones whose lineage depth means something.
    int plants;
    long depthSum;
    long maxDepth;
    int blockEntities;
    int sampledEntities;
    long sampledBytes;
    // The first few block entities as they would be saved, kept to measure how well they compress together.
    private final ListTag sampledTags = new ListTag();
    // Active Diseased plants per garden id (see GardenRegistry), for the plants that belong to one.
    final Int2IntOpenHashMap activePerGarden = new Int2IntOpenHashMap();

    static GardenStats collect(ServerLevel level, List<ChunkPos> chunks) {
        GardenStats stats = new GardenStats();
        for (ChunkPos chunk : chunks) {
            GardenScan.forEachPlantBlock(level, chunk, (pos, state) -> stats.add(level, pos, state));
        }
        return stats;
    }

    private void add(ServerLevel level, BlockPos pos, BlockState state) {
        boolean flowerBlock = state.getBlock() instanceof FlowerMassBlock;
        if (!flowerBlock && state.hasProperty(DoublePlantBlock.HALF) && state.getValue(DoublePlantBlock.HALF) == DoubleBlockHalf.UPPER) {
            // One plant, counted through its lower half.
            return;
        }

        boolean settled = DiseasedPlantLogic.isSettled(state);
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof SpreadProfileBlockEntity plant) {
            blockEntities++;
            if (sampledEntities < SIZE_SAMPLE) {
                sampledEntities++;
                CompoundTag saved = plant.saveWithFullMetadata(level.registryAccess());
                sampledBytes += serializedSize(saved);
                sampledTags.add(saved);
            }
            if (!flowerBlock) {
                plants++;
                depthSum += plant.depth();
                maxDepth = Math.max(maxDepth, plant.depth());
                if (plant.garden() != GardenRegistry.NO_GARDEN && !settled) {
                    activePerGarden.addTo(plant.garden(), 1);
                }
            }
        }

        if (flowerBlock) {
            if (settled) {
                flowerBlocksSettled++;
            } else {
                flowerBlocksActive++;
            }
            return;
        }

        if (state.getBlock() instanceof CreepingFlowerBlock) {
            creeperPieces++;
        }
        if (settled) {
            settledInPlace++;
        } else {
            active++;
        }
    }

    // What the tag costs on disk, uncompressed - CompoundTag#sizeInBytes is an in-memory accounting figure that runs
    // several times higher.
    static int serializedSize(CompoundTag tag) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            NbtIo.write(tag, out);
            return bytes.size();
        } catch (IOException e) {
            return tag.sizeInBytes();
        }
    }

    double meanDepth() {
        return plants == 0 ? 0.0 : (double) depthSum / plants;
    }

    long bytesPerEntity() {
        return sampledEntities == 0 ? 0 : sampledBytes / sampledEntities;
    }

    // What the sample takes once compressed together, per entity - closer to what a region file really holds, since
    // chunk data is compressed and block entities are very repetitive. Sampled from the first entities found, which
    // tend to sit next to each other, so a somewhat rosy figure.
    long compressedBytesPerEntity() {
        if (sampledEntities == 0) {
            return 0;
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            CompoundTag wrapper = new CompoundTag();
            wrapper.put("Sample", sampledTags);
            NbtIo.writeCompressed(wrapper, bytes);
            return bytes.size() / sampledEntities;
        } catch (IOException e) {
            return bytesPerEntity();
        }
    }

    // The gardens with the most active plants, biggest first, as "#id (n)".
    private String biggestGardens(int limit) {
        List<Int2IntMap.Entry> entries = new ArrayList<>(activePerGarden.int2IntEntrySet());
        entries.sort((a, b) -> Integer.compare(b.getIntValue(), a.getIntValue()));
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            text.append(i == 0 ? "" : ", ").append('#').append(entries.get(i).getIntKey()).append(" (").append(entries.get(i).getIntValue()).append(')');
        }
        return text.toString();
    }

    // Lines for chat; `before` (nullable) adds the change since then in brackets.
    List<String> describe(GardenStats before) {
        List<String> lines = new ArrayList<>(List.of(
                "  diseased plants: " + active + change(active, before == null ? null : before.active) + " active, "
                        + settledInPlace + change(settledInPlace, before == null ? null : before.settledInPlace)
                        + " settled in place (creeper pieces: " + creeperPieces + ")",
                "  flower blocks: " + flowerBlocksActive + " active, " + flowerBlocksSettled + " settled",
                "  lineage depth: mean " + String.format(Locale.ROOT, "%.1f", meanDepth()) + ", max " + maxDepth + " (over " + plants + " plants)",
                "  block entities: " + blockEntities + " - one saves as about " + bytesPerEntity() + " bytes (~"
                        + (blockEntities * bytesPerEntity() / 1024) + " KB in all), roughly " + compressedBytesPerEntity()
                        + " once compressed on disk (~" + (blockEntities * compressedBytesPerEntity() / 1024) + " KB)"
        ));
        if (!activePerGarden.isEmpty()) {
            lines.add("  gardens with active plants: " + activePerGarden.size() + " - most active: " + biggestGardens(3));
        }
        return lines;
    }

    private static String change(int now, Integer before) {
        if (before == null || now == before) {
            return "";
        }
        return " (" + (now > before ? "+" : "") + (now - before) + ")";
    }
}
