package net.runelite.client.plugins.slayerdropprioritizer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.game.ItemManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@PluginDescriptor(name = "Slayer Drop Prioritizer", description = "Deprioritizes items outside task drop table.", tags = {
        "slayer", "drops", "menu" })
public class SlayerDropPrioritizerPlugin extends Plugin {
    @Inject
    private Client client;

    @Inject
    private SlayerDropPrioritizerConfig config;

    @Inject
    private ConfigManager configManager;

    @Inject
    private OkHttpClient okHttpClient;

    @Inject
    private Gson gson;

    @Inject
    private ItemManager itemManager;

    @Inject
    private OverlayManager overlayManager;

    @Inject
    private SlayerDropPrioritizerOverlay overlay;

    private String currentTask = "";
    private final Set<String> currentTaskDrops = new HashSet<>();
    private final Set<Integer> resolvedNpcIds = new HashSet<>();

    private String normalizeItemName(String itemName) {
        itemName = net.runelite.client.util.Text.removeTags(itemName);

        if (config.supportCollapsedItems()) {
            itemName = itemName.replaceAll("\\s+x\\s+\\d+$", "");
        }

        itemName = itemName
                .replaceAll("\\s*\\([^)]*\\)$", "")
                .trim();

        return itemName;
    }

    private int lastCombatTick = -100;

    private static final int DEFAULT_COMBAT_TIMEOUT = 50;

    private String currentNpc = "";
    private int currentNpcId = -1;
    private String lastWikiPage = "";

    @Provides
    SlayerDropPrioritizerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SlayerDropPrioritizerConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("[Status] Plugin Started");

        overlayManager.add(overlay);

