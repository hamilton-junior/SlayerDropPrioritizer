/*
 * Copyright (c) 2026, Mystery Gift
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.slayerdropprioritizer;

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

    @ConfigItem(keyName = "prioritizeUntradeables", name = "Prioritize Untradeables", description = "Treat untradeable items as priority. Useful for valuable Slayer drops that have no GE price (imbue scrolls, totem pieces, etc.).", section = prioritizationSection, position = 25)
    default boolean prioritizeUntradeables() {
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

    @ConfigItem(keyName = "addManageMenuEntries", name = "Right-click Manage", description = "Adds 'Prioritize'/'Ignore' options to ground item right-click menus that add or remove the item from these lists.", section = listsSection, position = 53)
    default boolean addManageMenuEntries() {
        return false;
    }

    // ═══ Menu Text (marker, value, rarity) ═══════════════════════════════════

    @ConfigSection(name = "Menu Text", description = "Marker, value and rarity text added next to item names.", position = 60, closedByDefault = true)
    String menuTextSection = "menuTextSection";

    @ConfigItem(keyName = "enablePriorityMarker", name = "Priority Marker", description = "Prepends a symbol or text to priority items in the right-click menu.", section = menuTextSection, position = 61)
    default boolean enablePriorityMarker() {
        return false;
    }

    @ConfigItem(keyName = "markerText", name = "Marker Text", description = "Text/symbol shown before priority item names (e.g. '*', '>>', '[!]'). The menu font does not render most Unicode glyphs, so plain ASCII works best.", section = menuTextSection, position = 62)
    default String markerText() {
        return "*";
    }

    @ConfigItem(keyName = "colorMarker", name = "Color Marker", description = "Color the marker text with the Marker Color (otherwise it uses the default menu color).", section = menuTextSection, position = 63)
    default boolean colorMarker() {
        return false;
    }

    @Alpha
    @ConfigItem(keyName = "markerColor", name = "Marker Color", description = "Color of the marker text. Independent from the highlight color.", section = menuTextSection, position = 64)
    default Color markerColor() {
        return new Color(0xFFFF00);
    }

    @ConfigItem(keyName = "showItemValueInMenu", name = "Show Item Value", description = "Appends the item value next to the name in the right-click menu.", section = menuTextSection, position = 65)
    default boolean showItemValueInMenu() {
        return false;
    }

    @ConfigItem(keyName = "itemValueDisplay", name = "Displayed Value", description = "Which price to display next to item names (GE or High Alchemy).", section = menuTextSection, position = 66)
    default ItemValueDisplay itemValueDisplay() {
        return ItemValueDisplay.GE;
    }

    @ConfigItem(keyName = "showRarityInMenu", name = "Show Item Rarity", description = "Appends the Wiki drop rate next to the name (e.g. '(1/512)') for task drops with a known rarity.", section = menuTextSection, position = 67)
    default boolean showRarityInMenu() {
        return false;
    }

    @ConfigItem(keyName = "useGroundItemsColors", name = "Use Ground Items Colors", description = "Color the value/rarity text using the official Ground Items plugin's value-tier colors. Turn off to use the custom colors below.", section = menuTextSection, position = 68)
    default boolean useGroundItemsColors() {
        return true;
    }

    @Alpha
    @ConfigItem(keyName = "valueColor", name = "Custom Value Color", description = "Color of the value text (used only when 'Use Ground Items Colors' is off).", section = menuTextSection, position = 69)
    default Color valueColor() {
        return new Color(0xAAAAAA);
    }

    @Alpha
    @ConfigItem(keyName = "rarityColor", name = "Custom Rarity Color", description = "Color of the rarity text (used only when 'Use Ground Items Colors' is off).", section = menuTextSection, position = 70)
    default Color rarityColor() {
        return new Color(0x9090FF);
    }

    // ═══ Highlight (recolor item names) ══════════════════════════════════════

    @ConfigSection(name = "Highlight", description = "Recolor priority items directly in the menu.", position = 65, closedByDefault = true)
    String highlightSection = "highlightSection";

    @ConfigItem(keyName = "highlightTaskItems", name = "Highlight Priority Items", description = "Recolors priority items in the right-click menu.", section = highlightSection, position = 71)
    default boolean highlightTaskItems() {
        return false;
    }

    @ConfigItem(keyName = "highlightPart", name = "Highlight Part", description = "Which part of the entry to color: the option (Take), the item name, or both.", section = highlightSection, position = 72)
    default HighlightPart highlightPart() {
        return HighlightPart.NAME;
    }

    @ConfigItem(keyName = "highlightExamine", name = "Highlight Examine", description = "Also color the Examine entry of priority items (independent of the option above).", section = highlightSection, position = 73)
    default boolean highlightExamine() {
        return false;
    }

    @ConfigItem(keyName = "highlightAboveValueOnly", name = "Highlight Above Value Only", description = "Only highlight priority items whose value is at or above the Minimum Value.", section = highlightSection, position = 74)
    default boolean highlightAboveValueOnly() {
        return false;
    }

    @Alpha
    @ConfigItem(keyName = "highlightColor", name = "Highlight Color", description = "Base highlight color (also used as the 'common' tier color).", section = highlightSection, position = 75)
    default Color highlightColor() {
        return new Color(0x00FF00);
    }

    @ConfigItem(keyName = "highlightByTier", name = "Tiered Highlight Colors", description = "Color priority items by tier: Ultra-Rare, Rare, Valuable, then common — using the colors below.", section = highlightSection, position = 76)
    default boolean highlightByTier() {
        return false;
    }

    @Alpha
    @ConfigItem(keyName = "highlightColorRare", name = "Tier Color: Rare", description = "Color for items in the Rare tier (uses the Drop Rarity thresholds). Requires Tiered Highlight Colors.", section = highlightSection, position = 77)
    default Color highlightColorRare() {
        return new Color(0xFF4040);
    }

    @Alpha
    @ConfigItem(keyName = "highlightColorUltraRare", name = "Tier Color: Ultra-Rare", description = "Color for items in the Ultra-Rare tier (uses the Drop Rarity thresholds). Requires Tiered Highlight Colors.", section = highlightSection, position = 78)
    default Color highlightColorUltraRare() {
        return new Color(0xAA00FF);
    }

    @Alpha
    @ConfigItem(keyName = "highlightColorValuable", name = "Tier Color: Valuable", description = "Color for items at or above the Minimum Value (when not in a rarity tier). Requires Tiered Highlight Colors.", section = highlightSection, position = 79)
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

    @ConfigItem(keyName = "notifyMinTier", name = "Notify For", description = "Which drops trigger a notification: any priority drop, only Rare+ tiers, or only Ultra-Rare.", section = notificationsSection, position = 72)
    default NotifyTier notifyMinTier() {
        return NotifyTier.ALL_PRIORITY;
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

    @ConfigItem(keyName = "clearCacheNow", name = "Clear Cache && Refresh", description = "Toggle on to delete all cached drop tables and re-fetch the current task from the Wiki. Resets itself automatically.", section = debugSection, position = 1006)
    default boolean clearCacheNow() {
        return false;
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
