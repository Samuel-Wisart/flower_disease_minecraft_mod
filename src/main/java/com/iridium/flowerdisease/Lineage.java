package com.iridium.flowerdisease;

// What a plant reads from its block entity every time it acts: the garden it belongs to, that garden's profile, and how
// deep in the lineage it is (0-based: 0 is the flower the Garden Bag planted, so a plant's generation is depth + 1).
// Immutable, so a child's is simply its parent's one step deeper.
record Lineage(int garden, GardenBagContents profile, long depth) {
    // A plant nothing configured: no garden, the server defaults for everything.
    static final Lineage NONE = new Lineage(GardenRegistry.NO_GARDEN, GardenBagContents.DEFAULT, 0);

    Lineage child() {
        return new Lineage(garden, profile, depth + 1);
    }
}
