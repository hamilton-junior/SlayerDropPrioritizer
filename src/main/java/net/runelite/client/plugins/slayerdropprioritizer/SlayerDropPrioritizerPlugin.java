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
    final Set<String> currentTaskDrops = new HashSet<>();
    private final Set<Integer> resolvedNpcIds = new HashSet<>();
    private PriorityItemClassifier classifier;

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

    int lastCombatTick = -100;

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

        classifier = new PriorityItemClassifier(itemManager, client, currentTaskDrops, config);

        overlayManager.add(overlay);

        checkTask();
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);

        currentTaskDrops.clear();
        resolvedNpcIds.clear();
        dropCache.clear();

        if (classifier != null) {
            classifier.clearCache();
            classifier = null;
        }

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
            log.debug("[Tick] No interaction");
            return;
        }

        log.debug(
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
            } else if (!resolvedNpcIds.contains(target.getId())) {
                log.info(
                        "[Wiki] Resolving drops for {} ({})",
                        target.getName(),
                        target.getId());
                resolvedNpcIds.add(target.getId());
                resolveWikiPage(target.getId());
            }
        }
    }

    private void resolveWikiPage(int npcId) {
        okhttp3.HttpUrl url = new okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("oldschool.runescape.wiki")
                .addPathSegment("w")
                .addPathSegment("Special:Lookup")
                .addQueryParameter("type", "npc")
                .addQueryParameter("id", String.valueOf(npcId))
                .build();

        Request request = new Request.Builder()
                .url(url)
                .build();

        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("[Wiki] Lookup failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    log.error("[Wiki] Lookup unsuccessful: HTTP {}", response.code());
                    response.close();
                    return;
                }

                List<String> pathSegments = response.request().url().pathSegments();
                if (pathSegments.size() < 2 || !"w".equals(pathSegments.get(0))) {
                    log.error("[Wiki] Unexpected URL format: {}", response.request().url());
                    response.close();
                    return;
                }

                String page = pathSegments.get(1);
                lastWikiPage = page;

                log.info("[Wiki] Resolved page: {}", page);

                response.close();

                fetchDrops(page);
            }
        });
    }

    private void fetchDrops(String page) {
        okhttp3.HttpUrl url = new okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("oldschool.runescape.wiki")
                .addPathSegment("api.php")
                .addQueryParameter("action", "parse")
                .addQueryParameter("page", page)
                .addQueryParameter("format", "json")
                .addQueryParameter("prop", "wikitext")
                .build();

        Request request = new Request.Builder()
                .url(url)
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

                if (response.body() == null) {
                    response.close();
                    return;
                }

                String responseBody = response.body().string();
                JsonObject jsonObject = gson.fromJson(responseBody, JsonObject.class);
                if (jsonObject == null || !jsonObject.has("parse")) {
                    log.error("[Wiki] Invalid API response: {}", responseBody);
                    response.close();
                    return;
                }

                JsonObject parse = jsonObject.getAsJsonObject("parse");
                if (parse == null || !parse.has("wikitext")) {
                    response.close();
                    return;
                }

                JsonObject wikitext = parse.getAsJsonObject("wikitext");
                if (wikitext == null || !wikitext.has("*")) {
                    response.close();
                    return;
                }

                String wikiText = wikitext.get("*").getAsString();

                Pattern p = Pattern.compile("\\{\\{DropsLine.*?\\}\\}", Pattern.DOTALL);
                Matcher m = p.matcher(wikiText);

                int count = 0;

                while (m.find() && count < 5) {
                    String sample = m.group()
                            .replace("\r", "\\r")
                            .replace("\n", "\\n");

                    log.debug("[Wiki] DropsLine {}: {}", count, sample);
                    count++;
                }

                log.debug("[Wiki] Length: {}", wikiText.length());
                log.debug("[Wiki] Contains DropsLine? {}", wikiText.contains("DropsLine"));
                log.debug("[Wiki] Contains DropsTableHead? {}", wikiText.contains("DropsTableHead"));
                log.debug("[Wiki] Contains itemid=? {}", wikiText.contains("itemid="));

                int dropPos = wikiText.indexOf("Drops");

                if (dropPos > -1) {
                    int start = Math.max(0, dropPos - 200);
                    int end = Math.min(wikiText.length(), dropPos + 1000);

                    log.debug("[Wiki] Around Drops:\n{}", wikiText.substring(start, end));
                }

                Matcher matcher = Pattern.compile("\\{\\{\\s*DropsLine\\s*\\|[^}]*?\\b[Nn]ame\\s*=\\s*([^|}]+)")
                        .matcher(wikiText);

                while (matcher.find()) {
                    String itemName = matcher.group(1).trim();

                    currentTaskDrops.add(itemName);

                    log.debug("[Wiki] Drop Found: {}", itemName);
                }
                log.info(
                        "[Wiki] Loaded {} items from wiki for page: {}",
                        currentTaskDrops.size(),
                        page);
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
            processDeprioritizeMode(entries);
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
            boolean isPriority = isPriorityItem(entry, normalizedItemName);

            ClassifiedMenuItem item = new ClassifiedMenuItem(
                    entry,
                    i,
                    normalizedItemName,
                    normalizedOption,
                    isPriority,
                    itemId,
                    0); // Score is no longer used for sorting

            groundItems.add(item);
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
    private boolean isPriorityItem(MenuEntry entry, String normalizedItemName) {
        return classifier != null && classifier.isPriority(entry, normalizedItemName);
    }

    /**
     * HIDE mode: removes all non-priority ground items.
     * Preserves non-ground menu entries.
     */
    private void processHideMode(MenuEntry[] entries, List<ClassifiedMenuItem> groundItems) {
        List<MenuEntry> result = new ArrayList<>();
        java.util.Set<Integer> excludedIndices = new java.util.HashSet<>();

        for (ClassifiedMenuItem item : groundItems) {
            boolean isExamine = "Examine".equalsIgnoreCase(item.getNormalizedOption());
            boolean shouldHide = false;
            
            if (item.isPriority()) {
                if (isExamine && !config.prioritizeExamine()) {
                    shouldHide = true;
                }
            } else {
                shouldHide = true;
            }

            if (shouldHide) {
                excludedIndices.add(item.getOriginalIndex());
                log.debug("[HIDE] Remove: {}", item.getNormalizedItemName());
            } else {
                log.debug("[HIDE] Keep: {}", item.getNormalizedItemName());
            }
        }

        for (int i = 0; i < entries.length; i++) {
            if (!excludedIndices.contains(i)) {
                result.add(entries[i]);
            }
        }

        client.setMenuEntries(result.toArray(new MenuEntry[0]));
        log.info("[Menu] HIDE mode applied, {} items hidden", excludedIndices.size());
    }

    /**
     * DEPRIORITIZE mode: reorders the ground items block in the menu.
     * Places non-priority items at the bottom of the ground items block, and priority items at the top.
     * Respects examine options and config.prioritizeExamine setting, ensuring they are ordered correctly
     * around other menu options (like Walk here).
     */
    private void processDeprioritizeMode(MenuEntry[] entries) {
        List<MenuEntry> cancelEntries = new ArrayList<>();
        List<MenuEntry> otherEntries = new ArrayList<>();
        List<ClassifiedMenuItem> prioritized = new ArrayList<>();
        List<ClassifiedMenuItem> deprioritized = new ArrayList<>();

        for (int i = 0; i < entries.length; i++) {
            MenuEntry entry = entries[i];
            MenuAction action = entry.getType();

            if (action == MenuAction.CANCEL || "Cancel".equalsIgnoreCase(entry.getOption())) {
                cancelEntries.add(entry);
            } else if (isGroundItemAction(action)) {
                String normalizedItemName = normalizeItemName(entry.getTarget());
                String normalizedOption = normalizeItemName(entry.getOption()).trim();
                int itemId = entry.getIdentifier();
                boolean isPriority = isPriorityItem(entry, normalizedItemName);
                boolean isExamine = action == MenuAction.EXAMINE_ITEM_GROUND;

                ClassifiedMenuItem item = new ClassifiedMenuItem(
                        entry,
                        i,
                        normalizedItemName,
                        normalizedOption,
                        isPriority,
                        itemId,
                        0);

                boolean shouldDeprioritize = false;
                if (isPriority) {
                    if (isExamine && !config.prioritizeExamine()) {
                        shouldDeprioritize = true;
                    }
                } else {
                    shouldDeprioritize = true;
                }

                if (shouldDeprioritize) {
                    deprioritized.add(item);
                } else {
                    prioritized.add(item);
                }
            } else {
                otherEntries.add(entry);
            }
        }

        // Sort deprioritized items if prioritizeExamine is OFF
        if (!config.prioritizeExamine()) {
            deprioritized.sort((a, b) -> Integer.compare(getDeprioritizedCategory(a), getDeprioritizedCategory(b)));
        }

        // Reconstruct the menu entries list (bottom of menu to top)
        List<MenuEntry> newEntriesList = new ArrayList<>();

        // 1. Cancel entries at the very bottom (lowest index)
        newEntriesList.addAll(cancelEntries);

        // 2. Deprioritized ground items
        for (ClassifiedMenuItem item : deprioritized) {
            newEntriesList.add(item.getEntry());
        }

        // 3. Other entries (like Walk here, NPC options)
        newEntriesList.addAll(otherEntries);

        // 4. Prioritized ground items at the top (highest index)
        for (ClassifiedMenuItem item : prioritized) {
            newEntriesList.add(item.getEntry());
        }

        client.setMenuEntries(newEntriesList.toArray(new MenuEntry[0]));
        log.info("[Menu] DEPRIORITIZE mode applied: reordered menu with {} prioritized and {} deprioritized ground items", 
                prioritized.size(), deprioritized.size());
    }

    private int getDeprioritizedCategory(ClassifiedMenuItem item) {
        boolean isPriority = item.isPriority();
        boolean isExamine = item.getEntry().getType() == MenuAction.EXAMINE_ITEM_GROUND;

        if (isPriority) {
            // Must be Examine (since shouldDeprioritize was true and isPriority is true)
            return 1; // Priority Examine
        } else {
            return isExamine ? 0 : 2; // Non-priority Examine (0) vs Non-priority Take (2)
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