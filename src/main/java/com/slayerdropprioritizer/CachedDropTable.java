package com.slayerdropprioritizer;

import java.util.Map;
import java.util.Set;

/**
 * On-disk representation of a resolved NPC drop table.
 * Serialized as JSON via Gson under .runelite/slayer-drop-prioritizer/.
 *
 * Rarity values are stored as the effective denominator N (i.e. a 1/N chance), so a
 * Wiki rarity of "3/128" is stored as ~42.67 rather than 128.
 */
class CachedDropTable {
    /** Bumped whenever the parsing logic changes so older caches are discarded. */
    static final int CURRENT_VERSION = 2;

    int version;
    long timestamp;
    Set<String> drops;
    Map<String, Double> rarities;

    CachedDropTable() {
    }

    CachedDropTable(int version, long timestamp, Set<String> drops, Map<String, Double> rarities) {
        this.version = version;
        this.timestamp = timestamp;
        this.drops = drops;
        this.rarities = rarities;
    }
}
