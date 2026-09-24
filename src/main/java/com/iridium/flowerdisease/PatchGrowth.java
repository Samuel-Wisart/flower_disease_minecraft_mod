package com.iridium.flowerdisease;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.MultifaceSpreader;
import net.minecraft.world.level.block.state.BlockState;

// How a creeping flower grows a patch around itself instead of standing alone (see PLANNING_STAGE2.md, "Creeper em
// mancha"). A creeper that is planted or born draws an ENERGY, 0 to patchMaxEnergy, with the middle values the most
// likely (1, 2, 3, 3, 2, 1 out of 12 for the default 5) - so a piece with no patch at all is rare, about 1 in 12, and
// most are middling. On its random ticks a piece that still has energy grows one more piece onto a neighbouring surface
// the way vanilla Glow Lichen does (MultifaceSpreader: another face of the same block, the same wall, around an edge),
// and the new piece has one energy less. That makes the patch a flood of surface out to as many pieces away as the seed's
// energy, in random order, and it takes about a day (of random ticks) to fill in.
//
// Density is ignored on purpose, and the growth chance is its own knob: a patch is one creeper's body, not a
// population. The pieces are not plants of the lineage and do not reproduce (see CreeperBlockEntity).
final class PatchGrowth {

    private PatchGrowth() {
    }

    // Weights rise then fall: min(energy + 1, max - energy + 1).
    static int rollEnergy(RandomSource random) {
        int max = Config.PATCH_MAX_ENERGY.getAsInt();
        if (max <= 0) {
            return 0;
        }

        int total = 0;
        for (int energy = 0; energy <= max; energy++) {
            total += weight(energy, max);
        }

        int roll = random.nextInt(total);
        for (int energy = 0; energy <= max; energy++) {
            roll -= weight(energy, max);
            if (roll < 0) {
                return energy;
            }
        }
        return max;
    }

    static int weight(int energy, int max) {
        return Math.min(energy + 1, max - energy + 1);
    }

    // One growth step of a piece that still has energy: with the configured chance it grows one more piece. Its part in
    // the patch is over when it finds nowhere at all to grow, and otherwise after each new piece with the chance that the
    // patch is not "full" (patchFill) - which is what leaves ragged, organic patches instead of a solid diamond.
    static void grow(ServerLevel level, BlockPos pos, BlockState state, CreeperBlockEntity piece, RandomSource random) {
        if (random.nextDouble() >= Config.PATCH_GROWTH_CHANCE.getAsDouble()) {
            return;
        }

        MultifaceSpreader spreader = new MultifaceSpreader(new Spreader((MultifaceBlock) state.getBlock(), piece.energy() - 1, piece.garden()));
        boolean grew = spreader.spreadFromRandomFaceTowardRandomDirection(state, level, pos, random).isPresent();
        if (!grew || random.nextDouble() >= Config.PATCH_FILL.getAsDouble()) {
            piece.setEnergy(0);
        }
    }

    // Vanilla's rules for where a multiface block may spread, with the placement done our way: no neighbour updates, and
    // the new piece is given its energy - or, with none left, is born already settled and never gets a block entity.
    private static final class Spreader extends MultifaceSpreader.DefaultSpreaderConfig {
        private final int childEnergy;
        private final int garden;

        Spreader(MultifaceBlock block, int childEnergy, int garden) {
            super(block);
            this.childEnergy = childEnergy;
            this.garden = garden;
        }

        // `current` is what is at the destination now (air, or a creeper block that lacks this face) - the state to place
        // is the one vanilla works out from it.
        @Override
        public boolean placeBlock(LevelAccessor level, MultifaceSpreader.SpreadPos pos, BlockState current, boolean markForPostprocessing) {
            BlockState grown = getStateForPlacement(current, level, pos.pos(), pos.face());
            if (grown == null) {
                return false;
            }

            // Growing onto another face of a block that already exists leaves that block's own energy alone.
            boolean newPiece = !current.is(block);
            BlockState placed = newPiece && childEnergy <= 0 ? grown.setValue(SettleTable.SETTLED, true) : grown;
            if (!level.setBlock(pos.pos(), placed, SettleTable.PLACEMENT_FLAGS)) {
                return false;
            }

            if (newPiece && childEnergy > 0 && level.getBlockEntity(pos.pos()) instanceof CreeperBlockEntity piece) {
                piece.startPiece(childEnergy, garden);
            }
            return true;
        }
    }
}
