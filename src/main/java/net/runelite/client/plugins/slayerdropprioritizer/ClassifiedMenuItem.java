package net.runelite.client.plugins.slayerdropprioritizer;

import lombok.Value;
import net.runelite.api.MenuEntry;

/**
 * Represents a ground item in the menu, classified as priority or not.
 */
@Value
public class ClassifiedMenuItem {
    MenuEntry entry;
    int originalIndex;
    String normalizedItemName;
    String normalizedOption;
    boolean isPriority;
    int itemId;
}
