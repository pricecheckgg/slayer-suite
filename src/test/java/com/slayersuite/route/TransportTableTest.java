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
}
