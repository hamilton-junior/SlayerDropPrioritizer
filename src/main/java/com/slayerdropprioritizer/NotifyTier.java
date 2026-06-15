package com.slayerdropprioritizer;

/**
 * Minimum tier that triggers a priority-drop notification.
 */
public enum NotifyTier {
    /** Notify for any priority drop (task, value, rarity, clue, custom list). */
    ALL_PRIORITY,
    /** Notify only for drops in the Rare or Ultra-Rare rarity tier. */
    RARE,
    /** Notify only for drops in the Ultra-Rare rarity tier. */
    ULTRA_RARE
}
