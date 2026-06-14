package net.runelite.client.plugins.slayerdropprioritizer;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("slayerdropprioritizer")
public interface SlayerDropPrioritizerConfig extends Config {

    // ═══ Master toggle (ungrouped, always visible at the top) ════════════════

    @ConfigItem(keyName = "enableDeprioritization", name = "Enable", description = "Master on/off switch for all menu modifications.", position = 1)
    default boolean enableDeprioritization() {
        return true;
    }

    // ═══ General ═════════════════════════════════════════════════════════════

    @ConfigSection(name = "General", description = "Core behavior: how the menu is changed and for how long.", position = 10)
    String generalSection = "generalSection";

    @ConfigItem(keyName = "dropDisplayMode", name = "Display Mode", description = "How non-priority drops are handled in the menu.", section = generalSection, position = 11)
    default DropDisplayMode dropDisplayMode() {
        return DropDisplayMode.DEPRIORITIZE;
    }

    @ConfigItem(keyName = "prioritizeExamine", name = "Prioritize Examine", description = "Move Examine entries together with Take entries for priority items.", section = generalSection, position = 12)
    default boolean prioritizeExamine() {
        return true;
    }

    @Range(min = 0, max = 600)
    @ConfigItem(keyName = "combatTimeoutSeconds", name = "Combat Timeout (s)", description = "Seconds after combat ends before menu modifications stop. 0 = keep active until the Slayer task ends.", section = generalSection, position = 13)
    default int combatTimeoutSeconds() {
        return 30;
    }

    // ═══ Prioritization (value-based) ════════════════════════════════════════

    @ConfigSection(name = "Prioritization", description = "Which drops count as priority, by task membership and value.", position = 20)
    String prioritizationSection = "prioritizationSection";

    @ConfigItem(keyName = "prioritizationMode", name = "Task Drops", description = "Which task drops are treated as priority.", section = prioritizationSection, position = 21)
    default PrioritizationMode prioritizationMode() {
        return PrioritizationMode.ALL_TASK_DROPS;
    }

    @Range(min = 0, max = 2147483647)
    @ConfigItem(keyName = "minimumPriorityValue", name = "Minimum Value", description = "Minimum item value to be treated as priority (0 = disabled). Applies to any item, not just task drops.", section = prioritizationSection, position = 22)
    default int minimumPriorityValue() {
        return 0;
    }

    @ConfigItem(keyName = "priorityValueSource", name = "Value Source", description = "Which price to use when checking the Minimum Value threshold.", section = prioritizationSection, position = 23)
    default PriorityValueSource priorityValueSource() {
        return PriorityValueSource.GE_ONLY;
    }

    @ConfigItem(keyName = "interestingDropsOnly", name = "Interesting Drops Only", description = "Being in the task drop table alone is not enough — items must also meet the Minimum Value or a rarity tier to be priority.", section = prioritizationSection, position = 24)
    default boolean interestingDropsOnly() {
        return false;
    }

    // ═══ Drop Rarity (Wiki-based tiers) ══════════════════════════════════════

    @ConfigSection(name = "Drop Rarity", description = "Classify and prioritize task drops by their OSRS Wiki drop rate.", position = 30)
    String raritySection = "raritySection";

    @ConfigItem(keyName = "enableRarePriority", name = "Rare Drops Are Priority", description = "Treats task drops in the Rare or Ultra-Rare tier as priority.", section = raritySection, position = 31)
    default boolean enableRarePriority() {
        return false;
    }

    @Range(min = 1, max = 1000000)
    @ConfigItem(keyName = "rareThreshold", name = "Rare: 1 / N", description = "An item dropped at 1/N or rarer is at least 'Rare'. Example: 512 means 1/512, 1/1000, 1/5000 ... all count as Rare.", section = raritySection, position = 32)
    default int rareThreshold() {
        return 512;
    }

    @Range(min = 1, max = 1000000)
    @ConfigItem(keyName = "ultraRareThreshold", name = "Ultra-Rare: 1 / N", description = "An item dropped at 1/N or rarer is 'Ultra-Rare'. Should be larger than the Rare threshold.", section = raritySection, position = 33)
    default int ultraRareThreshold() {
        return 2500;
    }

    // ═══ Clue Scrolls ════════════════════════════════════════════════════════

    @ConfigSection(name = "Clue Scrolls", description = "Special handling for clue scroll drops.", position = 40, closedByDefault = true)
    String clueSection = "clueSection";

    @ConfigItem(keyName = "showClueScrolls", name = "Show Clue Scrolls", description = "Always: clue scrolls are never hidden. Mode: they follow the Display Mode. Never: they are always hidden in HIDE modes.", section = clueSection, position = 41)
    default ClueScrollDisplay showClueScrolls() {
        return ClueScrollDisplay.MODE;
    }

    @ConfigItem(keyName = "prioritizeClueScrolls", name = "Prioritize Clue Scrolls", description = "Before: above the other priority drops. After: below them. Off: no special placement (follows Show Clue Scrolls).", section = clueSection, position = 42)
    default ClueScrollPriority prioritizeClueScrolls() {
        return ClueScrollPriority.OFF;
    }

    // ═══ Custom Lists ════════════════════════════════════════════════════════

    @ConfigSection(name = "Custom Lists", description = "Per-item overrides that win over every other rule.", position = 50, closedByDefault = true)
    String listsSection = "listsSection";

    @ConfigItem(keyName = "alwaysPriorityItems", name = "Always Priority", description = "Items always treated as priority regardless of task, value, or rarity. One item name per line.", section = listsSection, position = 51)
    default String alwaysPriorityItems() {
        return "";
    }

