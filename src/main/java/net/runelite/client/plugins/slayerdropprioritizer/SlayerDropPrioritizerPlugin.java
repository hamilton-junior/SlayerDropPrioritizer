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
        log.info(
                "[Menu] Opened | drops={} | combatTicksAgo={}",
                currentTaskDrops.size(),
                client.getTickCount() - lastCombatTick);

        if (!config.enableDeprioritization()) {
            return;
        }

        if (currentTaskDrops.isEmpty()) {
            return;
        }

        if ((client.getTickCount() - lastCombatTick) > config.combatTimeout()) {
            return;
        }

        MenuEntry[] entries = client.getMenuEntries();

        List<MenuEntry> grounds = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();

        for (int i = 0; i < entries.length; i++) {
            MenuAction action = entries[i].getType();

            if (action == MenuAction.GROUND_ITEM_FIRST_OPTION
                    || action == MenuAction.GROUND_ITEM_SECOND_OPTION
                    || action == MenuAction.GROUND_ITEM_THIRD_OPTION
                    || action == MenuAction.GROUND_ITEM_FOURTH_OPTION
                    || action == MenuAction.GROUND_ITEM_FIFTH_OPTION
                    || action == MenuAction.EXAMINE_ITEM_GROUND) {
                grounds.add(entries[i]);
                indices.add(i);

                log.info(
                        "[Menu] Ground item: {} | option={} | identifier={}",
                        entries[i].getTarget(),
                        entries[i].getOption(),
                        entries[i].getIdentifier());
            }
        }
        if (grounds.isEmpty()) {
            return;
        }

        if (config.dropDisplayMode() == DropDisplayMode.SHOW) {
            return;
        }

        List<MenuEntry> priorityTake = new ArrayList<>();
        List<MenuEntry> priorityExamine = new ArrayList<>();
        List<MenuEntry> nonPriorityTake = new ArrayList<>();
        List<MenuEntry> nonPriorityExamine = new ArrayList<>();

        for (MenuEntry entry : grounds) {
            String itemName = normalizeItemName(entry.getTarget());
            boolean priority = currentTaskDrops.contains(itemName);

            if (priority
                    && config.prioritizationMode() == PrioritizationMode.VALUABLE_TASK_DROPS) {
                int gePrice = itemManager.getItemPrice(entry.getIdentifier());

                priority = gePrice >= config.minimumPriorityValue();

                log.info(
                        "[Value Filter] {} | id={} | price={} | priority={}",
                        itemName,
                        entry.getIdentifier(),
                        gePrice,
                        priority);
            }

            log.info(
                    "[Menu] {} | option={} | priority={}",
                    itemName,
                    entry.getOption(),
                    priority);

            String option = normalizeItemName(entry.getOption()).trim();

            if (priority) {
                if ("Take".equalsIgnoreCase(option)) {
                    priorityTake.add(entry);
                } else if ("Examine".equalsIgnoreCase(option)) {
                    priorityExamine.add(entry);
                }
            } else {
                if ("Take".equalsIgnoreCase(option)) {
                    nonPriorityTake.add(entry);
                } else if ("Examine".equalsIgnoreCase(option)) {
                    nonPriorityExamine.add(entry);
                }
            }
        }

        if (config.dropDisplayMode() == DropDisplayMode.HIDE) {
            // For HIDE mode: only keep priority items
            List<MenuEntry> finalMenu = new ArrayList<>();

            // Keep all non-ground items
            for (int i = 0; i < entries.length; i++) {
                if (!indices.contains(i)) {
                    finalMenu.add(entries[i]);
                }
            }

            // Add priority Take entries first
            finalMenu.addAll(priorityTake);

            // Add priority Examine if "Prioritize Examine" is enabled
            if (config.prioritizeExamine()) {
                finalMenu.addAll(priorityExamine);
            }

            log.info("========== HIDE MODE FINAL ==========");
            for (MenuEntry entry : finalMenu) {
                String option = normalizeItemName(entry.getOption()).trim();
                String target = normalizeItemName(entry.getTarget()).trim();
                boolean isGround = indices.contains(entries[0]); // simplified check
                log.info("[FINAL] option={} target={}", option, target);
            }

            client.setMenuEntries(finalMenu.toArray(new MenuEntry[0]));
        } else if (config.dropDisplayMode() == DropDisplayMode.DEPRIORITIZE) {
            // For DEPRIORITIZE mode: reorder all items with priority at top
            List<MenuEntry> reordered = new ArrayList<>();

            // Add priority Take entries first
            reordered.addAll(priorityTake);

            // Add priority Examine if "Prioritize Examine" is enabled
            if (config.prioritizeExamine()) {
                reordered.addAll(priorityExamine);
            }

            // Add non-priority items (both Take and Examine)
            reordered.addAll(nonPriorityTake);
            reordered.addAll(nonPriorityExamine);

            log.info("========== DEPRIORITIZE MODE FINAL ==========");
            for (MenuEntry entry : reordered) {
                String option = normalizeItemName(entry.getOption()).trim();
                String target = normalizeItemName(entry.getTarget()).trim();
                log.info("[FINAL] option={} target={}", option, target);
            }

            // Replace the ground items in their original positions
            int limit = Math.min(indices.size(), reordered.size());
            for (int i = 0; i < limit; i++) {
                entries[indices.get(i)] = reordered.get(i);
            }

            client.setMenuEntries(entries);
        }

        log.info("[Menu] Processed {} ground items", grounds.size());
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