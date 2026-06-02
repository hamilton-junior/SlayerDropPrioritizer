package net.runelite.client.plugins.slayerdropprioritizer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ItemManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Classifies menu items as priority or non-priority based on drop table and GE price.
 */
@Slf4j
@RequiredArgsConstructor
public class PriorityItemClassifier {
    private final ItemManager itemManager;
    private final Set<String> taskDrops;
    private final SlayerDropPrioritizerConfig config;

    private final Map<Integer, Integer> priceCache = new HashMap<>();

    /**
     * Determines if an item should be prioritized.
     */
    public boolean isPriority(String normalizedItemName, int itemId) {
        // Check if item is in task drop table
        if (taskDrops.contains(normalizedItemName)) {
            return true;
        }

        // Check if item is valuable (applies to non-table items too)
        if (config.prioritizationMode() == PrioritizationMode.VALUABLE_TASK_DROPS) {
            int price = getPrice(itemId);
            return price >= config.minimumPriorityValue();
        }

        return false;
    }

    /**
     * Gets price from cache or ItemManager, caching the result.
     */
    private int getPrice(int itemId) {
        return priceCache.computeIfAbsent(itemId, id -> {
            int price = itemManager.getItemPrice(id);
            log.debug("[Price Cache] Cached item {} = {}", id, price);
            return price;
        });
    }

    public void clearCache() {
        priceCache.clear();
    }
}
