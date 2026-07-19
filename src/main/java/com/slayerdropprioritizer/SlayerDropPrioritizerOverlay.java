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