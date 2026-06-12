package net.runelite.client.plugins.slayerdropprioritizer;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemLayer;
import net.runelite.api.MenuEntry;
import net.runelite.api.Node;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Classifies menu items as priority or non-priority based on drop table and GE price.
 */
@Slf4j
public class PriorityItemClassifier {
    private final ItemManager itemManager;
    private final Client client;
    private final Set<String> taskDrops;
    private final SlayerDropPrioritizerConfig config;

    private final Map<Integer, Integer> unitPriceCache = new HashMap<>();

    public PriorityItemClassifier(ItemManager itemManager, Client client, Set<String> taskDrops,
            SlayerDropPrioritizerConfig config) {
        this.itemManager = itemManager;
        this.client = client;
        this.taskDrops = taskDrops;
        this.config = config;
    }

    public boolean isPriority(String normalizedItemName, int itemId) {
        return isPriority(normalizedItemName, itemId, 1);
    }

    public boolean isPriority(String normalizedItemName, int itemId, int quantity) {
        int minPrice = config.minimumPriorityValue();

        // Always prioritize items that meet the minimum value threshold (if configured)
        if (minPrice > 0) {
            int price = getGePrice(itemId) * quantity;
            if (price >= minPrice) {
                log.debug("[Classifier] Valuable item: {} (id={}, qty={}) total_price={}",
                        normalizedItemName, itemId, quantity, price);
                return true;
            }
        }

        // If it's a task drop, check mode
        if (taskDrops.contains(normalizedItemName)) {
            if (config.prioritizationMode() == PrioritizationMode.ALL_TASK_DROPS) {
                return true;
            }
            // If VALUABLE_TASK_DROPS is selected but minPrice is 0, we can't filter by
            // value, so we allow it.
            if (config.prioritizationMode() == PrioritizationMode.VALUABLE_TASK_DROPS && minPrice == 0) {
                return true;
            }
        }

        return false;
    }

    public boolean isPriority(MenuEntry entry, String normalizedItemName) {
        int itemId = entry.getIdentifier();
        int quantity = 1;

        // Calculate the item quantity on the tile by searching for the TileItem
        // matching the identifier
        int sceneX = entry.getParam0();
        int sceneY = entry.getParam1();

        if (sceneX >= 0 && sceneX < 104 && sceneY >= 0 && sceneY < 104) {
            Tile[][][] tiles = client.getScene().getTiles();
            if (tiles != null) {
                Tile tile = tiles[client.getPlane()][sceneX][sceneY];
                if (tile != null) {
                    ItemLayer itemLayer = tile.getItemLayer();
                    if (itemLayer != null) {
                        Node current = itemLayer.getBottom();
                        while (current instanceof TileItem) {
                            TileItem item = (TileItem) current;
                            if (item.getId() == itemId) {
                                quantity = item.getQuantity();
                                break;
                            }
                            current = current.getNext();
                        }
                    }
                }
            }
        }

        return isPriority(normalizedItemName, itemId, quantity);
    }

    /**
     * Gets the unit price from cache, resolving noted items and coins.
     */
    private int getGePrice(int itemId) {
        return unitPriceCache.computeIfAbsent(itemId, id -> {
            ItemComposition itemComposition = itemManager.getItemComposition(id);
            int realItemId = itemComposition.getNote() != -1 ? itemComposition.getLinkedNoteId() : id;

            int price;
            if (realItemId == ItemID.COINS) {
                price = 1;
            } else {
                price = itemManager.getItemPrice(realItemId);
            }
            log.debug("[Price Cache] Cached item {} (realId={}) unit price = {}", id, realItemId, price);
            return price;
        });
    }

    public void clearCache() {
        unitPriceCache.clear();
    }
}
