package net.runelite.client.plugins.slayerdropprioritizer;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum PriorityMarker {
    NONE(""),
    STAR("★"),
    DIAMOND("♦"),
    ARROW("►");

    private final String symbol;
}
