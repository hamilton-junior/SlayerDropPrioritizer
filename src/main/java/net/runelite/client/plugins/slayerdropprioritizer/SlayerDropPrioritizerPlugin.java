package net.runelite.client.plugins.slayerdropprioritizer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.TileItem;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemSpawned;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.Notifier;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
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
import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
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

    @Inject
    private ClientThread clientThread;

    @Inject
    private ScheduledExecutorService executor;

    @Inject
    private Notifier notifier;

    private String currentTask = "";
    // Only mutated on the client thread (onGameTick / onMenuOpened / clientThread.invoke callbacks).
    final Set<String> currentTaskDrops = new HashSet<>();
    final Map<String, Double> currentDropRarity = new HashMap<>();

    // resolvedNpcIds is only touched on the client thread.
    private final Set<Integer> resolvedNpcIds = new HashSet<>();
    // Caches are read on the client thread and written from background/OkHttp threads.
    private final Map<Integer, Set<String>> dropCache = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Double>> dropRarityCache = new ConcurrentHashMap<>();

    private PriorityItemClassifier classifier;

    int lastCombatTick = -100;

    private String currentNpc = "";
    private int currentNpcId = -1;
    private volatile String lastWikiPage = "";

    // Guards background tasks so they no-op after the plugin shuts down.
    private volatile boolean active = false;

    private static final String CONFIG_GROUP = "slayerdropprioritizer";
    private static final long CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000; // 7 days
    private static final File CACHE_DIR = new File(RuneLite.RUNELITE_DIR, "slayer-drop-prioritizer");
    // OSRS Wiki etiquette: identify the client with a descriptive User-Agent.
    private static final String USER_AGENT = "RuneLite-SlayerDropPrioritizer/1.0";

    // Captures numerator and denominator (both may be decimal), e.g. "3/128" or "1/5000".
    private static final Pattern RARITY_FRACTION_PATTERN =
            Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)");
    // Leading <img=N> tag, e.g. the placeholder prepended by the Ground Loot Icons plugin.
    private static final Pattern LEADING_IMG_PATTERN = Pattern.compile("^<img=\\d+>");

    // Rendered drop-table parsing (prop=text HTML). Using the rendered page expands macro
    // templates like {{HerbDropLines}}, {{RareDropTable}}, {{GemDropTable}} into real rows,
    // which raw {{DropsLine}} wikitext parsing missed (e.g. herbs were absent).
    private static final Pattern DROP_TABLE_PATTERN =
            Pattern.compile("(?s)<table\\b[^>]*\\bitem-drops\\b[^>]*>(.*?)</table>");
    private static final Pattern TABLE_ROW_PATTERN =
            Pattern.compile("(?s)<tr\\b[^>]*>(.*?)</tr>");
    private static final Pattern LINK_TITLE_PATTERN =
            Pattern.compile("title=\"([^\"]+)\"");
    private static final Pattern HTML_TAG_PATTERN =
            Pattern.compile("<[^>]+>");

    @Provides
    SlayerDropPrioritizerConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(SlayerDropPrioritizerConfig.class);
    }

    private boolean overlayActive = false;

    @Override
    protected void startUp() {
        log.info("[Status] Plugin Started");
        active = true;
        migrateConfig();
        migrateRarityConfig();
        migratePrioritizationMode();
        migratePriorityMarker();
        classifier = new PriorityItemClassifier(itemManager, client, currentTaskDrops, currentDropRarity, config);
        syncOverlay();
        checkTask();
    }

    /**
     * Migrates the legacy tick-based "combatTimeout" key to the seconds-based
     * "combatTimeoutSeconds" key (~0.6 seconds per game tick). Runs once: the old key is
     * removed afterwards so the conversion is not repeated.
     */
    private void migrateConfig() {
        String oldValue = configManager.getConfiguration(CONFIG_GROUP, "combatTimeout");
        if (oldValue == null) {
            return;
        }

        boolean alreadyMigrated =
                configManager.getConfiguration(CONFIG_GROUP, "combatTimeoutSeconds") != null;
        if (!alreadyMigrated) {
            try {
                int ticks = Integer.parseInt(oldValue.trim());
                int seconds = Math.max(0, Math.min(600, (int) Math.round(ticks * 0.6)));
                configManager.setConfiguration(CONFIG_GROUP, "combatTimeoutSeconds", seconds);
                log.info("[Migration] combatTimeout {} ticks -> combatTimeoutSeconds {}", ticks, seconds);
            } catch (NumberFormatException e) {
                log.warn("[Migration] Could not parse legacy combatTimeout value: {}", oldValue);
            }
        }
        configManager.unsetConfiguration(CONFIG_GROUP, "combatTimeout");
    }

    /**
     * Migrates the legacy "maxRareDenominator" key to the new "rareThreshold" key.
     * The numeric value carries over directly; the old (inverted) comparison is gone.
     */
    private void migrateRarityConfig() {
        String oldValue = configManager.getConfiguration(CONFIG_GROUP, "maxRareDenominator");
        if (oldValue == null) {
            return;
        }
        if (configManager.getConfiguration(CONFIG_GROUP, "rareThreshold") == null) {
            try {
                int n = Integer.parseInt(oldValue.trim());
                configManager.setConfiguration(CONFIG_GROUP, "rareThreshold", n);
                log.info("[Migration] maxRareDenominator {} -> rareThreshold {}", n, n);
            } catch (NumberFormatException e) {
                log.warn("[Migration] Could not parse legacy maxRareDenominator value: {}", oldValue);
            }
        }
        configManager.unsetConfiguration(CONFIG_GROUP, "maxRareDenominator");
    }

    /**
     * Migrates the removed "prioritizationMode" key. The old VALUABLE_TASK_DROPS mode is
     * equivalent to enabling "Interesting Drops Only"; ALL_TASK_DROPS is the default behavior.
     */
    private void migratePrioritizationMode() {
        String oldValue = configManager.getConfiguration(CONFIG_GROUP, "prioritizationMode");
        if (oldValue == null) {
            return;
        }
        if ("VALUABLE_TASK_DROPS".equals(oldValue.trim())
                && configManager.getConfiguration(CONFIG_GROUP, "interestingDropsOnly") == null) {
            configManager.setConfiguration(CONFIG_GROUP, "interestingDropsOnly", true);
            log.info("[Migration] prioritizationMode=VALUABLE_TASK_DROPS -> interestingDropsOnly=true");
        }
        configManager.unsetConfiguration(CONFIG_GROUP, "prioritizationMode");
    }

    /** Migrates the removed "priorityMarker" enum key to the free-text "markerText" key. */
    private void migratePriorityMarker() {
        String oldValue = configManager.getConfiguration(CONFIG_GROUP, "priorityMarker");
        if (oldValue == null) {
            return;
        }
        if (configManager.getConfiguration(CONFIG_GROUP, "markerText") == null) {
            String symbol;
            switch (oldValue.trim()) {
                case "ARROW": symbol = ">"; break;
                case "PLUS": symbol = "+"; break;
                case "DASH": symbol = "-"; break;
                case "NONE": symbol = ""; break;
                case "STAR":
                default: symbol = "*"; break;
            }
            configManager.setConfiguration(CONFIG_GROUP, "markerText", symbol);
            log.info("[Migration] priorityMarker={} -> markerText='{}'", oldValue, symbol);
        }
        configManager.unsetConfiguration(CONFIG_GROUP, "priorityMarker");
    }

    /** Adds or removes the debug overlay to match the showDebugOverlay config. */
    private void syncOverlay() {
        if (config.showDebugOverlay() && !overlayActive) {
            overlayManager.add(overlay);
            overlayActive = true;
        } else if (!config.showDebugOverlay() && overlayActive) {
            overlayManager.remove(overlay);
            overlayActive = false;
        }
    }

    @Override
    protected void shutDown() {
        active = false;
        overlayManager.remove(overlay);
        overlayActive = false;
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
        if (!CONFIG_GROUP.equals(event.getGroup())) {
            return;
        }

        // "Clear Cache & Refresh" acts as a button: it resets itself after running.
        if ("clearCacheNow".equals(event.getKey()) && config.clearCacheNow()) {
            clearCaches();
            configManager.setConfiguration(CONFIG_GROUP, "clearCacheNow", false);
            return;
        }

        if (classifier != null) {
            classifier.refreshCustomLists();
        }
        syncOverlay();
        checkTask();
    }

    /**
     * Clears in-memory and on-disk drop caches and forces the current task to re-resolve.
     * Disk deletion runs off the client thread.
     */
    private void clearCaches() {
        dropCache.clear();
        dropRarityCache.clear();
        resolvedNpcIds.clear();
        currentTaskDrops.clear();
        currentDropRarity.clear();
        currentTask = "";

        executor.execute(() -> {
            File[] files = CACHE_DIR.listFiles((dir, fileName) -> fileName.endsWith(".json"));
            if (files != null) {
                for (File file : files) {
                    if (!file.delete()) {
                        log.debug("[Cache] Could not delete {}", file);
                    }
                }
            }
            log.info("[Cache] Cleared drop table cache");
        });

        // Re-resolve the active task on the client thread.
        clientThread.invoke(this::checkTask);
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

            Set<String> memoryDrops = dropCache.get(target.getId());
            if (memoryDrops != null) {
                currentTaskDrops.clear();
                currentTaskDrops.addAll(memoryDrops);
                currentDropRarity.clear();
                Map<String, Double> cachedRarity = dropRarityCache.get(target.getId());
                if (cachedRarity != null) {
                    currentDropRarity.putAll(cachedRarity);
                }
            } else if (!resolvedNpcIds.contains(target.getId())) {
                log.info("[Drops] Resolving drops for {} ({})", target.getName(), target.getId());
                resolvedNpcIds.add(target.getId());
                loadDrops(target.getId());
            }
        }
    }

    /**
     * Resolves an NPC's drop table on a background thread: tries the disk cache first,
     * then falls back to the OSRS Wiki. Disk and network IO never run on the client thread.
     */
    private void loadDrops(int npcId) {
        executor.execute(() -> {
            if (!active) {
                return;
            }
            CachedDropTable cached = readDiskCache(npcId);
            if (isFresh(cached)) {
                log.debug("[Cache] Disk hit for NPC id={} ({} drops)", npcId, cached.drops.size());
                applyDrops(npcId, cached.drops, cached.rarities, false);
            } else {
                resolveWikiPage(npcId);
            }
        });
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

        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("[Wiki] Lookup failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        log.error("[Wiki] Lookup unsuccessful: HTTP {}", response.code());
                        return;
                    }
                    List<String> pathSegments = response.request().url().pathSegments();
                    if (pathSegments.size() < 2 || !"w".equals(pathSegments.get(0))) {
                        log.error("[Wiki] Unexpected URL format: {}", response.request().url());
                        return;
                    }
                    String page = pathSegments.get(1);
                    lastWikiPage = page;
                    log.info("[Wiki] Resolved page: {}", page);
                    fetchDrops(page, npcId);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void fetchDrops(String page, int npcId) {
        okhttp3.HttpUrl url = new okhttp3.HttpUrl.Builder()
                .scheme("https")
                .host("oldschool.runescape.wiki")
                .addPathSegment("api.php")
                .addQueryParameter("action", "parse")
                .addQueryParameter("page", page)
                .addQueryParameter("format", "json")
                .addQueryParameter("prop", "text")
                .build();

        Request request = new Request.Builder().url(url).header("User-Agent", USER_AGENT).build();
        okHttpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                log.error("[Wiki] Fetch failed", e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        return;
                    }

                    String responseBody = response.body().string();

                    JsonObject jsonObject = gson.fromJson(responseBody, JsonObject.class);
                    if (jsonObject == null || !jsonObject.has("parse")) {
                        log.error("[Wiki] Invalid API response");
                        return;
                    }
                    JsonObject parse = jsonObject.getAsJsonObject("parse");
                    if (parse == null || !parse.has("text")) return;
                    JsonObject text = parse.getAsJsonObject("text");
                    if (text == null || !text.has("*")) return;

                    String html = text.get("*").getAsString();
                    parseDropsFromHtml(html, npcId);
                } finally {
                    response.close();
                }
            }
        });
    }

    /**
     * Parses item name + rarity from the rendered drop tables (HTML). Operating on the rendered
     * page expands macro templates ({{HerbDropLines}}, {{RareDropTable}}, {{GemDropTable}}, …)
     * into real rows, so herbs and similar drops are captured. Rarity is stored as the effective
     * denominator (a "3/128" drop becomes ~42.67).
     */
    private void parseDropsFromHtml(String html, int npcId) {
        Set<String> drops = new HashSet<>();
        Map<String, Double> rarityMap = new HashMap<>();

        Matcher tableMatcher = DROP_TABLE_PATTERN.matcher(html);
        while (tableMatcher.find()) {
            Matcher rowMatcher = TABLE_ROW_PATTERN.matcher(tableMatcher.group(1));
            while (rowMatcher.find()) {
                String row = rowMatcher.group(1);

                // Item name = the title attribute of the first item link in the row.
                Matcher titleMatcher = LINK_TITLE_PATTERN.matcher(row);
                if (!titleMatcher.find()) {
                    continue; // header / separator row
                }
                String itemName = decodeHtml(titleMatcher.group(1)).trim();
                if (itemName.isEmpty()) {
                    continue;
                }
                drops.add(itemName);

                // Rarity = the only "N/M" fraction in the row's visible text (quantity and
                // price columns never contain a slash).
                String rowText = HTML_TAG_PATTERN.matcher(row).replaceAll(" ").replace(",", "");
                Matcher fractionMatcher = RARITY_FRACTION_PATTERN.matcher(rowText);
                if (fractionMatcher.find()) {
                    double numerator = Double.parseDouble(fractionMatcher.group(1));
                    double denominator = Double.parseDouble(fractionMatcher.group(2));
                    if (numerator > 0) {
                        rarityMap.put(itemName, denominator / numerator);
                    }
                }
            }
        }

        log.info("[Wiki] Loaded {} drops ({} with rarity) for NPC id={}", drops.size(), rarityMap.size(), npcId);
        applyDrops(npcId, drops, rarityMap, true);
    }

    /** Decodes the handful of HTML entities that appear in OSRS Wiki item names. */
    private static String decodeHtml(String s) {
        return s.replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&#039;", "'")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ")
                .replace("&#160;", " ");
    }

    /**
     * Persists (optionally) and applies a resolved drop table. Cache maps are thread-safe;
     * the "current" task collections are only touched on the client thread.
     */
    private void applyDrops(int npcId, Set<String> drops, Map<String, Double> rarities, boolean writeDisk) {
        if (!active) {
            return;
        }
        if (writeDisk) {
            writeDiskCache(npcId, drops, rarities);
        }
        dropCache.put(npcId, new HashSet<>(drops));
        dropRarityCache.put(npcId, new HashMap<>(rarities));

        clientThread.invoke(() -> {
            // Only adopt as the active table if this NPC is still the one we care about.
            if (npcId == currentNpcId) {
                currentTaskDrops.clear();
                currentTaskDrops.addAll(drops);
                currentDropRarity.clear();
                currentDropRarity.putAll(rarities);
            }
        });
    }

    // ─── Disk cache ─────────────────────────────────────────────────────────

    private CachedDropTable readDiskCache(int npcId) {
        if (!config.cacheDropTables()) {
            return null;
        }
        File file = new File(CACHE_DIR, npcId + ".json");
        if (!file.exists()) {
            return null;
        }
        try (Reader reader = new FileReader(file)) {
            return gson.fromJson(reader, CachedDropTable.class);
        } catch (Exception e) {
            log.debug("[Cache] Failed to read {}", file, e);
            return null;
        }
    }

    private boolean isFresh(CachedDropTable cached) {
        return cached != null
                && cached.version == CachedDropTable.CURRENT_VERSION
                && cached.drops != null
                && !cached.drops.isEmpty()
                && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS;
    }

    private void writeDiskCache(int npcId, Set<String> drops, Map<String, Double> rarities) {
        if (!config.cacheDropTables() || drops.isEmpty()) {
            return;
        }
        try {
            if (!CACHE_DIR.exists() && !CACHE_DIR.mkdirs()) {
                log.debug("[Cache] Could not create cache dir {}", CACHE_DIR);
                return;
            }
            CachedDropTable table = new CachedDropTable(
                    CachedDropTable.CURRENT_VERSION, System.currentTimeMillis(),
                    new HashSet<>(drops), new HashMap<>(rarities));
            File file = new File(CACHE_DIR, npcId + ".json");
            try (Writer writer = new FileWriter(file)) {
                gson.toJson(table, writer);
            }
        } catch (Exception e) {
            log.debug("[Cache] Failed to write cache for NPC id={}", npcId, e);
        }
    }

    // ─── Notifications ──────────────────────────────────────────────────────

    @Subscribe
    public void onItemSpawned(ItemSpawned event) {
        if (!config.notifyPriorityDrops() || classifier == null) {
            return;
        }
        if (currentTaskDrops.isEmpty() || !isInCombatGrace()) {
            return;
        }

        TileItem item = event.getItem();
        int itemId = item.getId();
        int quantity = item.getQuantity();
        String name = normalizeItemName(itemManager.getItemComposition(itemId).getName());

        if (!isPriorityDrop(name, itemId, quantity)) {
            return;
        }

        RarityTier tier = rarityTierFor(name);
        if (!meetsNotifyTier(tier)) {
            return;
        }

        notifier.notify(notificationMessage(tier, name));
        log.debug("[Notify] Priority drop spawned: {} (id={}, qty={}, tier={})", name, itemId, quantity, tier);
    }

    private boolean meetsNotifyTier(RarityTier tier) {
        switch (config.notifyMinTier()) {
            case ULTRA_RARE:
                return tier == RarityTier.ULTRA_RARE;
            case RARE:
                return tier == RarityTier.RARE || tier == RarityTier.ULTRA_RARE;
            case ALL_PRIORITY:
            default:
                return true;
        }
    }

    private String notificationMessage(RarityTier tier, String name) {
        switch (tier) {
            case ULTRA_RARE:
                return "Ultra-rare Slayer drop: " + name;
            case RARE:
                return "Rare Slayer drop: " + name;
            default:
                return "Slayer priority drop: " + name;
        }
    }

    /**
     * Effective priority for a raw item (no MenuEntry), used for spawn notifications.
     * Mirrors {@link #effectivePriority(ClassifiedMenuItem)} but classifies from name/id/qty.
     */
    private boolean isPriorityDrop(String name, int itemId, int quantity) {
        boolean base = classifier.isPriority(name, itemId, quantity);
        return applyClueRules(base, classifier.isClueScroll(name));
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
        if (!isInCombatGrace()) {
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

        DropDisplayMode mode = config.dropDisplayMode();
        boolean decorate = config.enablePriorityMarker() || config.showItemValueInMenu()
                || config.highlightTaskItems() || config.showRarityInMenu();

        if (mode == DropDisplayMode.HIDE || mode == DropDisplayMode.HIDE_TAKE_ONLY) {
            List<MenuEntry> result = buildHideModeEntries(entries, groundItems, mode == DropDisplayMode.HIDE_TAKE_ONLY);
            applyDisplayModifications(result, groundItems);
            client.setMenuEntries(result.toArray(new MenuEntry[0]));
        } else if (mode == DropDisplayMode.DEPRIORITIZE) {
            List<MenuEntry> result = buildDeprioritizeModeEntries(entries, groundItems);
            applyDisplayModifications(result, groundItems);
            client.setMenuEntries(result.toArray(new MenuEntry[0]));
        } else if (decorate) {
            // SHOW mode with cosmetic decorations only
            List<MenuEntry> result = new ArrayList<>(Arrays.asList(entries));
            applyDisplayModifications(result, groundItems);
            client.setMenuEntries(result.toArray(new MenuEntry[0]));
        }

        // Management entries operate on the (possibly rebuilt) live menu, in every mode.
        if (config.addManageMenuEntries()) {
            addManagementEntries(groundItems);
        }
    }

    /**
     * Adds local-only "Prioritize"/"Ignore" right-click options for each unique ground item,
     * toggling the item in the Always Priority / Always Ignore config lists. These are
     * {@link MenuAction#RUNELITE} entries with an onClick handler — they never hit the server.
     * Inserted near the bottom of the menu so they never become the default left-click action.
     */
    private void addManagementEntries(List<ClassifiedMenuItem> groundItems) {
        Set<String> seen = new HashSet<>();
        for (ClassifiedMenuItem item : groundItems) {
            String name = item.getNormalizedItemName();
            if (name.isEmpty() || !seen.add(name.toLowerCase())) {
                continue;
            }

            boolean inPriority = listContains(configManager.getConfiguration(CONFIG_GROUP, "alwaysPriorityItems"), name);
            boolean inIgnore = listContains(configManager.getConfiguration(CONFIG_GROUP, "alwaysIgnoreItems"), name);
            String coloredName = "<col=ff9040>" + name + "</col>";

            client.createMenuEntry(1)
                    .setOption(inIgnore ? "Unignore" : "Ignore")
                    .setTarget(coloredName)
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> setListMembership("alwaysIgnoreItems", "alwaysPriorityItems", name));

            client.createMenuEntry(1)
                    .setOption(inPriority ? "Unprioritize" : "Prioritize")
                    .setTarget(coloredName)
                    .setType(MenuAction.RUNELITE)
                    .onClick(e -> setListMembership("alwaysPriorityItems", "alwaysIgnoreItems", name));
        }
    }

    private boolean listContains(String value, String itemName) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (String token : value.split("[,\\r\\n]+")) {
            if (token.trim().equalsIgnoreCase(itemName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Toggles an item in {@code targetKey}. When adding it, it is also removed from
     * {@code exclusiveKey} so an item is never both prioritized and ignored at once.
     */
    private void setListMembership(String targetKey, String exclusiveKey, String itemName) {
        String listLabel = "alwaysPriorityItems".equals(targetKey) ? "priority" : "ignore";
        boolean alreadyThere = listContains(configManager.getConfiguration(CONFIG_GROUP, targetKey), itemName);
        if (alreadyThere) {
            removeFromList(targetKey, itemName);
            log.debug("[Manage] Removed '{}' from {}", itemName, targetKey);
            sendManageFeedback("Removed " + itemName + " from the " + listLabel + " list.");
        } else {
            addToList(targetKey, itemName);
            removeFromList(exclusiveKey, itemName);
            log.debug("[Manage] Added '{}' to {} (removed from {})", itemName, targetKey, exclusiveKey);
            sendManageFeedback("Added " + itemName + " to the " + listLabel + " list.");
        }
    }

    /** Confirms a list change in the game chat (the config panel does not refresh live). */
    private void sendManageFeedback(String message) {
        client.addChatMessage(ChatMessageType.GAMEMESSAGE, "",
                "[Slayer Drop Prioritizer] " + message, null);
    }

    private void addToList(String configKey, String itemName) {
        String current = configManager.getConfiguration(CONFIG_GROUP, configKey);
        if (listContains(current, itemName)) {
            return;
        }
        List<String> lines = splitList(current);
        lines.add(itemName);
        configManager.setConfiguration(CONFIG_GROUP, configKey, String.join("\n", lines));
    }

    private void removeFromList(String configKey, String itemName) {
        String current = configManager.getConfiguration(CONFIG_GROUP, configKey);
        if (current == null || current.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (String token : splitList(current)) {
            if (!token.equalsIgnoreCase(itemName)) {
                lines.add(token);
            }
        }
        configManager.setConfiguration(CONFIG_GROUP, configKey, String.join("\n", lines));
    }

    private List<String> splitList(String raw) {
        List<String> lines = new ArrayList<>();
        if (raw == null) {
            return lines;
        }
        for (String token : raw.split("[,\\r\\n]+")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
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
            boolean clue = classifier != null && classifier.isClueScroll(normalizedName);

            groundItems.add(new ClassifiedMenuItem(entry, i, normalizedName, normalizedOption, priority, itemId, 0, clue));
        }
        return groundItems;
    }

    /**
     * Effective priority for an item, layering clue-scroll rules on top of the base classification.
     * Clue scrolls are forced to priority when "Prioritize Clue Scrolls" is set, or when
     * "Show Clue Scrolls" is ALWAYS; forced to non-priority when it is NEVER.
     */
    private boolean effectivePriority(ClassifiedMenuItem item) {
        return applyClueRules(item.isPriority(), item.isClueScroll());
    }

    /**
     * Layers the clue-scroll rules on top of a base priority decision.
     * Forces priority when "Prioritize Clue Scrolls" is set, or when "Show Clue Scrolls"
     * is ALWAYS; forces non-priority when it is NEVER; otherwise keeps the base decision.
     */
    private boolean applyClueRules(boolean basePriority, boolean clueScroll) {
        if (!clueScroll) {
            return basePriority;
        }
        if (config.prioritizeClueScrolls() != ClueScrollPriority.OFF) {
            return true;
        }
        switch (config.showClueScrolls()) {
            case ALWAYS:
                return true;
            case NEVER:
                return false;
            case MODE:
            default:
                return basePriority;
        }
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

            if (effectivePriority(item)) {
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
     *   Cancel → deprioritized → other entries (Walk here, NPC, …)
     *         → clue scrolls (AFTER) → priority drops → clue scrolls (BEFORE)
     */
    private List<MenuEntry> buildDeprioritizeModeEntries(MenuEntry[] entries, List<ClassifiedMenuItem> groundItems) {
        // Index the pre-classified items by their MenuEntry reference for O(1) lookup
        Map<MenuEntry, ClassifiedMenuItem> classified = new IdentityHashMap<>();
        for (ClassifiedMenuItem item : groundItems) {
            classified.put(item.getEntry(), item);
        }

        List<MenuEntry> cancelEntries = new ArrayList<>();
        List<ClassifiedMenuItem> prioritized = new ArrayList<>();
        List<ClassifiedMenuItem> clueBefore = new ArrayList<>();
        List<ClassifiedMenuItem> clueAfter = new ArrayList<>();
        List<ClassifiedMenuItem> deprioritized = new ArrayList<>();
        List<MenuEntry> otherEntries = new ArrayList<>();

        ClueScrollPriority cluePriority = config.prioritizeClueScrolls();

        for (MenuEntry entry : entries) {
            MenuAction action = entry.getType();

            if (action == MenuAction.CANCEL || "Cancel".equalsIgnoreCase(entry.getOption())) {
                cancelEntries.add(entry);
            } else if (isGroundItemAction(action)) {
                ClassifiedMenuItem item = classified.get(entry);
                if (item == null) {
                    // Should not happen, but fall back to leaving the entry where it is
                    otherEntries.add(entry);
                    continue;
                }
                boolean isExamine = action == MenuAction.EXAMINE_ITEM_GROUND;
                boolean shouldDeprioritize = !effectivePriority(item)
                        || (isExamine && !config.prioritizeExamine());

                if (shouldDeprioritize) {
                    deprioritized.add(item);
                } else if (item.isClueScroll() && cluePriority == ClueScrollPriority.BEFORE) {
                    clueBefore.add(item);
                } else if (item.isClueScroll() && cluePriority == ClueScrollPriority.AFTER) {
                    clueAfter.add(item);
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
        clueAfter.forEach(item -> result.add(item.getEntry()));
        prioritized.forEach(item -> result.add(item.getEntry()));
        clueBefore.forEach(item -> result.add(item.getEntry()));

        log.info("[Menu] DEPRIORITIZE: {} priority, {} clue(before)/{} clue(after), {} deprioritized",
                prioritized.size(), clueBefore.size(), clueAfter.size(), deprioritized.size());
        return result;
    }

    private int deprioritizedCategory(ClassifiedMenuItem item) {
        boolean isExamine = item.getEntry().getType() == MenuAction.EXAMINE_ITEM_GROUND;
        if (effectivePriority(item)) return 1;   // priority Examine (deprioritized due to prioritizeExamine=false)
        return isExamine ? 0 : 2;                // non-priority Examine (0) vs non-priority Take (2)
    }

    /**
     * Applies cosmetic modifications (highlight color, value annotation, priority marker)
     * to ground item entries that survived mode processing. Called on the final result list
     * so classification has already happened and targets haven't been changed yet.
     */
    private void applyDisplayModifications(List<MenuEntry> result, List<ClassifiedMenuItem> groundItems) {
        boolean showMarker = config.enablePriorityMarker();
        boolean showValue = config.showItemValueInMenu();
        boolean highlight = config.highlightTaskItems();
        boolean showRarity = config.showRarityInMenu();
        if (!showMarker && !showValue && !highlight && !showRarity) return;

        // Map entry reference → classified item for the entries still in the result
        Map<MenuEntry, ClassifiedMenuItem> entryMap = new IdentityHashMap<>();
        for (ClassifiedMenuItem item : groundItems) {
            entryMap.put(item.getEntry(), item);
        }

        String markerText = config.markerText() == null ? "" : config.markerText().trim();
        boolean markerActive = showMarker && !markerText.isEmpty();
        String markerRendered = config.colorMarker()
                ? "<col=" + colorToHex(config.markerColor()) + ">" + markerText + "</col>"
                : markerText;

        for (MenuEntry entry : result) {
            ClassifiedMenuItem item = entryMap.get(entry);
            if (item == null) continue; // Non-ground entry

            boolean priority = effectivePriority(item);
            String fullTarget = entry.getTarget();

            // Detach a leading Ground Loot Icons image tag so it always stays at the very
            // front of the target. Its overlay draws the icon at a fixed X (the option width),
            // so the placeholder must remain first; our marker goes right after it.
            String imgPrefix = "";
            String target = fullTarget;
            Matcher imgMatcher = LEADING_IMG_PATTERN.matcher(fullTarget);
            if (imgMatcher.find()) {
                imgPrefix = fullTarget.substring(0, imgMatcher.end());
                target = fullTarget.substring(imgMatcher.end());
            }

            boolean isExamine = entry.getType() == MenuAction.EXAMINE_ITEM_GROUND;
            int displayValue = (showValue || showRarity) && classifier != null
                    ? classifier.getItemDisplayValue(entry) : 0;

            // 1. Highlight (recolor the name and/or the option) for priority items.
            //    Examine entries are only colored when "Highlight Examine" is enabled.
            if (highlight && priority && (!isExamine || config.highlightExamine())
                    && passesHighlightValueGate(entry)) {
                String hex = highlightHexFor(entry, item);
                HighlightPart part = config.highlightPart();
                if (part != HighlightPart.OPTION) {
                    target = recolor(target, hex);
                }
                if (part != HighlightPart.NAME) {
                    String newOption = recolor(entry.getOption(), hex);
                    if (!newOption.equals(entry.getOption())) {
                        entry.setOption(newOption);
                    }
                }
            }

            // 2. Append value annotation (with gp unit)
            if (showValue && displayValue > 0) {
                String hex = config.useGroundItemsColors()
                        ? colorToHex(groundItemsColorForValue(displayValue))
                        : colorToHex(config.valueColor());
                target = target + " <col=" + hex + ">(" + formatValue(displayValue) + " gp)</col>";
            }

            // 3. Append rarity annotation (task drops with a known Wiki drop rate)
            if (showRarity) {
                Double denominator = currentDropRarity.get(item.getNormalizedItemName());
                if (denominator != null) {
                    String hex = config.useGroundItemsColors()
                            ? colorToHex(groundItemsColorForValue(displayValue))
                            : colorToHex(config.rarityColor());
                    target = target + " <col=" + hex + ">(1/" + Math.round(denominator) + ")</col>";
                }
            }

            // 4. Prepend the priority marker (after the icon placeholder, before the name)
            if (markerActive && priority) {
                target = markerRendered + " " + target;
            }

            String newTarget = imgPrefix + target;
            if (!newTarget.equals(fullTarget)) {
                entry.setTarget(newTarget);
            }
        }
    }

    private boolean passesHighlightValueGate(MenuEntry entry) {
        if (!config.highlightAboveValueOnly()) {
            return true;
        }
        return classifier != null && classifier.getPriorityValue(entry) >= config.minimumPriorityValue();
    }

    /**
     * Picks the highlight color for an item. With tiered colors enabled, items are colored by
     * (in order) Ultra-Rare, Rare, Valuable, then common; otherwise the single highlight color.
     */
    private String highlightHexFor(MenuEntry entry, ClassifiedMenuItem item) {
        if (!config.highlightByTier()) {
            return colorToHex(config.highlightColor());
        }

        switch (rarityTierFor(item.getNormalizedItemName())) {
            case ULTRA_RARE:
                return colorToHex(config.highlightColorUltraRare());
            case RARE:
                return colorToHex(config.highlightColorRare());
            default:
                break;
        }

        int min = config.minimumPriorityValue();
        if (min > 0 && classifier != null && classifier.getPriorityValue(entry) >= min) {
            return colorToHex(config.highlightColorValuable());
        }

        return colorToHex(config.highlightColor());
    }

    /**
     * Classifies an item into a rarity tier using its Wiki drop rate and the configured
     * thresholds. Larger (effective) denominator = rarer.
     */
    private RarityTier rarityTierFor(String normalizedItemName) {
        Double denominator = currentDropRarity.get(normalizedItemName);
        if (denominator == null) {
            return RarityTier.COMMON;
        }
        if (denominator >= config.ultraRareThreshold()) {
            return RarityTier.ULTRA_RARE;
        }
        if (denominator >= config.rareThreshold()) {
            return RarityTier.RARE;
        }
        return RarityTier.COMMON;
    }

    /**
     * Recolors a menu target. Item targets already carry a colour tag (default ff9040),
     * so we replace existing tags rather than wrapping (nested col tags don't restore).
     */
    private String recolor(String target, String hex) {
        if (target.contains("<col=")) {
            return target.replaceAll("<col=[0-9a-fA-F]+>", "<col=" + hex + ">");
        }
        return "<col=" + hex + ">" + target + "</col>";
    }

    private static String colorToHex(Color c) {
        return String.format("%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ─── Ground Items color integration ─────────────────────────────────────

    /**
     * Returns the color the official Ground Items plugin would use for an item of this value,
     * reading that plugin's configured value-tier colors/thresholds (with RuneLite's defaults
     * as a fallback if it is not installed/configured).
     */
    private Color groundItemsColorForValue(int value) {
        if (value >= groundItemsPrice("insaneValuePrice", 10_000_000)) {
            return groundItemsColor("insaneValueColor", 0xFF66B2);
        }
        if (value >= groundItemsPrice("highValuePrice", 1_000_000)) {
            return groundItemsColor("highValueColor", 0xFF9600);
        }
        if (value >= groundItemsPrice("mediumValuePrice", 100_000)) {
            return groundItemsColor("mediumValueColor", 0x99FF99);
        }
        if (value >= groundItemsPrice("lowValuePrice", 20_000)) {
            return groundItemsColor("lowValueColor", 0x66B2FF);
        }
        return groundItemsColor("defaultColor", 0xFFFFFF);
    }

    private Color groundItemsColor(String key, int fallbackRgb) {
        try {
            Color c = configManager.getConfiguration("grounditems", key, Color.class);
            return c != null ? c : new Color(fallbackRgb);
        } catch (Exception e) {
            return new Color(fallbackRgb);
        }
    }

    private int groundItemsPrice(String key, int fallback) {
        try {
            Integer v = configManager.getConfiguration("grounditems", key, Integer.class);
            return v != null ? v : fallback;
        } catch (Exception e) {
            return fallback;
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
        // Strip "(noted)" qualifier (does not change the underlying item identity).
        itemName = itemName.replaceAll("\\s*\\(noted\\)$", "");
        if (config.supportCollapsedItems()) {
            // Remove Ground Items decorations appended to the name: the collapsed-count
            // form " x 19" and a trailing numeric quantity/value in parentheses such as
            // " (19)" or " (1.9K)". Parentheses that are part of the real item name —
            // e.g. "Sawmill coupon (oak plank)", "Clue scroll (hard)", potion doses
            // like "(4)" (which have no leading space) — are intentionally preserved.
            itemName = itemName.replaceAll("\\s+x\\s+\\d+$", "");
            itemName = itemName.replaceAll("\\s+\\(\\d[\\d,.]*[kKmMbB]?\\)$", "");
        }
        return itemName.trim();
    }

    // ─── Accessors for overlay ──────────────────────────────────────────────

    public String getCurrentTask() { return currentTask; }
    public String getCurrentNpc() { return currentNpc; }
    public int getCurrentNpcId() { return currentNpcId; }
    public int getDropCount() { return currentTaskDrops.size(); }
    public String getLastWikiPage() { return lastWikiPage; }
    public boolean isInCombatGrace() {
        int seconds = config.combatTimeoutSeconds();
        if (seconds <= 0) {
            // 0 = stay active until the Slayer task ends (drops are cleared on task change)
            return true;
        }
        // ~0.6 seconds per game tick
        int ticksAllowed = (int) Math.ceil(seconds / 0.6);
        return (client.getTickCount() - lastCombatTick) <= ticksAllowed;
    }
}
