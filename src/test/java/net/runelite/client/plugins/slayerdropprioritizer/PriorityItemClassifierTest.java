package net.runelite.client.plugins.slayerdropprioritizer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PriorityItemClassifierTest {
    private Map<Integer, Integer> fakePrices;
    private int priceLookupCount;
    private Set<String> taskDrops;
    private FakeConfig config;
    private PriorityItemClassifier classifier;
    private ItemManager itemManager;
    private Client client;

    @Before
    public void setUp() {
        fakePrices = new HashMap<>();
        priceLookupCount = 0;
        taskDrops = new HashSet<>();
        config = new FakeConfig();
        
        itemManager = mock(ItemManager.class);
        client = mock(Client.class);
        
        ItemComposition mockComposition = mock(ItemComposition.class);
        when(mockComposition.getNote()).thenReturn(-1);
        when(itemManager.getItemComposition(anyInt())).thenReturn(mockComposition);
        
        when(itemManager.getItemPrice(anyInt())).thenAnswer(invocation -> {
            priceLookupCount++;
            int itemId = invocation.getArgument(0);
            return fakePrices.getOrDefault(itemId, 0);
        });
        
        classifier = new PriorityItemClassifier(
            itemManager,
            client,
            taskDrops,
            config
        );
    }

    @Test
    public void testAllTaskDropsMode_taskDropPrioritized() {
        config.setPrioritizationMode(PrioritizationMode.ALL_TASK_DROPS);
        taskDrops.add("bones");

        assertTrue(classifier.isPriority("bones", 526));
    }

    @Test
    public void testAllTaskDropsMode_nonTaskDropNotPrioritized() {
        config.setPrioritizationMode(PrioritizationMode.ALL_TASK_DROPS);
        taskDrops.add("bones");

        assertFalse(classifier.isPriority("goblin mail", 12345));
    }

    @Test
    public void testValuableTaskDropsMode_valuableTaskDropPrioritized() {
        config.setPrioritizationMode(PrioritizationMode.VALUABLE_TASK_DROPS);
        config.setMinimumPriorityValue(1000);
        taskDrops.add("adamant scimitar");
        
        fakePrices.put(23456, 1200);

        assertTrue(classifier.isPriority("adamant scimitar", 23456));
    }

    @Test
    public void testValuableTaskDropsMode_nonValuableTaskDropNotPrioritized() {
        config.setPrioritizationMode(PrioritizationMode.VALUABLE_TASK_DROPS);
        config.setMinimumPriorityValue(1000);
        taskDrops.add("bones");
        
        fakePrices.put(526, 10);

        assertFalse(classifier.isPriority("bones", 526));
    }

    @Test
    public void testValuableTaskDropsMode_valuableNonTaskDropPrioritized() {
        config.setPrioritizationMode(PrioritizationMode.VALUABLE_TASK_DROPS);
        config.setMinimumPriorityValue(1000);
        taskDrops.add("bones"); // "rune scimitar" is NOT in task drops
        
        fakePrices.put(1333, 15000);

        assertTrue(classifier.isPriority("rune scimitar", 1333));
    }

    @Test
    public void testValuableTaskDropsMode_nonValuableNonTaskDropNotPrioritized() {
        config.setPrioritizationMode(PrioritizationMode.VALUABLE_TASK_DROPS);
        config.setMinimumPriorityValue(1000);
        taskDrops.add("bones"); // "goblin mail" is NOT in task drops
        
        fakePrices.put(12345, 50);

        assertFalse(classifier.isPriority("goblin mail", 12345));
    }

    @Test
    public void testPriceCaching() {
        config.setPrioritizationMode(PrioritizationMode.VALUABLE_TASK_DROPS);
        config.setMinimumPriorityValue(1000);
        
        fakePrices.put(999, 2000);

        // Perform multiple checks
        assertTrue(classifier.isPriority("rare item", 999));
        assertTrue(classifier.isPriority("rare item", 999));
        assertTrue(classifier.isPriority("rare item", 999));

        // Verify that the price provider was called exactly once due to caching
        assertEquals(1, priceLookupCount);
    }

    @Test
    public void testClearCache() {
        config.setPrioritizationMode(PrioritizationMode.VALUABLE_TASK_DROPS);
        config.setMinimumPriorityValue(1000);
        
        fakePrices.put(999, 2000);

        assertTrue(classifier.isPriority("rare item", 999));
        classifier.clearCache();
        assertTrue(classifier.isPriority("rare item", 999));

        // Verify that the price provider was called twice because the cache was cleared
        assertEquals(2, priceLookupCount);
    }

    private static class FakeConfig implements SlayerDropPrioritizerConfig {
        private boolean enableDeprioritization = true;
        private PrioritizationMode prioritizationMode = PrioritizationMode.ALL_TASK_DROPS;
        private int minimumPriorityValue = 0;
        private DropDisplayMode dropDisplayMode = DropDisplayMode.DEPRIORITIZE;
        private boolean prioritizeExamine = true;
        private int combatTimeout = 50;
        private boolean testMode = false;
        private String testMonsterName = "Goblin";
        private boolean supportCollapsedItems = true;

        @Override
        public boolean enableDeprioritization() {
            return enableDeprioritization;
        }

        @Override
        public PrioritizationMode prioritizationMode() {
            return prioritizationMode;
        }

        @Override
        public int minimumPriorityValue() {
            return minimumPriorityValue;
        }

        @Override
        public DropDisplayMode dropDisplayMode() {
            return dropDisplayMode;
        }

        @Override
        public boolean prioritizeExamine() {
            return prioritizeExamine;
        }

        @Override
        public int combatTimeout() {
            return combatTimeout;
        }

        @Override
        public boolean testMode() {
            return testMode;
        }

        @Override
        public String testMonsterName() {
            return testMonsterName;
        }

        @Override
        public boolean supportCollapsedItems() {
            return supportCollapsedItems;
        }

        public void setPrioritizationMode(PrioritizationMode mode) {
            this.prioritizationMode = mode;
        }

        public void setMinimumPriorityValue(int value) {
            this.minimumPriorityValue = value;
        }
    }
}
