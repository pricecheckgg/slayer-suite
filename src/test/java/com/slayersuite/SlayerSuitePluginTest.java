package com.slayersuite;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class SlayerSuitePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(SlayerSuitePlugin.class);
		RuneLite.main(args);
	}
}
