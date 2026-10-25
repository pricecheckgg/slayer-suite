package com.slayersuite.advisor;

import com.google.gson.Gson;
import com.slayersuite.SlayerSuiteConfig;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ObjectComposition;
import net.runelite.api.WorldView;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.RuneScapeProfileChanged;

/**
 * Remembers what teleport furniture your house has. The client only shows the
 * house while you stand in it, so every visit updates a list saved on the
 * RuneScape profile: portals, portal nexus, jewellery box tier, mounted glory,
 * talisman, pendant and cape, fairy ring, spirit tree, spellbook altars.
 */
@Slf4j
@Singleton
public class HouseScanner
{
	static final String KEY = "house";

	/** Object names (lower case) that only exist inside a player-owned house. */
	private static final Set<String> FACILITIES = new HashSet<>(Arrays.asList(
		"portal nexus", "fairy ring", "spirit tree", "spiritual fairy tree",
		"basic jewellery box", "fancy jewellery box", "ornate jewellery box",
		"amulet of glory", "xeric's talisman", "digsite pendant", "mythical cape",
		"occult altar", "altar of the occult", "ancient altar", "lunar altar", "dark altar",
		"obelisk", "pool of restoration", "pool of revitalisation", "pool of rejuvenation", "fancy pool of rejuvenation", "ornate pool of rejuvenation"));

	public static class House
	{
		public Set<String> facilities = new TreeSet<>();
		public boolean nexus;
		public boolean nexusXeric;
		public boolean nexusDigsite;
		public long updated;
	}

	private final Client client;
	private final ConfigManager configManager;
	private final Gson gson;

	@Getter
	private House house = new House();
	private boolean loaded;
	private boolean dirty;
	private int tick;

	@Inject
	public HouseScanner(Client client, ConfigManager configManager, Gson gson)
	{
		this.client = client;
		this.configManager = configManager;
		this.gson = gson;
	}

	public void reset()
	{
		loaded = false;
		dirty = false;
	}

	// ---------------------------------------------------------------- persistence

	private void load()
	{
		house = new House();
		try
		{
			String json = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, KEY);
			if (json != null && !json.isEmpty())
			{
				House h = gson.fromJson(json, House.class);
				if (h != null)
				{
					if (h.facilities == null)
					{
						h.facilities = new TreeSet<>();
					}
					house = h;
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("house load failed", e);
		}
		loaded = true;
	}

	private void save()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		try
		{
			house.updated = System.currentTimeMillis();
			configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, KEY, gson.toJson(house));
			dirty = false;
		}
		catch (RuntimeException e)
		{
			log.debug("house save failed", e);
		}
	}

	// ---------------------------------------------------------------- queries

	public boolean scanned()
	{
		return house.updated > 0;
	}

	public boolean has(String facility)
	{
		if (facility == null)
		{
			return false;
		}
		String f = facility.toLowerCase(Locale.ROOT);
		if (f.equals("portal nexus"))
		{
			return house.nexus;
		}
		if (f.equals("fairy ring"))
		{
			return house.facilities.contains("fairy ring") || house.facilities.contains("spiritual fairy tree");
		}
		if (f.equals("spirit tree"))
		{
			return house.facilities.contains("spirit tree") || house.facilities.contains("spiritual fairy tree");
		}
		if (f.equals("xeric's talisman"))
		{
			return house.facilities.contains(f) || house.nexusXeric;
		}
		if (f.equals("digsite pendant"))
		{
			return house.facilities.contains(f) || house.nexusDigsite;
		}
		return house.facilities.contains(f);
	}

	/** Jewellery box tiers: basic = dueling and games; fancy adds skills and combat; ornate adds glory and wealth. */
	public boolean jewelleryBoxCovers(String itemName)
	{
		if (itemName == null)
		{
			return false;
		}
		String n = itemName.toLowerCase(Locale.ROOT);
		boolean basic = house.facilities.contains("basic jewellery box") || house.facilities.contains("fancy jewellery box") || house.facilities.contains("ornate jewellery box");
		boolean fancy = house.facilities.contains("fancy jewellery box") || house.facilities.contains("ornate jewellery box");
		boolean ornate = house.facilities.contains("ornate jewellery box");
		if (n.startsWith("ring of dueling") || n.startsWith("games necklace"))
		{
			return basic;
		}
		if (n.startsWith("skills necklace") || n.startsWith("combat bracelet"))
		{
			return fancy;
		}
		if (n.startsWith("amulet of glory") || n.startsWith("ring of wealth"))
		{
			return ornate;
		}
		return false;
	}

	public boolean hasSpellbookAltar()
	{
		return house.facilities.contains("occult altar") || house.facilities.contains("altar of the occult")
			|| house.facilities.contains("ancient altar") || house.facilities.contains("lunar altar") || house.facilities.contains("dark altar");
	}

	public Set<String> facilities()
	{
		return Collections.unmodifiableSet(house.facilities);
	}

	public String summary()
	{
		if (!scanned())
		{
			return "Not scanned yet: stand in your house once and I will remember its teleports.";
		}
		Set<String> all = new TreeSet<>(house.facilities);
		if (house.nexus)
		{
			all.add("portal nexus" + (house.nexusXeric ? " (xeric)" : "") + (house.nexusDigsite ? " (digsite)" : ""));
		}
		return all.isEmpty() ? "House scanned: no teleport furniture seen." : String.join(", ", all);
	}

	// ---------------------------------------------------------------- events

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN && !loaded)
		{
			load();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged e)
	{
		load();
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned e)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || !wv.isInstance() || e.getGameObject() == null)
		{
			return;
		}
		if (!loaded)
		{
			load();
		}
		ObjectComposition def;
		try
		{
			def = client.getObjectDefinition(e.getGameObject().getId());
		}
		catch (RuntimeException ex)
		{
			return;
		}
		if (def == null || def.getName() == null)
		{
			return;
		}
		String name = def.getName().toLowerCase(Locale.ROOT).trim();
		if (name.isEmpty() || name.equals("null"))
		{
			return;
		}
		boolean portal = name.endsWith(" portal") && !name.equals("exit portal");
		if (portal || FACILITIES.contains(name))
		{
			if (name.equals("portal nexus"))
			{
				if (!house.nexus)
				{
					house.nexus = true;
					dirty = true;
				}
			}
			else if (house.facilities.add(name))
			{
				dirty = true;
				log.debug("house: found {}", name);
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (!loaded)
		{
			return;
		}
		if (house.nexus)
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv != null && wv.isInstance())
			{
				boolean xeric = client.getVarbitValue(VarbitID.POH_NEXUS_XERIC) == 1;
				boolean digsite = client.getVarbitValue(VarbitID.POH_NEXUS_DIGSITE) == 1;
				if (xeric != house.nexusXeric || digsite != house.nexusDigsite)
				{
					house.nexusXeric = xeric;
					house.nexusDigsite = digsite;
					dirty = true;
				}
			}
		}
		if (dirty && ++tick % 10 == 0)
		{
			save();
		}
	}
}