    @ConfigItem(keyName = "alwaysIgnoreItems", name = "Always Ignore", description = "Items never treated as priority. In HIDE mode they are also removed. One item name per line.", section = listsSection, position = 52)
    default String alwaysIgnoreItems() {
        return "";
    }

    // ═══ Display (menu cosmetics) ════════════════════════════════════════════

    @ConfigSection(name = "Display", description = "Visual markers, value annotations and highlight colors inside the menu.", position = 60, closedByDefault = true)
    String displaySection = "displaySection";

    @ConfigItem(keyName = "enablePriorityMarker", name = "Priority Marker", description = "Prepends a symbol to priority item names in the right-click menu.", section = displaySection, position = 61)
    default boolean enablePriorityMarker() {
        return false;
    }

    @ConfigItem(keyName = "priorityMarker", name = "Marker Style", description = "Symbol shown before priority item names.", section = displaySection, position = 62)
    default PriorityMarker priorityMarker() {
        return PriorityMarker.STAR;
    }

    @ConfigItem(keyName = "showItemValueInMenu", name = "Show Item Value", description = "Appends the item value next to the name in the right-click menu.", section = displaySection, position = 63)
    default boolean showItemValueInMenu() {
        return false;
    }

    @ConfigItem(keyName = "itemValueDisplay", name = "Displayed Value", description = "Which price to display next to item names (GE or High Alchemy).", section = displaySection, position = 64)
    default ItemValueDisplay itemValueDisplay() {
        return ItemValueDisplay.GE;
    }

    @ConfigItem(keyName = "highlightTaskItems", name = "Highlight Priority Items", description = "Recolors priority item names in the right-click menu.", section = displaySection, position = 65)
    default boolean highlightTaskItems() {
        return false;
    }

    @ConfigItem(keyName = "highlightAboveValueOnly", name = "Highlight Above Value Only", description = "Only highlight priority items whose value is at or above the Minimum Value.", section = displaySection, position = 66)
    default boolean highlightAboveValueOnly() {
        return false;
    }

    @Alpha
    @ConfigItem(keyName = "highlightColor", name = "Highlight Color", description = "Base highlight color (also used as the 'common' tier color).", section = displaySection, position = 67)
    default Color highlightColor() {
        return new Color(0x00FF00);
    }

    @ConfigItem(keyName = "highlightByTier", name = "Tiered Highlight Colors", description = "Color priority items by tier: Ultra-Rare, Rare, Valuable, then common — using the colors below.", section = displaySection, position = 68)
    default boolean highlightByTier() {
        return false;
    }

    @Alpha
    @ConfigItem(keyName = "highlightColorRare", name = "Tier Color: Rare", description = "Color for items in the Rare tier (uses the Drop Rarity thresholds). Requires Tiered Highlight Colors.", section = displaySection, position = 69)
    default Color highlightColorRare() {
        return new Color(0xFF4040);
    }

    @Alpha
    @ConfigItem(keyName = "highlightColorUltraRare", name = "Tier Color: Ultra-Rare", description = "Color for items in the Ultra-Rare tier (uses the Drop Rarity thresholds). Requires Tiered Highlight Colors.", section = displaySection, position = 70)
    default Color highlightColorUltraRare() {
        return new Color(0xAA00FF);
    }

    @Alpha
    @ConfigItem(keyName = "highlightColorValuable", name = "Tier Color: Valuable", description = "Color for items at or above the Minimum Value (when not in a rarity tier). Requires Tiered Highlight Colors.", section = displaySection, position = 71)
    default Color highlightColorValuable() {
        return new Color(0xFFA500);
    }

    // ═══ Notifications ═══════════════════════════════════════════════════════

    @ConfigSection(name = "Notifications", description = "Alerts when priority drops land on the ground.", position = 70, closedByDefault = true)
    String notificationsSection = "notificationsSection";

    @ConfigItem(keyName = "notifyPriorityDrops", name = "Notify on Priority Drop", description = "Fires a RuneLite notification when a priority item spawns on the ground while on task.", section = notificationsSection, position = 71)
    default boolean notifyPriorityDrops() {
        return false;
    }

    // ═══ Compatibility & Debug ═══════════════════════════════════════════════

    @ConfigSection(name = "Compatibility & Debug", description = "Interop with other plugins, caching, and testing options.", position = 1000, closedByDefault = true)
    String debugSection = "debugSection";

    @ConfigItem(keyName = "supportCollapsedItems", name = "Support Collapsed Ground Items", description = "Recognizes items grouped by the Ground Items plugin (e.g. 'Dragon bones x 5').", section = debugSection, position = 1001)
    default boolean supportCollapsedItems() {
        return true;
    }

    @ConfigItem(keyName = "cacheDropTables", name = "Cache Drop Tables", description = "Stores resolved drop tables on disk (.runelite/slayer-drop-prioritizer) so the Wiki is not queried every session.", section = debugSection, position = 1002)
    default boolean cacheDropTables() {
        return true;
    }

    @ConfigItem(keyName = "showDebugOverlay", name = "Show Debug Overlay", description = "Shows the diagnostic info box (task, NPC, drop count, combat state).", section = debugSection, position = 1003)
    default boolean showDebugOverlay() {
        return false;
    }

    @ConfigItem(keyName = "testMode", name = "Test Mode", description = "Ignores the official Slayer task and uses the monster name below instead.", section = debugSection, position = 1004)
    default boolean testMode() {
        return false;
    }

    @ConfigItem(keyName = "testMonsterName", name = "Test Monster Name", description = "Monster name used when Test Mode is enabled.", section = debugSection, position = 1005)
    default String testMonsterName() {
        return "Goblin";
    }
}
