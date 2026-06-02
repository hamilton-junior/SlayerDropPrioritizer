package net.runelite.client.plugins.slayerdropprioritizer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup("slayerdropprioritizer")
public interface SlayerDropPrioritizerConfig extends Config {

    @ConfigItem(keyName = "enableDeprioritization", name = "Enable Deprioritization", description = "Moves non-drop table items to the bottom of the menu.", position = 1)
    default boolean enableDeprioritization() {
        return true;
    }

    @ConfigItem(keyName = "prioritizationMode", name = "Prioritization Mode", description = "Controls which task drops are prioritized", position = 2)
    default PrioritizationMode prioritizationMode() {
        return PrioritizationMode.ALL_TASK_DROPS;
    }

    @Range(min = 0, max = 2147483647)
    @ConfigItem(keyName = "minimumPriorityValue", name = "Minimum Priority Value", description = "Minimum GE value to prioritize any item (task drops + non-task valuable items)", position = 3)
    default int minimumPriorityValue() {
        return 0;
    }

    @ConfigItem(keyName = "dropDisplayMode", name = "Non-task drops", description = "How non-task drops should be handled", position = 4)
    default DropDisplayMode dropDisplayMode() {
        return DropDisplayMode.DEPRIORITIZE;
    }

    @ConfigItem(keyName = "prioritizeExamine", name = "Prioritize Examine", description = "Move Examine entries together with Take entries", position = 5)
    default boolean prioritizeExamine() {
        return true;
    }

    @Range(min = 1, max = 500)
    @ConfigItem(keyName = "combatTimeout", name = "Combat timeout", description = "Ticks after combat before prioritization stops", position = 6)
    default int combatTimeout() {
        return 50;
    }

    @ConfigSection(name = "Debug", description = "Debug and testing options", position = 1000)
    String debugSection = "debugSection";

    @ConfigItem(keyName = "testMode", name = "Test Mode", description = "Ignores the official task.", section = debugSection, position = 1001)
    default boolean testMode() {
        return false;
    }

    @ConfigItem(keyName = "testMonsterName", name = "Test Monster Name", description = "Monster name for testing.", section = debugSection, position = 1002)
    default String testMonsterName() {
        return "Goblin";
    }

    @ConfigItem(keyName = "supportCollapsedItems", name = "Support collapsed ground items", description = "Recognizes items grouped by Ground Items plugin", section = debugSection, position = 1003)
    default boolean supportCollapsedItems() {
        return true;
    }
}