package net.runelite.client.plugins.slayerdropprioritizer;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class MenuPrioritizationTest {
    private Client client;
    private SlayerDropPrioritizerConfig config;
    private ConfigManager configManager;
    private ItemManager itemManager;
    private SlayerDropPrioritizerPlugin plugin;

    private boolean enableDeprioritization = true;
    private PrioritizationMode prioritizationMode = PrioritizationMode.ALL_TASK_DROPS;
    private int minimumPriorityValue = 0;
    private DropDisplayMode dropDisplayMode = DropDisplayMode.DEPRIORITIZE;
    private boolean prioritizeExamine = true;
    private int combatTimeout = 50;

    @Before
    public void setUp() {
        client = mock(Client.class);
        config = mock(SlayerDropPrioritizerConfig.class);
        configManager = mock(ConfigManager.class);
        itemManager = mock(ItemManager.class);

        // Set up mock config behaviors
        when(config.enableDeprioritization()).thenAnswer(inv -> enableDeprioritization);
        when(config.prioritizationMode()).thenAnswer(inv -> prioritizationMode);
        when(config.minimumPriorityValue()).thenAnswer(inv -> minimumPriorityValue);
        when(config.dropDisplayMode()).thenAnswer(inv -> dropDisplayMode);
        when(config.prioritizeExamine()).thenAnswer(inv -> prioritizeExamine);
        when(config.combatTimeout()).thenAnswer(inv -> combatTimeout);

        // Mock ItemManager to avoid null pointers
        ItemComposition mockComposition = mock(ItemComposition.class);
        when(mockComposition.getNote()).thenReturn(-1);
        when(itemManager.getItemComposition(anyInt())).thenReturn(mockComposition);

        // Mock Client behavior
        when(client.getTickCount()).thenReturn(0);
        net.runelite.api.Scene mockScene = mock(net.runelite.api.Scene.class);
        when(client.getScene()).thenReturn(mockScene);
        when(mockScene.getTiles()).thenReturn(null);

        // Guice injection to create plugin instance and inject mocked components
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(Client.class).toInstance(client);
                bind(SlayerDropPrioritizerConfig.class).toInstance(config);
                bind(ConfigManager.class).toInstance(configManager);
                bind(ItemManager.class).toInstance(itemManager);
                bind(okhttp3.OkHttpClient.class).toInstance(mock(okhttp3.OkHttpClient.class));
                bind(com.google.gson.Gson.class).toInstance(new com.google.gson.Gson());
                bind(net.runelite.client.ui.overlay.OverlayManager.class).toInstance(mock(net.runelite.client.ui.overlay.OverlayManager.class));
                bind(SlayerDropPrioritizerOverlay.class).toInstance(mock(SlayerDropPrioritizerOverlay.class));
            }
        });

        plugin = injector.getInstance(SlayerDropPrioritizerPlugin.class);
    }

    private MenuEntry createMenuEntry(int id, String option, String target, MenuAction type, int identifier) {
        MenuEntry entry = mock(MenuEntry.class);
        when(entry.getOption()).thenReturn(option);
        when(entry.getTarget()).thenReturn(target);
        when(entry.getType()).thenReturn(type);
        when(entry.getIdentifier()).thenReturn(identifier);
        return entry;
    }

    @Test
    public void testMenuPrioritization_withPrioritizeExamineEnabled() {
        enableDeprioritization = true;
        prioritizeExamine = true;

        plugin.startUp();
        plugin.currentTaskDrops.add("Bones");
        plugin.lastCombatTick = 0;

        // Mock original menu entries:
        // Index 3: Take Ashes (non-priority Take)
        // Index 2: Take Bones (priority Take)
        // Index 1: Examine Ashes (non-priority Examine)
        // Index 0: Examine Bones (priority Examine)
        MenuEntry examineBones = createMenuEntry(0, "Examine", "Bones", MenuAction.EXAMINE_ITEM_GROUND, 526);
        MenuEntry examineAshes = createMenuEntry(1, "Examine", "Ashes", MenuAction.EXAMINE_ITEM_GROUND, 592);
        MenuEntry takeBones = createMenuEntry(2, "Take", "Bones", MenuAction.GROUND_ITEM_FIRST_OPTION, 526);
        MenuEntry takeAshes = createMenuEntry(3, "Take", "Ashes", MenuAction.GROUND_ITEM_FIRST_OPTION, 592);

        MenuEntry[] entries = new MenuEntry[] { examineBones, examineAshes, takeBones, takeAshes };
        when(client.getMenuEntries()).thenReturn(entries);

        // Trigger menu open
        plugin.onMenuOpened(null);

        // Capture what was set on the client
        ArgumentCaptor<MenuEntry[]> captor = ArgumentCaptor.forClass(MenuEntry[].class);
        verify(client).setMenuEntries(captor.capture());

        MenuEntry[] result = captor.getValue();
        assertEquals(4, result.length);

        // Expected order (index 3 is top, 0 is bottom):
        // 3: Take Bones (priority)
        // 2: Examine Bones (priority)
        // 1: Take Ashes (non-priority)
        // 0: Examine Ashes (non-priority)
        assertEquals("Take", result[3].getOption());
        assertEquals("Bones", result[3].getTarget());

        assertEquals("Examine", result[2].getOption());
        assertEquals("Bones", result[2].getTarget());

        assertEquals("Take", result[1].getOption());
        assertEquals("Ashes", result[1].getTarget());

        assertEquals("Examine", result[0].getOption());
        assertEquals("Ashes", result[0].getTarget());
    }

    @Test
    public void testMenuPrioritization_withPrioritizeExamineDisabled() {
        enableDeprioritization = true;
        prioritizeExamine = false;

        plugin.startUp();
        plugin.currentTaskDrops.add("Bones");
        plugin.lastCombatTick = 0;

        // Mock original menu entries:
        // Index 3: Take Ashes (non-priority Take)
        // Index 2: Take Bones (priority Take)
        // Index 1: Examine Ashes (non-priority Examine)
        // Index 0: Examine Bones (priority Examine)
        MenuEntry examineBones = createMenuEntry(0, "Examine", "Bones", MenuAction.EXAMINE_ITEM_GROUND, 526);
        MenuEntry examineAshes = createMenuEntry(1, "Examine", "Ashes", MenuAction.EXAMINE_ITEM_GROUND, 592);
        MenuEntry takeBones = createMenuEntry(2, "Take", "Bones", MenuAction.GROUND_ITEM_FIRST_OPTION, 526);
        MenuEntry takeAshes = createMenuEntry(3, "Take", "Ashes", MenuAction.GROUND_ITEM_FIRST_OPTION, 592);

        MenuEntry[] entries = new MenuEntry[] { examineBones, examineAshes, takeBones, takeAshes };
        when(client.getMenuEntries()).thenReturn(entries);

        // Trigger menu open
        plugin.onMenuOpened(null);

        // Capture what was set on the client
        ArgumentCaptor<MenuEntry[]> captor = ArgumentCaptor.forClass(MenuEntry[].class);
        verify(client).setMenuEntries(captor.capture());

        MenuEntry[] result = captor.getValue();
        assertEquals(4, result.length);

        // Expected order (index 3 is top, 0 is bottom):
        // 3: Take Bones (priority Take)
        // 2: Take Ashes (non-priority Take)
        // 1: Examine Bones (priority Examine)
        // 0: Examine Ashes (non-priority Examine)
        assertEquals("Take", result[3].getOption());
        assertEquals("Bones", result[3].getTarget());

        assertEquals("Take", result[2].getOption());
        assertEquals("Ashes", result[2].getTarget());

        assertEquals("Examine", result[1].getOption());
        assertEquals("Bones", result[1].getTarget());

        assertEquals("Examine", result[0].getOption());
        assertEquals("Ashes", result[0].getTarget());
    }

    @Test
    public void testMenuPrioritization_withPrioritizeExamineDisabledAndWalkOption() {
        enableDeprioritization = true;
        prioritizeExamine = false;

        plugin.startUp();
        plugin.currentTaskDrops.add("Bones");
        plugin.lastCombatTick = 0;

        // Mock original menu entries:
        // Index 4: Take Ashes (non-priority Take)
        // Index 3: Take Bones (priority Take)
        // Index 2: Walk here (non-ground action)
        // Index 1: Examine Ashes (non-priority Examine)
        // Index 0: Examine Bones (priority Examine)
        MenuEntry examineBones = createMenuEntry(0, "Examine", "Bones", MenuAction.EXAMINE_ITEM_GROUND, 526);
        MenuEntry examineAshes = createMenuEntry(1, "Examine", "Ashes", MenuAction.EXAMINE_ITEM_GROUND, 592);
        MenuEntry walkHere = createMenuEntry(2, "Walk here", "", MenuAction.WALK, 0);
        MenuEntry takeBones = createMenuEntry(3, "Take", "Bones", MenuAction.GROUND_ITEM_FIRST_OPTION, 526);
        MenuEntry takeAshes = createMenuEntry(4, "Take", "Ashes", MenuAction.GROUND_ITEM_FIRST_OPTION, 592);

        MenuEntry[] entries = new MenuEntry[] { examineBones, examineAshes, walkHere, takeBones, takeAshes };
        when(client.getMenuEntries()).thenReturn(entries);

        // Trigger menu open
        plugin.onMenuOpened(null);

        // Capture what was set on the client
        ArgumentCaptor<MenuEntry[]> captor = ArgumentCaptor.forClass(MenuEntry[].class);
        verify(client).setMenuEntries(captor.capture());

        MenuEntry[] result = captor.getValue();
        assertEquals(5, result.length);

        // Expected order (index 4 is top, 0 is bottom):
        // 4: Take Bones (priority Take)
        // 3: Walk here (non-ground action)
        // 2: Take Ashes (non-priority Take)
        // 1: Examine Bones (priority Examine)
        // 0: Examine Ashes (non-priority Examine)
        assertEquals("Take", result[4].getOption());
        assertEquals("Bones", result[4].getTarget());

        assertEquals("Walk here", result[3].getOption());

        assertEquals("Take", result[2].getOption());
        assertEquals("Ashes", result[2].getTarget());

        assertEquals("Examine", result[1].getOption());
        assertEquals("Bones", result[1].getTarget());

        assertEquals("Examine", result[0].getOption());
        assertEquals("Ashes", result[0].getTarget());
    }
}
