package com.iridium.flowerdisease;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;

// What the creeper patches in some part of the world look like, as numbers: how big they are and how elongated. A patch here is a
// group of creeper blocks that touch each other, corners included, so two patches that grew into one another count as one - which
// is also what shows when patchCrowding lets them merge into a mat. Pure geometry over a list of positions, so it can be worked out
// (and checked) without a world; GardenStats collects the positions.
final class PatchStats {
    // A patch has to have this many pieces before its shape means anything.
    static final int MIN_PIECES_FOR_SHAPE = 4;
    // The elongation (see aspect) under which a patch counts as round.
    static final double ROUND_BELOW = 1.4;
    // What one block's own extent adds to each axis' variance (a unit cube: 1/12), so that a lone piece is not infinitely thin.
    private static final double CELL_VARIANCE = 1.0 / 12.0;

    final int patches;
    final int pieces;
    // Sizes of the patches, smallest first.
    final int[] sizes;
    // How many patches were big enough for their shape to count, and how many of those are round; and the median elongation.
    final int shaped;
    final int round;
    final double medianAspect;

    private PatchStats(int patches, int pieces, int[] sizes, int shaped, int round, double medianAspect) {
        this.patches = patches;
        this.pieces = pieces;
        this.sizes = sizes;
        this.shaped = shaped;
        this.round = round;
        this.medianAspect = medianAspect;
    }

    // The patches among `cells` (block positions as longs, see BlockPos#asLong).
    static PatchStats of(LongArrayList cells) {
        LongOpenHashSet remaining = new LongOpenHashSet(cells);
        List<Integer> sizeList = new ArrayList<>();
        List<Double> aspects = new ArrayList<>();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        LongArrayList stack = new LongArrayList();
        for (long start : cells) {
            if (!remaining.remove(start)) {
                continue;
            }

            LongArrayList members = new LongArrayList();
            stack.add(start);
            while (!stack.isEmpty()) {
                long current = stack.removeLong(stack.size() - 1);
                members.add(current);
                BlockPos at = BlockPos.of(current);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if ((dx != 0 || dy != 0 || dz != 0) && remaining.remove(cursor.setWithOffset(at, dx, dy, dz).asLong())) {
                                stack.add(cursor.asLong());
                            }
                        }
                    }
                }
            }

            sizeList.add(members.size());
            if (members.size() >= MIN_PIECES_FOR_SHAPE) {
                aspects.add(aspect(members));
            }
        }

        int[] sizes = sizeList.stream().mapToInt(Integer::intValue).sorted().toArray();
        double[] sortedAspects = aspects.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int round = (int) Arrays.stream(sortedAspects).filter(aspect -> aspect < ROUND_BELOW).count();
        double median = sortedAspects.length == 0 ? 0.0 : sortedAspects[sortedAspects.length / 2];
        return new PatchStats(sizes.length, cells.size(), sizes, sortedAspects.length, round, median);
    }

    // How elongated a group of blocks is: the ratio of the standard deviations along its two longest principal axes (a round patch
    // is about 1, a straight line of pieces is large). Taken from the two longest of the three because a patch is a sheet lying
    // over a surface - along a wall, around a corner - and its thickness says nothing about its shape.
    static double aspect(LongArrayList members) {
        int n = members.size();
        double mx = 0.0;
        double my = 0.0;
        double mz = 0.0;
        for (long member : members) {
            mx += BlockPos.getX(member);
            my += BlockPos.getY(member);
            mz += BlockPos.getZ(member);
        }
        mx /= n;
        my /= n;
        mz /= n;

        double xx = 0.0;
        double yy = 0.0;
        double zz = 0.0;
        double xy = 0.0;
        double xz = 0.0;
        double yz = 0.0;
        for (long member : members) {
            double dx = BlockPos.getX(member) - mx;
            double dy = BlockPos.getY(member) - my;
            double dz = BlockPos.getZ(member) - mz;
            xx += dx * dx;
            yy += dy * dy;
            zz += dz * dz;
            xy += dx * dy;
            xz += dx * dz;
            yz += dy * dz;
        }

        double[] eigenvalues = eigenvalues(xx / n + CELL_VARIANCE, yy / n + CELL_VARIANCE, zz / n + CELL_VARIANCE, xy / n, xz / n, yz / n);
        return Math.sqrt(eigenvalues[0] / Math.max(eigenvalues[1], 1.0e-9));
    }

    // The eigenvalues of a symmetric 3x3 matrix, largest first (the closed form, no iteration).
    static double[] eigenvalues(double a11, double a22, double a33, double a12, double a13, double a23) {
        double offDiagonal = a12 * a12 + a13 * a13 + a23 * a23;
        if (offDiagonal < 1.0e-12) {
            double[] diagonal = {a11, a22, a33};
            Arrays.sort(diagonal);
            return new double[]{diagonal[2], diagonal[1], diagonal[0]};
        }

        double q = (a11 + a22 + a33) / 3.0;
        double p2 = (a11 - q) * (a11 - q) + (a22 - q) * (a22 - q) + (a33 - q) * (a33 - q) + 2.0 * offDiagonal;
        double p = Math.sqrt(p2 / 6.0);
        double b11 = (a11 - q) / p;
        double b22 = (a22 - q) / p;
        double b33 = (a33 - q) / p;
        double b12 = a12 / p;
        double b13 = a13 / p;
        double b23 = a23 / p;
        double determinant = b11 * (b22 * b33 - b23 * b23) - b12 * (b12 * b33 - b23 * b13) + b13 * (b12 * b23 - b22 * b13);
        double r = Math.max(-1.0, Math.min(1.0, determinant / 2.0));
        double phi = Math.acos(r) / 3.0;

        double largest = q + 2.0 * p * Math.cos(phi);
        double smallest = q + 2.0 * p * Math.cos(phi + 2.0 * Math.PI / 3.0);
        return new double[]{largest, 3.0 * q - largest - smallest, smallest};
    }

    double meanSize() {
        return patches == 0 ? 0.0 : (double) pieces / patches;
    }

    // The size that `fraction` of the patches do not exceed.
    int sizeAt(double fraction) {
        return sizes.length == 0 ? 0 : sizes[Math.min(sizes.length - 1, (int) (fraction * sizes.length))];
    }

    int biggest() {
        return sizes.length == 0 ? 0 : sizes[sizes.length - 1];
    }

    // Lines for chat.
    List<String> describe() {
        if (patches == 0) {
            return List.of();
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "  creeper patches: %d (%d pieces) - size mean %.1f, median %d, 90%% have at most %d, biggest %d",
                patches, pieces, meanSize(), sizeAt(0.5), sizeAt(0.9), biggest()));
        if (shaped > 0) {
            lines.add(String.format(Locale.ROOT, "  patch shape: %d of the %d patches with %d pieces or more are round (elongation under %.1f), median elongation %.1f",
                    round, shaped, MIN_PIECES_FOR_SHAPE, ROUND_BELOW, medianAspect));
        }
        return lines;
    }
}
