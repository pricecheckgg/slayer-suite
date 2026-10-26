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
			if (t.shortcut)
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
			java.util.Collections.emptyMap(), java.util.Collections.emptySet(), java.util.Collections.emptyList(), java.util.Collections.emptyList(), false, false);
		TransportTable bigger = table.withAdded(java.util.Collections.singletonList(learned));
		assertTrue(bigger.knows(origin, 12345));
		assertEquals(table.size() + 1, bigger.size());
		assertFalse(table.knows(origin, 12345));
	}
}
