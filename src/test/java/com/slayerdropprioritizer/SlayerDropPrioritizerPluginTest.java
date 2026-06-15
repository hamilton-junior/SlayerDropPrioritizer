package com.slayerdropprioritizer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SlayerDropPrioritizerPluginTest
{
    public static void main(String[] args) throws Exception
    {
        // Avisa ao RuneLite para carregar o seu plugin customizado
        //ExternalPluginManager.loadBuiltin(SlayerDropPrioritizerPlugin.class);
        
        // Inicia o jogo
        RuneLite.main(args);
    }
}