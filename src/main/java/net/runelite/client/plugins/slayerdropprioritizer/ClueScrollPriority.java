package net.runelite.client.plugins.slayerdropprioritizer;

/**
 * Controls where clue scrolls are placed relative to other priority task drops.
 */
public enum ClueScrollPriority {
    /** Above the other priority task drops (very top of the menu). */
    BEFORE,
    /** Below the other priority task drops, but still above non-priority items. */
    AFTER,
    /** No special placement; follows the "Show clue scrolls" option and normal classification. */
    OFF
}
