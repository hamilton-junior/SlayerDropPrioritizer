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
    public void testTaskDrop_prioritizedByDefault() {
        // interestingDropsOnly = false (default): any task drop is priority
        taskDrops.add("bones");

        assertTrue(classifier.isPriority("bones", 526));
    }

    @Test
    public void testNonTaskDrop_notPrioritized() {
        taskDrops.add("bones");

        assertFalse(classifier.isPriority("goblin mail", 12345));
    }

    @Test
    public void testInterestingOnly_valuableTaskDropPrioritized() {
        config.setInterestingDropsOnly(true);
        config.setMinimumPriorityValue(1000);
        taskDrops.add("adamant scimitar");

        fakePrices.put(23456, 1200);

        assertTrue(classifier.isPriority("adamant scimitar", 23456));
    }

    @Test
    public void testInterestingOnly_nonValuableTaskDropNotPrioritized() {
        config.setInterestingDropsOnly(true);
        config.setMinimumPriorityValue(1000);
        taskDrops.add("bones");

        fakePrices.put(526, 10);

        assertFalse(classifier.isPriority("bones", 526));
    }

    @Test
    public void testValuableNonTaskDrop_prioritized() {
        config.setMinimumPriorityValue(1000);
        taskDrops.add("bones"); // "rune scimitar" is NOT in task drops

        fakePrices.put(1333, 15000);

        assertTrue(classifier.isPriority("rune scimitar", 1333));
    }

    @Test
    public void testNonValuableNonTaskDrop_notPrioritized() {
        config.setMinimumPriorityValue(1000);
        taskDrops.add("bones"); // "goblin mail" is NOT in task drops

        fakePrices.put(12345, 50);

        assertFalse(classifier.isPriority("goblin mail", 12345));
    }

    @Test
    public void testPriceCaching() {
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
        private boolean interestingDropsOnly = false;
        private int minimumPriorityValue = 0;
        private DropDisplayMode dropDisplayMode = DropDisplayMode.DEPRIORITIZE;
        private boolean prioritizeExamine = true;
        private int combatTimeoutSeconds = 30;
        private boolean testMode = false;
        private String testMonsterName = "Goblin";
        private boolean supportCollapsedItems = true;

        @Override
        public boolean enableDeprioritization() {
            return enableDeprioritization;
        }

        @Override
        public boolean interestingDropsOnly() {
            return interestingDropsOnly;
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
        public int combatTimeoutSeconds() {
            return combatTimeoutSeconds;
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

        public void setInterestingDropsOnly(boolean value) {
            this.interestingDropsOnly = value;
        }

        public void setMinimumPriorityValue(int value) {
            this.minimumPriorityValue = value;
        }
    }
}
