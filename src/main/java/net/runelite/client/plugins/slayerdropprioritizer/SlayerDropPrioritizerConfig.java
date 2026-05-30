package net.runelite.client.plugins.slayerdropprioritizer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("slayerdropprioritizer")
public interface SlayerDropPrioritizerConfig extends Config
{
    @ConfigItem(
        keyName = "enableDeprioritization",
        name = "Ativar Depriorização",
        description = "Move itens que não são da drop table para o final do menu durante o combate."
    )
    default boolean enableDeprioritization()
    {
        return true;
    }
}