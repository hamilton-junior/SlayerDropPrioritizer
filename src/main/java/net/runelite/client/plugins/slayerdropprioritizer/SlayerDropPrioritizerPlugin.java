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
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    final Map<String, Double> currentDropRarity = new HashMap<>();

    private final Set<Integer> resolvedNpcIds = new HashSet<>();
    private final Map<Integer, Set<String>> dropCache = new HashMap<>();
    private final Map<Integer, Map<String, Double>> dropRarityCache = new HashMap<>();

    private PriorityItemClassifier classifier;

    int lastCombatTick = -100;

    private String currentNpc = "";
    private int currentNpcId = -1;
    private String lastWikiPage = "";

    private static final Pattern DROPS_LINE_NAME_PATTERN =
            Pattern.compile("\\b[Nn]ame\\s*=\\s*([^|\n}]+)");
    private static final Pattern DROPS_LINE_RARITY_PATTERN =
            Pattern.compile("\\b[Rr]arity\\s*=\\s*([^|\n}]+)");
    private static final Pattern RARITY_FRACTION_PATTERN =
            Pattern.compile("\\d+/(\\d+)");

    @Provides
    SlayerDropPrioritizerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SlayerDropPrioritizerConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("[Status] Plugin Started");
        classifier = new PriorityItemClassifier(itemManager, client, currentTaskDrops, currentDropRarity, config);
        overlayManager.add(overlay);
        checkTask();
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(overlay);
        currentTaskDrops.clear();
        currentDropRarity.clear();
        resolvedNpcIds.clear();
        dropCache.clear();
        dropRarityCache.clear();
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
            currentDropRarity.clear();
            log.info("[Task] Empty task detected");
            return;
        }
        if (!task.equalsIgnoreCase(currentTask)) {
            currentTask = task;
            currentTaskDrops.clear();
            currentDropRarity.clear();
            resolvedNpcIds.clear();
            log.info("[Task] Target updated to: {}", currentTask);
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        if ("slayerdropprioritizer".equals(event.getGroup())) {
            if (classifier != null) {
                classifier.refreshCustomLists();
            }
            checkTask();
        }
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        Player player = client.getLocalPlayer();
        if (player == null) {
            return;
        }
        if (!config.enableDeprioritization()) {
            return;
        }
        if (currentTask.isEmpty()) {
            return;
        }

        Actor interacting = player.getInteracting();
        if (interacting == null) {
            return;
        }

        NPC target = null;
        if (interacting instanceof NPC) {
            NPC npc = (NPC) interacting;
            if (npc.getName() != null && npc.getName().toLowerCase().contains(currentTask.toLowerCase())) {
                target = npc;
            }
        }

        if (target != null) {
            currentNpc = target.getName();
            currentNpcId = target.getId();
            lastCombatTick = client.getTickCount();
            log.info("[Combat] Target found: {} ({})", currentNpc, currentNpcId);

            if (dropCache.containsKey(target.getId())) {
                currentTaskDrops.clear();
                currentTaskDrops.addAll(dropCache.get(target.getId()));
                currentDropRarity.clear();
                Map<String, Double> cachedRarity = dropRarityCache.get(target.getId());
                if (cachedRarity != null) {
                    currentDropRarity.putAll(cachedRarity);
                }
            } else if (!resolvedNpcIds.contains(target.getId())) {
                log.info("[Wiki] Resolving drops for {} ({})", target.getName(), target.getId());
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

        Request request = new Request.Builder().url(url).build();
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

        Request request = new Request.Builder().url(url).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("[Wiki] Fetch failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful() || response.body() == null) {
                    response.close();
                    return;
                }

                String responseBody = response.body().string();
                response.close();

                JsonObject jsonObject = gson.fromJson(responseBody, JsonObject.class);
                if (jsonObject == null || !jsonObject.has("parse")) {
                    log.error("[Wiki] Invalid API response");
                    return;
                }
                JsonObject parse = jsonObject.getAsJsonObject("parse");
                if (parse == null || !parse.has("wikitext")) return;
                JsonObject wikitext = parse.getAsJsonObject("wikitext");
                if (wikitext == null || !wikitext.has("*")) return;

                String wikiText = wikitext.get("*").getAsString();
                parseDropsFromWikiText(wikiText);
            }
        });
    }

    /**
     * Splits wikitext on DropsLine template openings, parses Name and Rarity from each block,
     * then stores results into the current task's drop sets and caches.
     */
    private void parseDropsFromWikiText(String wikiText) {
        Set<String> drops = new HashSet<>();
        Map<String, Double> rarityMap = new HashMap<>();

        // Split on each DropsLine template start; index 0 is text before the first one
        String[] blocks = wikiText.split("\\{\\{\\s*DropsLine");
        for (int i = 1; i < blocks.length; i++) {
            String block = blocks[i];
            int end = block.indexOf("}}");
            if (end < 0) continue;
            String templateBody = block.substring(0, end);

            Matcher nameMatcher = DROPS_LINE_NAME_PATTERN.matcher(templateBody);
            if (!nameMatcher.find()) continue;
            String itemName = nameMatcher.group(1).trim();
            drops.add(itemName);

            Matcher rarityMatcher = DROPS_LINE_RARITY_PATTERN.matcher(templateBody);
            if (rarityMatcher.find()) {
                String rarityText = rarityMatcher.group(1).trim();
                Matcher fractionMatcher = RARITY_FRACTION_PATTERN.matcher(rarityText);
                if (fractionMatcher.find()) {
                    double denominator = Double.parseDouble(fractionMatcher.group(1));
                    rarityMap.put(itemName, denominator);
                    log.debug("[Wiki] Rarity for {}: 1/{}", itemName, (int) denominator);
                }
            }
        }

        currentTaskDrops.addAll(drops);
        currentDropRarity.clear();
        currentDropRarity.putAll(rarityMap);
        dropCache.put(currentNpcId, new HashSet<>(drops));
        dropRarityCache.put(currentNpcId, new HashMap<>(rarityMap));

        log.info("[Wiki] Loaded {} drops ({} with rarity) for NPC id={}", drops.size(), rarityMap.size(), currentNpcId);
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (!config.enableDeprioritization()) {
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

        log.info("[Menu] Opened with {} ground items (mode={})", groundItems.size(), config.dropDisplayMode());

        List<MenuEntry> result;
        DropDisplayMode mode = config.dropDisplayMode();

        if (mode == DropDisplayMode.HIDE || mode == DropDisplayMode.HIDE_TAKE_ONLY) {
            result = buildHideModeEntries(entries, groundItems, mode == DropDisplayMode.HIDE_TAKE_ONLY);
        } else if (mode == DropDisplayMode.DEPRIORITIZE) {
            result = buildDeprioritizeModeEntries(entries, groundItems);
        } else {
            // SHOW mode: no reordering, but still apply visual decorations if configured
            if (!config.enablePriorityMarker() && !config.showItemValueInMenu()) {
                log.debug("[Menu] SHOW mode - no changes");
                return;
            }
            result = new ArrayList<>(Arrays.asList(entries));
        }

        applyDisplayModifications(result, groundItems);
        client.setMenuEntries(result.toArray(new MenuEntry[0]));
    }

    private List<ClassifiedMenuItem> extractAndClassifyGroundItems(MenuEntry[] entries) {
        List<ClassifiedMenuItem> groundItems = new ArrayList<>();
        for (int i = 0; i < entries.length; i++) {
            MenuEntry entry = entries[i];
            if (!isGroundItemAction(entry.getType())) continue;

            String normalizedName = normalizeItemName(entry.getTarget());
            String normalizedOption = normalizeItemName(entry.getOption()).trim();
            int itemId = entry.getIdentifier();
            boolean priority = classifier != null && classifier.isPriority(entry, normalizedName);

            groundItems.add(new ClassifiedMenuItem(entry, i, normalizedName, normalizedOption, priority, itemId, 0));
        }
        return groundItems;
    }

    /**
     * HIDE / HIDE_TAKE_ONLY mode.
     *
     * HIDE: removes all ground item entries for non-priority items.
     * HIDE_TAKE_ONLY: keeps Examine entries for non-priority items, removes only the Take entries.
     */
    private List<MenuEntry> buildHideModeEntries(MenuEntry[] entries, List<ClassifiedMenuItem> groundItems,
            boolean takeOnly) {
        Set<Integer> excludedIndices = new HashSet<>();

        for (ClassifiedMenuItem item : groundItems) {
            boolean isExamine = item.getEntry().getType() == MenuAction.EXAMINE_ITEM_GROUND;

            if (item.isPriority()) {
                // Priority item: only suppress Examine if prioritizeExamine is off
                if (isExamine && !config.prioritizeExamine()) {
                    excludedIndices.add(item.getOriginalIndex());
                }
            } else {
                // Non-priority item
                if (takeOnly && isExamine) {
                    // HIDE_TAKE_ONLY: keep the Examine entry, drop only Take entries
                } else {
                    excludedIndices.add(item.getOriginalIndex());
                }
            }
        }

        List<MenuEntry> result = new ArrayList<>(entries.length);
        for (int i = 0; i < entries.length; i++) {
            if (!excludedIndices.contains(i)) {
                result.add(entries[i]);
            }
        }
        log.info("[Menu] {} mode: {} entries removed", takeOnly ? "HIDE_TAKE_ONLY" : "HIDE", excludedIndices.size());
        return result;
    }

    /**
     * DEPRIORITIZE mode: reorders ground items in the menu.
     *
     * Final menu order (bottom to top, matching RuneLite's index convention):
     *   Cancel → deprioritized ground items → other entries (Walk here, NPC, …) → priority ground items
     */
    private List<MenuEntry> buildDeprioritizeModeEntries(MenuEntry[] entries, List<ClassifiedMenuItem> groundItems) {
        // Index the pre-classified items by their MenuEntry reference for O(1) lookup
        Map<MenuEntry, ClassifiedMenuItem> classified = new IdentityHashMap<>();
        for (ClassifiedMenuItem item : groundItems) {
            classified.put(item.getEntry(), item);
        }

        List<MenuEntry> cancelEntries = new ArrayList<>();
        List<ClassifiedMenuItem> prioritized = new ArrayList<>();
        List<ClassifiedMenuItem> deprioritized = new ArrayList<>();
        List<MenuEntry> otherEntries = new ArrayList<>();

        for (MenuEntry entry : entries) {
            MenuAction action = entry.getType();

            if (action == MenuAction.CANCEL || "Cancel".equalsIgnoreCase(entry.getOption())) {
                cancelEntries.add(entry);
            } else if (isGroundItemAction(action)) {
                ClassifiedMenuItem item = classified.get(entry);
                if (item == null) {
                    // Should not happen, but fall back to treating as deprioritized
                    otherEntries.add(entry);
                    continue;
                }
                boolean isExamine = action == MenuAction.EXAMINE_ITEM_GROUND;
                boolean shouldDeprioritize = !item.isPriority()
                        || (isExamine && !config.prioritizeExamine());

                if (shouldDeprioritize) {
                    deprioritized.add(item);
                } else {
                    prioritized.add(item);
                }
            } else {
                otherEntries.add(entry);
            }
        }

        if (!config.prioritizeExamine()) {
            deprioritized.sort((a, b) -> Integer.compare(deprioritizedCategory(a), deprioritizedCategory(b)));
        }

        List<MenuEntry> result = new ArrayList<>(entries.length);
        cancelEntries.forEach(result::add);
        deprioritized.forEach(item -> result.add(item.getEntry()));
        otherEntries.forEach(result::add);
        prioritized.forEach(item -> result.add(item.getEntry()));

        log.info("[Menu] DEPRIORITIZE: {} priority, {} deprioritized", prioritized.size(), deprioritized.size());
        return result;
    }

    private int deprioritizedCategory(ClassifiedMenuItem item) {
        boolean isExamine = item.getEntry().getType() == MenuAction.EXAMINE_ITEM_GROUND;
        if (item.isPriority()) return 1;   // priority Examine (deprioritized due to prioritizeExamine=false)
        return isExamine ? 0 : 2;          // non-priority Examine (0) vs non-priority Take (2)
    }

    /**
     * Applies cosmetic modifications (priority marker and value annotation) to ground item
     * entries that survived mode processing. Called on the final result list so that
     * classification has already happened and targets haven't been changed yet.
     */
    private void applyDisplayModifications(List<MenuEntry> result, List<ClassifiedMenuItem> groundItems) {
        boolean showMarker = config.enablePriorityMarker();
        boolean showValue = config.showItemValueInMenu();
        if (!showMarker && !showValue) return;

        // Map entry reference → classified item for the entries still in the result
        Map<MenuEntry, ClassifiedMenuItem> entryMap = new IdentityHashMap<>();
        for (ClassifiedMenuItem item : groundItems) {
            entryMap.put(item.getEntry(), item);
        }

        PriorityMarker markerStyle = config.priorityMarker();
        boolean markerActive = showMarker && markerStyle != null && markerStyle != PriorityMarker.NONE;

        for (MenuEntry entry : result) {
            ClassifiedMenuItem item = entryMap.get(entry);
            if (item == null) continue; // Non-ground entry

            String target = entry.getTarget();

            if (showValue && classifier != null) {
                int value = classifier.getItemDisplayValue(entry);
                if (value > 0) {
                    target = target + " <col=aaaaaa>(" + formatValue(value) + ")</col>";
                }
            }

            if (markerActive && item.isPriority()) {
                target = markerStyle.getSymbol() + " " + target;
            }

            if (!target.equals(entry.getTarget())) {
                entry.setTarget(target);
            }
        }
    }

    private String formatValue(int value) {
        if (value >= 1_000_000) {
            double m = value / 1_000_000.0;
            return (m == (long) m) ? (long) m + "m" : String.format("%.1fm", m);
        }
        if (value >= 1_000) {
            double k = value / 1_000.0;
            return (k == (long) k) ? (long) k + "k" : String.format("%.1fk", k);
        }
        return String.valueOf(value);
    }

    private boolean isGroundItemAction(MenuAction action) {
        return action == MenuAction.GROUND_ITEM_FIRST_OPTION
                || action == MenuAction.GROUND_ITEM_SECOND_OPTION
                || action == MenuAction.GROUND_ITEM_THIRD_OPTION
                || action == MenuAction.GROUND_ITEM_FOURTH_OPTION
                || action == MenuAction.GROUND_ITEM_FIFTH_OPTION
                || action == MenuAction.EXAMINE_ITEM_GROUND;
    }

    private String normalizeItemName(String itemName) {
        itemName = net.runelite.client.util.Text.removeTags(itemName);
        if (config.supportCollapsedItems()) {
            itemName = itemName.replaceAll("\\s+x\\s+\\d+$", "");
        }
        return itemName.replaceAll("\\s*\\([^)]*\\)$", "").trim();
    }

    // ─── Accessors for overlay ──────────────────────────────────────────────

    public String getCurrentTask() { return currentTask; }
    public String getCurrentNpc() { return currentNpc; }
    public int getCurrentNpcId() { return currentNpcId; }
    public int getDropCount() { return currentTaskDrops.size(); }
    public String getLastWikiPage() { return lastWikiPage; }
    public boolean isInCombatGrace() {
        return (client.getTickCount() - lastCombatTick) <= config.combatTimeout();
    }
}
