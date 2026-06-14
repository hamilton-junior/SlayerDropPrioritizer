package net.runelite.client.plugins.slayerdropprioritizer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("slayerdropprioritizer")
public interface SlayerDropPrioritizerConfig extends Config {

    // ─── Main Options ────────────────────────────────────────────────────────

    @ConfigItem(keyName = "enableDeprioritization", name = "Enable", description = "Master on/off switch for all menu modifications.", position = 1)
    default boolean enableDeprioritization() {
        return true;
    }

    @ConfigItem(keyName = "dropDisplayMode", name = "Display Mode", description = "How non-task drops should be handled in the menu.", position = 2)
    default DropDisplayMode dropDisplayMode() {
        return DropDisplayMode.DEPRIORITIZE;
    }

    @ConfigItem(keyName = "prioritizationMode", name = "Prioritization Mode", description = "Controls which task drops are treated as priority.", position = 3)
    default PrioritizationMode prioritizationMode() {
        return PrioritizationMode.ALL_TASK_DROPS;
    }

    @Range(min = 0, max = 2147483647)
    @ConfigItem(keyName = "minimumPriorityValue", name = "Minimum Priority Value", description = "Minimum item value to be treated as priority (0 = disabled). Applies to all items, not just task drops.", position = 4)
    default int minimumPriorityValue() {
        return 0;
    }

    @ConfigItem(keyName = "priorityValueSource", name = "Priority Value Source", description = "Which price to use when checking the minimum value threshold.", position = 5)
    default PriorityValueSource priorityValueSource() {
        return PriorityValueSource.GE_ONLY;
    }

    @ConfigItem(keyName = "prioritizeExamine", name = "Prioritize Examine", description = "Move Examine entries together with Take entries for priority items.", position = 6)
    default boolean prioritizeExamine() {
        return true;
    }

    @Range(min = 1, max = 500)
    @ConfigItem(keyName = "combatTimeout", name = "Combat Timeout", description = "Ticks after combat ends before menu modifications stop.", position = 7)
    default int combatTimeout() {
        return 50;
    }

    // ─── Display Section ─────────────────────────────────────────────────────

    @ConfigSection(name = "Display", description = "Visual markers and value annotations inside the menu.", position = 10)
    String displaySection = "displaySection";

    @ConfigItem(keyName = "enablePriorityMarker", name = "Enable Priority Marker", description = "Prepends a symbol to priority item names in the right-click menu.", section = displaySection, position = 11)
    default boolean enablePriorityMarker() {
        return false;
    }

    @ConfigItem(keyName = "priorityMarker", name = "Marker Style", description = "Symbol shown before priority item names.", section = displaySection, position = 12)
    default PriorityMarker priorityMarker() {
        return PriorityMarker.STAR;
    }

    @ConfigItem(keyName = "showItemValueInMenu", name = "Show Item Value", description = "Appends the item value next to the name in the right-click menu.", section = displaySection, position = 13)
    default boolean showItemValueInMenu() {
        return false;
    }

    @ConfigItem(keyName = "itemValueDisplay", name = "Value Source", description = "Which price to display next to item names (GE or High Alchemy).", section = displaySection, position = 14)
    default ItemValueDisplay itemValueDisplay() {
        return ItemValueDisplay.GE;
    }

    // ─── Clue Scrolls Section ────────────────────────────────────────────────

    @ConfigSection(name = "Clue Scrolls", description = "Special handling for clue scroll drops.", position = 20)
    String clueSection = "clueSection";

    @ConfigItem(keyName = "enableCluePriority", name = "Enable Clue Scroll Priority", description = "Always prioritizes clue scrolls regardless of current task. Clue scrolls are never hidden in HIDE mode.", section = clueSection, position = 21)
    default boolean enableCluePriority() {
        return false;
    }

    // ─── Drop Rarity Section ─────────────────────────────────────────────────

    @ConfigSection(name = "Drop Rarity", description = "Prioritize task drops based on their OSRS Wiki rarity.", position = 30)
    String raritySection = "raritySection";

    @ConfigItem(keyName = "enableRarePriority", name = "Enable Rare Drop Priority", description = "Treats task drops with a sufficiently rare Wiki drop rate as priority.", section = raritySection, position = 31)
    default boolean enableRarePriority() {
        return false;
    }

    @Range(min = 1, max = 100000)
    @ConfigItem(keyName = "maxRareDenominator", name = "Max Rarity Denominator", description = "Items dropped at 1/N or better (N at most this value) are prioritized. Example: 512 treats anything 1/512 or rarer as priority.", section = raritySection, position = 32)
    default int maxRareDenominator() {
        return 512;
    }

    // ─── Custom Lists Section ────────────────────────────────────────────────

    @ConfigSection(name = "Custom Lists", description = "Override prioritization with custom per-item rules.", position = 40)
    String listsSection = "listsSection";

    @ConfigItem(keyName = "alwaysPriorityItems", name = "Always Priority Items", description = "Items always treated as priority regardless of task, value, or rarity. One item name per line.", section = listsSection, position = 41)
    default String alwaysPriorityItems() {
        return "";
    }

    @ConfigItem(keyName = "alwaysIgnoreItems", name = "Always Ignore Items", description = "Items never treated as priority. In HIDE mode they are also removed. One item name per line.", section = listsSection, position = 42)
    default String alwaysIgnoreItems() {
        return "";
    }

    // ─── Interesting Drops Section ───────────────────────────────────────────

    @ConfigSection(name = "Interesting Drops", description = "Filter which task drops qualify as worth prioritizing.", position = 50)
    String interestingSection = "interestingSection";

    @ConfigItem(keyName = "interestingDropsOnly", name = "Interesting Drops Only", description = "Being in the task drop table alone is not enough. Items must also meet the minimum value or rarity criteria to be priority.", section = interestingSection, position = 51)
    default boolean interestingDropsOnly() {
        return false;
    }

    // ─── Debug Section ───────────────────────────────────────────────────────

    @ConfigSection(name = "Debug", description = "Debug and testing options.", position = 1000)
    String debugSection = "debugSection";

    @ConfigItem(keyName = "testMode", name = "Test Mode", description = "Ignores the official Slayer task and uses the monster name below instead.", section = debugSection, position = 1001)
    default boolean testMode() {
        return false;
    }

    @ConfigItem(keyName = "testMonsterName", name = "Test Monster Name", description = "Monster name used when Test Mode is enabled.", section = debugSection, position = 1002)
    default String testMonsterName() {
        return "Goblin";
    }

    @ConfigItem(keyName = "supportCollapsedItems", name = "Support Collapsed Ground Items", description = "Recognizes items grouped by the Ground Items plugin (e.g. 'Dragon bones x 5').", section = debugSection, position = 1003)
    default boolean supportCollapsedItems() {
        return true;
    }
}
