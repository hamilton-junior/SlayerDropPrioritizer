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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
public class PriorityItemClassifier {
    private final ItemManager itemManager;
    private final Client client;
    private final Set<String> taskDrops;
    private final Map<String, Double> dropRarity;
    private final SlayerDropPrioritizerConfig config;

    private final Map<Integer, Integer> unitGePriceCache = new HashMap<>();
    private final Map<Integer, Integer> unitHaPriceCache = new HashMap<>();

    private Set<String> alwaysPrioritySet = Collections.emptySet();
    private Set<String> alwaysIgnoreSet = Collections.emptySet();

    public PriorityItemClassifier(ItemManager itemManager, Client client, Set<String> taskDrops,
            Map<String, Double> dropRarity, SlayerDropPrioritizerConfig config) {
        this.itemManager = itemManager;
        this.client = client;
        this.taskDrops = taskDrops;
        this.dropRarity = dropRarity;
        this.config = config;
        refreshCustomLists();
    }

    public PriorityItemClassifier(ItemManager itemManager, Client client, Set<String> taskDrops,
            SlayerDropPrioritizerConfig config) {
        this(itemManager, client, taskDrops, new HashMap<>(), config);
    }

    public void refreshCustomLists() {
        alwaysPrioritySet = parseItemList(config.alwaysPriorityItems());
        alwaysIgnoreSet = parseItemList(config.alwaysIgnoreItems());
    }

    private Set<String> parseItemList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> result = new HashSet<>();
        for (String entry : raw.split("[,\n]")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed.toLowerCase());
            }
        }
        return result;
    }

    public boolean isPriority(String normalizedItemName, int itemId) {
        return isPriority(normalizedItemName, itemId, 1);
    }

    public boolean isPriority(String normalizedItemName, int itemId, int quantity) {
        String lower = normalizedItemName.toLowerCase();

        // 1. Always-ignore list: highest priority, returns false immediately
        if (alwaysIgnoreSet.contains(lower)) {
            log.debug("[Classifier] Always ignore: {}", normalizedItemName);
            return false;
        }

        // 2. Always-priority list: unconditionally priority
        if (alwaysPrioritySet.contains(lower)) {
            log.debug("[Classifier] Always priority: {}", normalizedItemName);
            return true;
        }

        // Note: clue scroll handling is done in the plugin (it needs ordering control),
        // not here in the base priority classification.

        // 3. Untradeable items (optional). Many valuable Slayer drops (imbue scrolls,
        //    totem pieces, etc.) are untradeable and therefore have no GE value.
        if (config.prioritizeUntradeables() && isUntradeable(itemId)) {
            log.debug("[Classifier] Untradeable: {}", normalizedItemName);
            return true;
        }

        // 4. Minimum value threshold
        int minPrice = config.minimumPriorityValue();
        if (minPrice > 0) {
            int effectiveValue = getEffectiveValue(itemId, quantity);
            if (effectiveValue >= minPrice) {
                log.debug("[Classifier] Meets value threshold: {} (id={}, qty={}, value={})",
                        normalizedItemName, itemId, quantity, effectiveValue);
                return true;
            }
        }

        // 5. Task drop table membership. Skipped when "Interesting Drops Only" is on, since
        //    in that mode being in the table is not enough — only value/rarity/lists qualify.
        if (!config.interestingDropsOnly() && taskDrops.contains(normalizedItemName)) {
            return true;
        }

        // 6. Wiki rarity check. A drop is "rare enough" when its rate is 1/N or rarer,
        //    i.e. the (effective) denominator is >= the Rare threshold.
        if (config.enableRarePriority()) {
            Double denominator = dropRarity.get(normalizedItemName);
            if (denominator != null && denominator >= config.rareThreshold()) {
                log.debug("[Classifier] Rare drop: {} (1/{})", normalizedItemName, denominator.intValue());
                return true;
            }
        }

        return false;
    }

    public boolean isPriority(MenuEntry entry, String normalizedItemName) {
        return isPriority(normalizedItemName, entry.getIdentifier(), extractQuantityFromTile(entry));
    }

    public int getItemDisplayValue(MenuEntry entry) {
        int itemId = entry.getIdentifier();
        int quantity = extractQuantityFromTile(entry);
        ItemValueDisplay display = config.itemValueDisplay();
        if (display == ItemValueDisplay.HA) {
            return getHaPrice(itemId) * quantity;
        }
        return getGePrice(itemId) * quantity;
    }

    /**
     * Value used for the Minimum Priority Value threshold (respects priorityValueSource).
     */
    public int getPriorityValue(MenuEntry entry) {
        return getEffectiveValue(entry.getIdentifier(), extractQuantityFromTile(entry));
    }

    public boolean isClueScroll(String name) {
        return name != null && name.toLowerCase().startsWith("clue scroll");
    }

    private int getEffectiveValue(int itemId, int quantity) {
        PriorityValueSource source = config.priorityValueSource();
        if (source == PriorityValueSource.HA_ONLY) {
            return getHaPrice(itemId) * quantity;
        }
        if (source == PriorityValueSource.HIGHEST_OF_BOTH) {
            return Math.max(getGePrice(itemId), getHaPrice(itemId)) * quantity;
        }
        return getGePrice(itemId) * quantity; // GE_ONLY or null-safe default
    }

    private int extractQuantityFromTile(MenuEntry entry) {
        int itemId = entry.getIdentifier();
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
                            TileItem tileItem = (TileItem) current;
                            if (tileItem.getId() == itemId) {
                                return tileItem.getQuantity();
                            }
                            current = current.getNext();
                        }
                    }
                }
            }
        }
        return 1;
    }

    private boolean isUntradeable(int itemId) {
        ItemComposition comp = itemManager.getItemComposition(itemId);
        int realId = comp.getNote() != -1 ? comp.getLinkedNoteId() : itemId;
        return !itemManager.getItemComposition(realId).isTradeable();
    }

    private int getGePrice(int itemId) {
        return unitGePriceCache.computeIfAbsent(itemId, id -> {
            ItemComposition comp = itemManager.getItemComposition(id);
            int realId = comp.getNote() != -1 ? comp.getLinkedNoteId() : id;
            int price = (realId == ItemID.COINS) ? 1 : itemManager.getItemPrice(realId);
            log.debug("[Price] GE id={} realId={} price={}", id, realId, price);
            return price;
        });
    }

    private int getHaPrice(int itemId) {
        return unitHaPriceCache.computeIfAbsent(itemId, id -> {
            ItemComposition comp = itemManager.getItemComposition(id);
            int realId = comp.getNote() != -1 ? comp.getLinkedNoteId() : id;
            int price = (realId == ItemID.COINS) ? 1 : itemManager.getItemComposition(realId).getHaPrice();
            log.debug("[Price] HA id={} realId={} price={}", id, realId, price);
            return price;
        });
    }

    public void clearCache() {
        unitGePriceCache.clear();
        unitHaPriceCache.clear();
    }
}
