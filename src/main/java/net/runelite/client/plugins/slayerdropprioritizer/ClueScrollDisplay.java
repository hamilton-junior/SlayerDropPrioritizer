package net.runelite.client.plugins.slayerdropprioritizer;

/**
 * Controls whether clue scrolls may be hidden.
 */
public enum ClueScrollDisplay {
    /** Clue scrolls are never hidden, regardless of the display mode. */
    ALWAYS,
    /** Clue scrolls follow the normal display mode rules. */
    MODE,
    /** Clue scrolls are always hidden in HIDE modes. */
    NEVER
}
