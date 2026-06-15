package com.slayerdropprioritizer;

import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

import javax.inject.Inject;
import java.awt.Dimension;
import java.awt.Graphics2D;

public class SlayerDropPrioritizerOverlay extends OverlayPanel
{
    private final SlayerDropPrioritizerPlugin plugin;

    @Inject
    private SlayerDropPrioritizerOverlay(SlayerDropPrioritizerPlugin plugin)
    {
        this.plugin = plugin;
        setPosition(OverlayPosition.TOP_LEFT);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        panelComponent.getChildren().clear();

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Task")
                .right(plugin.getCurrentTask())
                .build());

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("NPC")
                .right(plugin.getCurrentNpc())
                .build());

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("NPC ID")
                .right(String.valueOf(plugin.getCurrentNpcId()))
                .build());

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Drops")
                .right(String.valueOf(plugin.getDropCount()))
                .build());

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Combat")
                .right(plugin.isInCombatGrace() ? "YES" : "NO")
                .build());

        panelComponent.getChildren().add(
            LineComponent.builder()
                .left("Wiki")
                .right(plugin.getLastWikiPage())
                .build());

        return super.render(graphics);
    }
}