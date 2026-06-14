package net.runelite.client.plugins.slayerdropprioritizer;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Prefix symbols shown before priority item names in the menu.
 * Only plain ASCII is used because the RuneScape menu font does not render
 * many Unicode glyphs (e.g. ★ ♦ ►).
 */
@AllArgsConstructor
@Getter
public enum PriorityMarker {
    NONE(""),
    STAR("*"),
    ARROW(">"),
    PLUS("+"),
    DASH("-");

    private final String symbol;
}