        checkTask();
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);

        currentTaskDrops.clear();
        resolvedNpcIds.clear();

        currentNpc = "";
        currentNpcId = -1;
        lastWikiPage = "";
    }

    private void checkTask() {
        String task = config.testMode()
                ? config.testMonsterName()
                : configManager.getConfiguration("slayer", "taskName");

        updateTask(task);
    }

    private void updateTask(String task) {
        if (task == null || task.isEmpty()) {
            currentTask = "";
            currentTaskDrops.clear();

            log.info("[Task] Empty task detected");

            return;
        }

        if (!task.equalsIgnoreCase(currentTask)) {
            currentTask = task;

            currentTaskDrops.clear();
            resolvedNpcIds.clear();

            log.info("[Task] Target updated to: {}", currentTask);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if ("slayerdropprioritizer".equals(event.getGroup())) {
            checkTask();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        Player player = client.getLocalPlayer();

        if (player == null) {
            log.debug("[Tick] Player is null");
            return;
        }

        if (!config.enableDeprioritization()) {
            log.debug("[Tick] Plugin disabled");
            return;
        }

        if (currentTask.isEmpty()) {
            log.debug("[Tick] Current task is empty");
            return;
        }

        Actor interacting = player.getInteracting();

        if (interacting == null) {
            log.info("[Tick] No interaction");
            return;
        }

        log.info(
                "[Tick] Interacting with: {} ({})",
                interacting.getName(),
                interacting.getClass().getSimpleName());

        NPC target = null;

        if (interacting instanceof NPC) {
            NPC npc = (NPC) interacting;

            if (npc.getName() != null &&
                    npc.getName().toLowerCase().contains(currentTask.toLowerCase())) {
                target = npc;
            }
        }

        if (target != null) {
            currentNpc = target.getName();
            currentNpcId = target.getId();

            lastCombatTick = client.getTickCount();

            log.info(
                    "[Combat] Target found: {} ({})",
                    currentNpc,
                    currentNpcId);

            if (dropCache.containsKey(target.getId())) {
                currentTaskDrops.clear();
                currentTaskDrops.addAll(dropCache.get(target.getId()));
            } else if (currentTaskDrops.isEmpty() &&
                    !resolvedNpcIds.contains(target.getId()))
                log.info(
                        "[Wiki] Resolving drops for {} ({})",
                        target.getName(),
                        target.getId());

            resolvedNpcIds.add(target.getId());

            resolveWikiPage(target.getId());
        }
    }

    private void resolveWikiPage(int npcId) {
        Request request = new Request.Builder()
                .url("https://oldschool.runescape.wiki/w/Special:Lookup?type=npc&id=" + npcId)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("[Wiki] Lookup failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                String page = response.request()
                        .url()
                        .toString()
                        .split("/w/")[1]
                        .split("\\?")[0]
                        .split("#")[0];

                lastWikiPage = page;

                log.info("[Wiki] Resolved page: {}", page);

                response.close();

                fetchDrops(page);
            }
        });
    }

    private void fetchDrops(String page) {
        Request request = new Request.Builder()
                .url("https://oldschool.runescape.wiki/api.php?action=parse&page=" + page
                        + "&format=json&prop=wikitext")
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("[Wiki] Fetch failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    return;
                }

                String wikiText = gson
                        .fromJson(response.body().string(), JsonObject.class)
                        .getAsJsonObject("parse")
                        .getAsJsonObject("wikitext")
                        .get("*")
                        .getAsString();

                Pattern p = Pattern.compile("\\{\\{DropsLine.*?\\}\\}", Pattern.DOTALL);
                Matcher m = p.matcher(wikiText);

                int count = 0;

                while (m.find() && count < 5) {
                    String sample = m.group()
                            .replace("\r", "\\r")
                            .replace("\n", "\\n");

                    log.info("[Wiki] DropsLine {}: {}", count, sample);
                    count++;
                }

                log.info(
                        "[Wiki] Length: {}",
                        wikiText.length());

                log.info(
                        "[Wiki] Contains DropsLine? {}",
                        wikiText.contains("DropsLine"));

                log.info(
                        "[Wiki] Contains DropsTableHead? {}",
                        wikiText.contains("DropsTableHead"));

                log.info(
                        "[Wiki] Contains itemid=? {}",
                        wikiText.contains("itemid="));

                int dropPos = wikiText.indexOf("Drops");

                if (dropPos > -1) {
                    int start = Math.max(0, dropPos - 200);
                    int end = Math.min(wikiText.length(), dropPos + 1000);

                    log.info(
                            "[Wiki] Around Drops:\n{}",
                            wikiText.substring(start, end));
                }

                Matcher matcher = Pattern.compile("\\{\\{DropsLine\\|name=([^|}]+)")
                        .matcher(wikiText);

                while (matcher.find()) {
                    String itemName = matcher.group(1).trim();

                    currentTaskDrops.add(itemName);

                    log.info("[Wiki] Drop Found: {}", itemName);
                }
                log.info(
                        "[Wiki] Loaded {} item ids",
                        currentTaskDrops.size());
                dropCache.put(
                        currentNpcId,
                        new HashSet<>(currentTaskDrops));

                response.close();
            }
        });
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (!config.enableDeprioritization()) {
            log.debug("[Menu] Plugin disabled");
            return;
        }

        if (currentTaskDrops.isEmpty()) {
            log.debug("[Menu] No drops loaded yet");
            return;
        }

        if ((client.getTickCount() - lastCombatTick) > config.combatTimeout()) {
            log.debug("[Menu] Outside combat grace period");
            return;
        }

        MenuEntry[] entries = client.getMenuEntries();
        List<ClassifiedMenuItem> groundItems = extractAndClassifyGroundItems(entries);

        if (groundItems.isEmpty()) {
            log.debug("[Menu] No ground items in menu");
            return;
        }

        log.info("[Menu] Opened with {} ground items (drops={}, combatTicksAgo={})",
                groundItems.size(),
                currentTaskDrops.size(),
                client.getTickCount() - lastCombatTick);

        if (config.dropDisplayMode() == DropDisplayMode.SHOW) {
            log.debug("[Menu] SHOW mode - no changes");
            return;
        }

        if (config.dropDisplayMode() == DropDisplayMode.HIDE) {
            processHideMode(entries, groundItems);
        } else if (config.dropDisplayMode() == DropDisplayMode.DEPRIORITIZE) {
            processDeprioritizeMode(entries, groundItems);
        }
    }

    /**
     * Extracts and classifies all ground items from the menu.
     */
    private List<ClassifiedMenuItem> extractAndClassifyGroundItems(MenuEntry[] entries) {
        List<ClassifiedMenuItem> groundItems = new ArrayList<>();

        for (int i = 0; i < entries.length; i++) {
            MenuEntry entry = entries[i];
            MenuAction action = entry.getType();

            if (!isGroundItemAction(action)) {
                continue;
            }

            String normalizedItemName = normalizeItemName(entry.getTarget());
            String normalizedOption = normalizeItemName(entry.getOption()).trim();
            int itemId = entry.getIdentifier();
            boolean isPriority = isPriorityItem(normalizedItemName, itemId);

            ClassifiedMenuItem item = new ClassifiedMenuItem(
                    entry,
                    i,
                    normalizedItemName,
                    normalizedOption,
                    isPriority,
                    itemId);

            groundItems.add(item);

            log.debug("[Classify] {} ({}) | option={} | priority={}",
                    normalizedItemName,
                    itemId,
                    normalizedOption,
                    isPriority);
        }

        return groundItems;
    }

    /**
     * Checks if a menu action represents a ground item.
     */
    private boolean isGroundItemAction(MenuAction action) {
        return action == MenuAction.GROUND_ITEM_FIRST_OPTION
                || action == MenuAction.GROUND_ITEM_SECOND_OPTION
                || action == MenuAction.GROUND_ITEM_THIRD_OPTION
                || action == MenuAction.GROUND_ITEM_FOURTH_OPTION
                || action == MenuAction.GROUND_ITEM_FIFTH_OPTION
                || action == MenuAction.EXAMINE_ITEM_GROUND;
    }

    /**
     * Determines if an item should be prioritized.
     * Supports both task drops and valuable items.
     */
    private boolean isPriorityItem(String normalizedItemName, int itemId) {
        // Task drops are always considered unless filtered by price
        if (currentTaskDrops.contains(normalizedItemName)) {
            if (config.prioritizationMode() == PrioritizationMode.VALUABLE_TASK_DROPS) {
                int gePrice = itemManager.getItemPrice(itemId);
                boolean valuable = gePrice >= config.minimumPriorityValue();
                log.debug("[Value Check] {} id={} price={} valuable={}",
                        normalizedItemName, itemId, gePrice, valuable);
                return valuable;
            }
            return true;
        }

        // Non-task items can be prioritized if valuable
        if (config.prioritizationMode() == PrioritizationMode.VALUABLE_TASK_DROPS) {
            int gePrice = itemManager.getItemPrice(itemId);
            boolean valuable = gePrice >= config.minimumPriorityValue();
            if (valuable) {
                log.debug("[Valuable Non-Task] {} id={} price={}",
                        normalizedItemName, itemId, gePrice);
            }
            return valuable;
        }

        return false;
    }

    /**
     * HIDE mode: removes all non-priority ground items.
     * Preserves non-ground menu entries.
     */
    private void processHideMode(MenuEntry[] entries, List<ClassifiedMenuItem> groundItems) {
        List<MenuEntry> result = new ArrayList<>();

        // Build a set of ground item indices for faster lookup
        java.util.Set<Integer> groundIndices = new java.util.HashSet<>();
        for (ClassifiedMenuItem item : groundItems) {
            groundIndices.add(item.getOriginalIndex());
        }

        // Add all non-ground items
        for (int i = 0; i < entries.length; i++) {
            if (!groundIndices.contains(i)) {
                result.add(entries[i]);
            }
        }

        // Add only priority ground items
        for (ClassifiedMenuItem item : groundItems) {
            if (item.isPriority()) {
                result.add(item.getEntry());
                log.debug("[HIDE] Keep: {}", item.getNormalizedItemName());
            } else {
                log.debug("[HIDE] Remove: {}", item.getNormalizedItemName());
            }
        }

        client.setMenuEntries(result.toArray(new MenuEntry[0]));
        log.info("[Menu] HIDE mode applied, {} items hidden",
                groundItems.size() - (int) groundItems.stream().filter(ClassifiedMenuItem::isPriority).count());
    }

    /**
     * DEPRIORITIZE mode: reorders ground items, keeping priority items first.
     * Preserves all non-ground menu entries at their original positions.
     */
    private void processDeprioritizeMode(MenuEntry[] entries, List<ClassifiedMenuItem> groundItems) {
        // Separate priority and non-priority items, grouped by "Take" and "Examine"
        List<ClassifiedMenuItem> priorityTake = new ArrayList<>();
        List<ClassifiedMenuItem> priorityExamine = new ArrayList<>();
        List<ClassifiedMenuItem> nonPriorityTake = new ArrayList<>();
        List<ClassifiedMenuItem> nonPriorityExamine = new ArrayList<>();

        for (ClassifiedMenuItem item : groundItems) {
            if (item.isPriority()) {
                if ("Take".equalsIgnoreCase(item.getNormalizedOption())) {
                    priorityTake.add(item);
                } else if ("Examine".equalsIgnoreCase(item.getNormalizedOption())) {
                    priorityExamine.add(item);
                }
            } else {
                if ("Take".equalsIgnoreCase(item.getNormalizedOption())) {
                    nonPriorityTake.add(item);
                } else if ("Examine".equalsIgnoreCase(item.getNormalizedOption())) {
                    nonPriorityExamine.add(item);
                }
            }
        }

        // Build reordered list of ground items
        List<ClassifiedMenuItem> reordered = new ArrayList<>();
        reordered.addAll(priorityTake);
        if (config.prioritizeExamine()) {
            reordered.addAll(priorityExamine);
        }
        reordered.addAll(nonPriorityTake);
        if (!config.prioritizeExamine()) {
            reordered.addAll(nonPriorityExamine);
        } else {
            reordered.addAll(nonPriorityExamine);
        }

        // Apply reordering to original array positions
        for (int i = 0; i < reordered.size() && i < groundItems.size(); i++) {
            int targetIdx = groundItems.get(i).getOriginalIndex();
            entries[targetIdx] = reordered.get(i).getEntry();
        }

        client.setMenuEntries(entries);
        log.info("[Menu] DEPRIORITIZE mode applied: {} priority items first",
                priorityTake.size() + (config.prioritizeExamine() ? priorityExamine.size() : 0));

        // Debug logging
        if (log.isDebugEnabled()) {
            log.debug("[DEPRIORITIZE] Final order:");
            for (ClassifiedMenuItem item : reordered) {
                log.debug("  - {} ({})", item.getNormalizedItemName(), item.isPriority() ? "PRIORITY" : "NON-PRIORITY");
            }
        }
    }

    public String getCurrentTask() {
        return currentTask;
    }

    public String getCurrentNpc() {
        return currentNpc;
    }

    public int getCurrentNpcId() {
        return currentNpcId;
    }

    public int getDropCount() {
        return currentTaskDrops.size();
    }

    public String getLastWikiPage() {
        return lastWikiPage;
    }

    public boolean isInCombatGrace() {
        return (client.getTickCount() - lastCombatTick) <= config.combatTimeout();
    }

    private final Map<Integer, Set<String>> dropCache = new HashMap<>();
}