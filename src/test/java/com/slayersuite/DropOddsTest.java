package com.slayersuite;

import com.google.gson.Gson;
import com.slayersuite.data.DropRecord;
import com.slayersuite.data.MonsterRecord;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DropOddsTest
{
	private static DropRecord drop(String name, double p, String where)
	{
		DropRecord d = new DropRecord();
		d.setName(name);
		d.setP(p);
		d.setRarity("1/" + Math.round(1 / p));
		d.setWhere(where);
		return d;
	}

	private static MonsterRecord monster(String name, boolean superior, DropRecord... drops)
	{
		MonsterRecord m = new MonsterRecord();
		m.setName(name);
		m.setSuperior(superior);
		m.setDrops(Arrays.asList(drops));
		return m;
	}

	@Test
	public void chanceOfAtLeastOneByNow()
	{
		double half = DropRecord.chanceBy(1.0 / 512, 355);
		assertTrue("355 kills at 1/512 is about a coin flip, got " + half, half > 0.49 && half < 0.51);
		assertEquals(0, DropRecord.chanceBy(1.0 / 512, 0), 0);
		assertEquals(0, DropRecord.chanceBy(0, 100), 0);
		assertEquals(1, DropRecord.chanceBy(1, 3), 0);
		assertTrue(DropRecord.chanceBy(1.0 / 128, 1000) > 0.999);
	}

	@Test
	public void rowsThatOnlyApplyOnSomeTasks()
	{
		assertTrue(drop("Abyssal whip", 1.0 / 512, null).appliesTo(5, null));
		DropRecord key = drop("Brimstone key", 1.0 / 96, "konar");
		assertTrue(key.appliesTo(8, null));
		assertFalse(key.appliesTo(5, null));
		assertFalse(key.appliesTo(-1, null));
		DropRecord larran = drop("Larran's key", 1.0 / 99, "wilderness");
		assertTrue(larran.appliesTo(7, null));
		assertFalse(larran.appliesTo(8, null));
		DropRecord shard = drop("Ancient shard", 1.0 / 128, "catacombs");
		assertTrue(shard.appliesTo(5, "Catacombs of Kourend"));
		assertFalse(shard.appliesTo(5, "Slayer Tower"));
		assertFalse(shard.appliesTo(5, null));
		assertFalse(drop("Imbued heart", 1.0 / 2000, "superior").appliesTo(5, null));
	}

	@Test
	public void unionAcrossVariantsRarestFirst()
	{
		TaskRecord t = new TaskRecord();
		t.setMonsters(Arrays.asList(
			monster("Spectre", false, drop("Robe", 1.0 / 512, null), drop("Staff", 1.0 / 128, null)),
			monster("Deviant spectre", false, drop("Robe", 1.0 / 512, null), drop("Key", 1.0 / 4000, null)),
			monster("Abhorrent spectre", true, drop("Heart", 1.0 / 2000, null))));
		List<DropRecord> rare = t.rareDrops();
		assertEquals(3, rare.size());
		assertEquals("Key", rare.get(0).getName());
		assertEquals("Robe", rare.get(1).getName());
		assertEquals("Staff", rare.get(2).getName());
		assertEquals(Arrays.asList("Spectre", "Deviant spectre"), rare.get(1).getFrom());
		assertEquals(Arrays.asList("Deviant spectre"), rare.get(0).getFrom());
		for (DropRecord d : rare)
		{
			assertFalse("a superior's drop leaked into the task odds", d.getName().equals("Heart"));
		}
		assertTrue("the union is cached", rare == t.rareDrops());
	}

	@Test
	public void datasetCarriesTheRates()
	{
		SlayerDataset d = new SlayerDataset(new Gson());
		d.load();
		TaskRecord abyssal = d.find("Abyssal demons");
		assertNotNull(abyssal);
		DropRecord whip = null;
		for (DropRecord r : abyssal.rareDrops())
		{
			if ("Abyssal whip".equals(r.getName()))
			{
				whip = r;
			}
		}
		assertNotNull("abyssal demons drop the whip", whip);
		assertEquals(1.0 / 512, whip.probability(), 1e-6);
		assertEquals("1/512", whip.getRarity());
		assertTrue(whip.getFrom().contains("Abyssal demon"));
		TaskRecord zombies = d.find("Zombies");
		assertNotNull(zombies);
		for (DropRecord r : zombies.rareDrops())
		{
			assertFalse("a boss's drops are not the task's odds: " + r.getName(), "Draconic visage".equals(r.getName()));
		}
		TaskRecord cerb = d.find("Cerberus");
		assertNotNull(cerb);
		boolean crystal = false;
		for (DropRecord r : cerb.rareDrops())
		{
			crystal |= "Primordial crystal".equals(r.getName());
		}
		assertTrue("a boss task shows the boss's own drops", crystal);
		TaskRecord hell = d.find("Hellhounds");
		for (DropRecord r : hell.rareDrops())
		{
			assertFalse("Cerberus's drops are not the hellhound task's odds", "Primordial crystal".equals(r.getName()));
		}
		int withDrops = 0;
		for (TaskRecord t : d.getTasks())
		{
			if (!t.rareDrops().isEmpty())
			{
				withDrops++;
			}
		}
		assertTrue("only " + withDrops + " tasks have rare drops", withDrops >= 120);
	}
}
