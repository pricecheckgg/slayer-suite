package com.slayersuite;

import com.google.gson.Gson;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemNames;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DatasetTest
{
	private SlayerDataset load()
	{
		SlayerDataset d = new SlayerDataset(new Gson());
		d.load();
		return d;
	}

	@Test
	public void loadsAndIndexes()
	{
		SlayerDataset d = load();
		assertTrue(d.isLoaded());
		assertTrue("expected at least 60 tasks, got " + d.getTasks().size(), d.getTasks().size() >= 60);
		assertTrue(d.getItems().keys().contains("slayer_helm"));
	}

	/** Tasks the wiki gives no place for: umbrella pages and two bosses whose pages have no pins yet. Anything else here is a build regression. */
	private static final java.util.Set<String> KNOWN_WITHOUT_LOCATIONS = new java.util.HashSet<>(java.util.Arrays.asList(
		"dragons", "giants", "the-maggot-king", "the-shellbane-gryphon", "tzkal-zuk"));

	@Test
	public void everyTaskHasSomewhereToGo()
	{
		SlayerDataset d = load();
		java.util.List<String> missing = new java.util.ArrayList<>();
		int withRegions = 0;
		for (com.slayersuite.data.TaskRecord t : d.getTasks())
		{
			if (t.getLocations().isEmpty())
			{
				if (!KNOWN_WITHOUT_LOCATIONS.contains(t.getKey()))
				{
					missing.add(t.getKey());
				}
				continue;
			}
			boolean regions = false;
			for (com.slayersuite.data.LocationRecord l : t.getLocations())
			{
				if (l.getRegions() != null && !l.getRegions().isEmpty())
				{
					regions = true;
					break;
				}
			}
			if (regions)
			{
				withRegions++;
			}
		}
		assertTrue("tasks with no locations at all: " + missing, missing.isEmpty());
		assertTrue("only " + withRegions + " tasks have a location with map regions", withRegions >= 125);
		assertFalse(d.find("cockatrice").getLocations().isEmpty());
	}

	@Test
	public void findsTasksTheWayTheCorePluginSpellsThem()
	{
		SlayerDataset d = load();
		assertEquals("aberrant-spectres", d.find("aberrant spectres").getKey());
		assertEquals("aberrant-spectres", d.find("Aberrant Spectre").getKey());
		assertEquals("kalphites", d.find("kalphite").getKey());
		assertEquals("cave-krakens", d.find("cave kraken").getKey());
		assertEquals("dark-beast", d.find("dark beasts").getKey());
		assertEquals("tzhaar", d.find("TzHaar").getKey());
		assertEquals("fossil-island-wyverns", d.find("fossil island wyverns").getKey());
		assertEquals("gargoyles", d.find("Gargoyles").getKey());
		assertNull(d.find("not a real task"));
		assertNull(d.find(""));
	}

	@Test
	public void requiredItemsAndRegionsArePresent()
	{
		SlayerDataset d = load();
		TaskRecord t = d.find("aberrant spectres");
		assertNotNull(t);
		boolean nosePeg = false;
		for (ItemRule r : t.getRequiredItems())
		{
			nosePeg |= r.getAnyOf().contains("nose_peg") && r.getAnyOf().contains("slayer_helm") && r.isRequired();
		}
		assertTrue(nosePeg);
		assertTrue(t.getSuperiors().contains("Abhorrent spectre"));
		assertEquals(Integer.valueOf(60), t.getSlayerLevel());
		LocationRecord loc = d.locationFor(t, new int[]{13623});
		assertNotNull(loc);
		assertEquals("Slayer Tower", loc.getName());
		assertEquals(Boolean.FALSE, loc.getCannon());
		assertNull(d.locationFor(t, new int[]{12850}));

		TaskRecord g = d.find("gargoyles");
		assertNotNull(g.getFinishingItem());
		assertEquals(Integer.valueOf(9), g.getFinishingItem().getThreshold());
		assertTrue(g.getFinishingItem().getAnyOf().contains("rock_hammer"));
	}

	@Test
	public void itemNameNormalisation()
	{
		assertEquals("black mask", ItemNames.normalize("Black mask (10) (i)"));
		assertEquals("slayer helmet", ItemNames.normalize("Slayer helmet (i)"));
		assertEquals("antifire potion", ItemNames.normalize("Antifire potion(4)"));
		assertEquals("extended super antifire", ItemNames.normalize("Extended super antifire(2)"));
		assertEquals("anti-venom+", ItemNames.normalize("Anti-venom+(4)"));
		assertEquals("fungicide spray", ItemNames.normalize("Fungicide spray 10"));
		assertEquals("dragonfire shield (uncharged)", ItemNames.normalize("Dragonfire shield (uncharged)"));
		assertEquals("bullseye lantern (lit)", ItemNames.normalize("Bullseye lantern (lit)"));
		assertEquals("salve amulet", ItemNames.normalize("Salve amulet(ei)"));
		assertEquals("kandarin headgear", ItemNames.normalize("Kandarin headgear 4"));
	}

	@Test
	public void itemGroupsMatchHeldNames()
	{
		SlayerDataset d = load();
		ItemGroups items = d.getItems();
		Set<String> held = new HashSet<>(Arrays.asList(
			ItemNames.normalize("Black mask (10)"),
			ItemNames.normalize("Super antifire potion(3)"),
			ItemNames.normalize("Cannon base"),
			ItemNames.normalize("Cannon stand")));
		assertTrue(items.satisfied("black_mask", held));
		assertFalse(items.satisfied("slayer_helm", held));
		assertTrue(items.anySatisfied(Arrays.asList("slayer_helm", "black_mask"), held));
		assertTrue(items.satisfied("antifire_potion", held));
		assertFalse("needs all four parts", items.satisfied("cannon_parts", held));
		assertEquals("Black mask", items.heldName(Arrays.asList("slayer_helm", "black_mask"), held));
	}
}
