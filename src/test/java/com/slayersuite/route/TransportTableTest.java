package com.slayersuite.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.runelite.api.Skill;
import org.junit.BeforeClass;
import org.junit.Test;

public class TransportTableTest
{
	static TransportTable table;

	@BeforeClass
	public static void load()
	{
		table = TransportTable.load();
	}

	@Test
	public void theCatacombsStatueIsATransport()
	{
		Transport statue = null;
		for (Transport t : table.from(Packed.of(1639, 3673, 0)))
		{
			if ("Statue".equals(t.name))
			{
				statue = t;
			}
		}
		assertNotNull(statue);
		assertEquals("Investigate", statue.action);
		assertEquals(27785, statue.objectId);
		assertEquals(Packed.of(1666, 10050, 0), statue.destination);
		assertEquals(2, statue.duration);
		assertFalse(statue.needsItems);
		assertEquals("Investigate Statue", statue.label());
	}

	@Test
	public void tablesAreLargeAndShortcutsCarryAgilityLevels()
	{
		assertTrue(table.size() > 6000);
		int shortcuts = 0;
		for (Transport t : table.all())
		{
			if (t.kind == Transport.Kind.SHORTCUT)
			{
				shortcuts++;
				assertTrue(t.skills.getOrDefault(Skill.AGILITY, 0) >= 1);
			}
		}
		assertTrue(shortcuts > 600);
	}

	@Test
	public void itemRowsAreSkippedUnlessHarmless()
	{
		int needing = 0;
		for (Transport t : table.all())
		{
			if (t.needsItems)
			{
				needing++;
			}
		}
		assertTrue(needing > 100 && needing < 400);
	}

	@Test
	public void learnedTransportsJoinTheTable()
	{
		int origin = Packed.of(2300, 3100, 0);
		assertFalse(table.knows(origin, 12345));
		Transport learned = new Transport(origin, Packed.of(1500, 4300, 1), "Enter", "Sewer", 12345, 3, "",
			java.util.Collections.emptyMap(), java.util.Collections.emptySet(), java.util.Collections.emptyList(), java.util.Collections.emptyList(), false, Transport.Kind.LEARNED);
		TransportTable bigger = table.withAdded(java.util.Collections.singletonList(learned));
		assertTrue(bigger.knows(origin, 12345));
		assertEquals(table.size() + 1, bigger.size());
		assertFalse(table.knows(origin, 12345));
	}

	@Test
	public void spiritTreeStopsAreKeptApartFromEdges()
	{
		int ge = Packed.of(3185, 3508, 0);
		assertTrue(table.isStop(ge));
		assertTrue(table.from(ge).isEmpty());
		boolean geStop = false;
		boolean poisonWaste = false;
		for (Transport s : table.stops(Transport.Kind.SPIRIT_TREE))
		{
			geStop |= s.origin == ge;
			poisonWaste |= "Poison Waste".equals(s.display);
		}
		assertTrue(geStop && poisonWaste);
		assertTrue(table.stops(Transport.Kind.FAIRY_RING).size() > 40);
		assertTrue(table.networks().contains(Transport.Kind.GLIDER));
	}

	@Test
	public void fairyRingsAndShipsAreThere()
	{
		int zanaris = Packed.of(2412, 4434, 0);
		assertTrue(table.isStop(zanaris));
		boolean ring = false;
		for (Transport t : table.stops(Transport.Kind.FAIRY_RING))
		{
			ring |= t.origin == zanaris;
		}
		assertTrue(ring);
		boolean ship = false;
		for (Transport t : table.from(Packed.of(3029, 3217, 0)))
		{
			ship |= t.kind == Transport.Kind.SHIP && !t.needsItems;
		}
		assertTrue(ship);
	}
}
