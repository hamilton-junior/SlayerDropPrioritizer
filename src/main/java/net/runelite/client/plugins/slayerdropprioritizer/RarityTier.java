package net.runelite.client.plugins.slayerdropprioritizer;

/**
 * Rarity classification of a task drop, derived from its OSRS Wiki drop rate and the
 * user-configured thresholds (see {@link SlayerDropPrioritizerConfig#rareThreshold()} and
 * {@link SlayerDropPrioritizerConfig#ultraRareThreshold()}).
 */
public enum RarityTier {
    COMMON,
    RARE,
    ULTRA_RARE
}
