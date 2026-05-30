package net.runelite.client.plugins.slayerdropprioritizer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import okhttp3.*;
import javax.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@PluginDescriptor(
    name = "Slayer Drop Prioritizer",
    description = "Deprioriza itens fora da drop table do monstro da task atual.",
    tags = {"slayer", "drops", "menu", "wiki", "combat"}
)
public class SlayerDropPrioritizerPlugin extends Plugin
{
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

    private String currentTask = "";
    private final Set<Integer> currentTaskDrops = new HashSet<>();
    private boolean inCombatWithTask = false;

    @Provides
    SlayerDropPrioritizerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(SlayerDropPrioritizerConfig.class);
    }

    @Override
    protected void startUp() throws Exception
    {
        log.info("Slayer Drop Prioritizer iniciado!");
        
        // Assim que o plugin liga, ele já lê a task salva pelo plugin oficial de Slayer
        String savedTask = configManager.getConfiguration("slayer", "taskName");
        updateTask(savedTask);
    }

    @Override
    protected void shutDown() throws Exception
    {
        currentTask = "";
        currentTaskDrops.clear();
        inCombatWithTask = false;
        log.info("Slayer Drop Prioritizer desligado!");
    }

    // Monitora as mudanças nas configurações do RuneLite em tempo real
    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        // Se o plugin oficial de Slayer mudar o nome da task (nova task, ou task finalizada/cancelada)
        if ("slayer".equals(event.getGroup()) && "taskName".equals(event.getKey()))
        {
            updateTask(event.getNewValue());
        }
    }

    private void updateTask(String taskName)
    {
        // Se a task foi cancelada ou acabada, o RuneLite salva como vazio ou nulo
        if (taskName == null || taskName.isEmpty())
        {
            currentTask = "";
            currentTaskDrops.clear();
            log.info("Nenhuma task de Slayer ativa. Drops limpos.");
            return;
        }

        // Remove "s" no final para padronizar com a pesquisa na Wiki, ex: "Blue dragons" -> "Blue dragon"
        String formattedTask = taskName;
        if (formattedTask.toLowerCase().endsWith("s")) {
            formattedTask = formattedTask.substring(0, formattedTask.length() - 1);
        }
        
        if (!formattedTask.equalsIgnoreCase(currentTask))
        {
            currentTask = formattedTask;
            log.info("Task de Slayer identificada: {}", currentTask);
            fetchDropsFromWiki(currentTask);
        }
    }

    private void fetchDropsFromWiki(String monsterName)
    {
        currentTaskDrops.clear();
        
        // Formata o nome para a URL da Wiki da OSRS (Semantic MediaWiki API)
        String urlName = monsterName.replace(" ", "_");
        String url = "https://oldschool.runescape.wiki/api.php?action=askargs&conditions=Dropped_by::" + urlName + "&printouts=Item_ID&format=json";

        Request request = new Request.Builder()
            .url(url)
            .header("User-Agent", "RuneLite Plugin - SlayerDropPrioritizer")
            .build();

        okHttpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException e)
            {
                log.error("Falha ao buscar drops na Wiki para: " + monsterName, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                if (!response.isSuccessful())
                {
                    log.error("Resposta HTTP inesperada da Wiki: " + response);
                    return;
                }

                try
                {
                    String responseBody = response.body().string();
                    JsonObject json = gson.fromJson(responseBody, JsonObject.class);
                    
                    if (json.has("query") && json.getAsJsonObject("query").has("results"))
                    {
                        JsonObject results = json.getAsJsonObject("query").getAsJsonObject("results");
                        
                        for (String itemKey : results.keySet())
                        {
                            JsonObject itemData = results.getAsJsonObject(itemKey);
                            JsonArray printouts = itemData.getAsJsonObject("printouts").getAsJsonArray("Item ID");
                            
                            for (JsonElement idElement : printouts)
                            {
                                currentTaskDrops.add(idElement.getAsInt());
                            }
                        }
                        log.info("Drops carregados com sucesso para {}. Total de itens permitidos: {}", monsterName, currentTaskDrops.size());
                    }
                }
                catch (Exception e)
                {
                    log.error("Erro ao analisar o JSON da Wiki.", e);
                }
                finally
                {
                    response.close();
                }
            }
        });
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (currentTask.isEmpty() || !config.enableDeprioritization())
        {
            inCombatWithTask = false;
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null) return;

        Actor interacting = localPlayer.getInteracting();
        
        // Verifica se o jogador está em combate com um NPC
        if (interacting instanceof NPC)
        {
            NPC npc = (NPC) interacting;
            String npcName = npc.getName();
            
            // Verifica se o nome do NPC inclui o nome da task (isso engloba variações na Wilderness, etc)
            if (npcName != null && npcName.toLowerCase().contains(currentTask.toLowerCase()))
            {
                inCombatWithTask = true;
                return;
            }
        }
        
        inCombatWithTask = false;
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        if (!config.enableDeprioritization() || !inCombatWithTask || currentTaskDrops.isEmpty())
        {
            return; 
        }

        MenuEntry[] entries = client.getMenuEntries();
        List<MenuEntry> priorityEntries = new ArrayList<>();
        List<MenuEntry> deprioritizedEntries = new ArrayList<>();

        for (MenuEntry entry : entries)
        {
            MenuAction action = entry.getType();
            
            if (action == MenuAction.GROUND_ITEM_FIRST_OPTION ||
                action == MenuAction.GROUND_ITEM_SECOND_OPTION ||
                action == MenuAction.GROUND_ITEM_THIRD_OPTION ||
                action == MenuAction.GROUND_ITEM_FOURTH_OPTION ||
                action == MenuAction.GROUND_ITEM_FIFTH_OPTION ||
                action == MenuAction.EXAMINE_ITEM_GROUND)
            {
                int itemId = entry.getIdentifier();

                if (!currentTaskDrops.contains(itemId))
                {
                    deprioritizedEntries.add(entry);
                    continue;
                }
            }
            
            priorityEntries.add(entry);
        }

        if (deprioritizedEntries.isEmpty())
        {
            return;
        }

        List<MenuEntry> finalMenu = new ArrayList<>();
        finalMenu.addAll(deprioritizedEntries); // Itens sem prioridade ficam no topo da lista (fundo do menu in-game)
        finalMenu.addAll(priorityEntries);      // Itens válidos ficam depois (topo do menu in-game)

        client.setMenuEntries(finalMenu.toArray(new MenuEntry[0]));
    }
}