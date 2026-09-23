package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

// Finding this mod's blocks in already-loaded chunks without visiting every block of the world: a section whose
// palette holds none of them is skipped outright, so a scan costs roughly one palette check per section plus the
// 4096 lookups of the few sections that actually contain a plant. Never loads a chunk - unloaded ones are skipped.
// Shared by the stats command and the day simulation (see GardenStats, DayAdvance).
final class GardenScan {

    interface PlantVisitor {
        // The position is a cursor that gets reused for the next hit - copy it with immutable() to keep it.
        void visit(BlockPos pos, BlockState state);
    }

    private GardenScan() {
    }

    // Everything that random-ticks on this mod's behalf: Diseased plants of every shape (creepers included) and
    // Flower Blocks.
    static boolean isPlantBlock(BlockState state) {
        Block block = state.getBlock();
        return block instanceof FlowerMassBlock || FlowerDisease.fallbackByDiseased().containsKey(block);
    }

    // The chunks vanilla keeps ticking around each player of this level, i.e. where a plant would be growing anyway.
    static List<ChunkPos> chunksAroundPlayers(ServerLevel level) {
        PlayerList players = level.getServer().getPlayerList();
        int radius = Math.min(players.getSimulationDistance(), players.getViewDistance());

        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (ServerPlayer player : level.players()) {
            ChunkPos center = player.chunkPosition();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    chunks.add(new ChunkPos(center.x + dx, center.z + dz));
                }
            }
        }
        return new ArrayList<>(chunks);
    }

    static void forEachPlantBlock(ServerLevel level, ChunkPos chunkPos, PlantVisitor visitor) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
        if (chunk == null) {
            return;
        }

        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int index = 0; index < sections.length; index++) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(GardenScan::isPlantBlock)) {
                continue;
            }

            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (isPlantBlock(state)) {
                            cursor.set(chunkPos.getMinBlockX() + x, baseY + y, chunkPos.getMinBlockZ() + z);
                            visitor.visit(cursor, state);
                        }
                    }
                }
            }
        }
    }
}
