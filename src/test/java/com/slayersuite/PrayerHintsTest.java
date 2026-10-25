package com.slayersuite;

import com.slayersuite.card.PrayerHints;
import com.slayersuite.data.MonsterRecord;
import com.slayersuite.data.TaskRecord;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import net.runelite.api.Prayer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PrayerHintsTest
{
	@Test
	public void singleStyleMonsters()
	{
		assertEquals(Prayer.PROTECT_FROM_MAGIC, PrayerHints.fromStyles(Arrays.asList("magic")));
		assertEquals(Prayer.PROTECT_FROM_MELEE, PrayerHints.fromStyles(Arrays.asList("magical melee")));
		assertEquals(Prayer.PROTECT_FROM_MELEE, PrayerHints.fromStyles(Arrays.asList("slash", "crush")));
		assertEquals(Prayer.PROTECT_FROM_MISSILES, PrayerHints.fromStyles(Arrays.asList("ranged")));
		assertEquals(Prayer.PROTECT_FROM_MAGIC, PrayerHints.fromStyles(Arrays.asList("dragonfire")));
	}

	@Test
	public void mixedMonstersGiveNoSingleAnswerButAllCandidates()
	{
		assertNull(PrayerHints.fromStyles(Arrays.asList("magic", "ranged")));
		Set<Prayer> c = PrayerHints.candidates(Arrays.asList("magic", "ranged"));
		assertEquals(2, c.size());
		assertTrue(c.contains(Prayer.PROTECT_FROM_MAGIC) && c.contains(Prayer.PROTECT_FROM_MISSILES));
		assertTrue(PrayerHints.candidates(Collections.emptyList()).isEmpty());
		assertTrue(PrayerHints.candidates(null).isEmpty());
	}

	@Test
	public void npcLookupPrefersItsOwnStylesThenTaskLine()
	{
		TaskRecord rec = new TaskRecord();
		rec.setPrayer("Protect from Melee");
		MonsterRecord hydra = new MonsterRecord();
		hydra.setName("Hydra");
		hydra.setAttackStyles(Arrays.asList("magic", "ranged"));
		MonsterRecord troll = new MonsterRecord();
		troll.setName("Mountain troll");
		troll.setAttackStyles(Arrays.asList("crush"));
		rec.setMonsters(Arrays.asList(hydra, troll));
		assertNull("a known switcher must not inherit the task's single prayer", PrayerHints.forNpc(rec, "Hydra"));
		assertEquals(2, PrayerHints.candidatesForNpc(rec, "Hydra").size());
		assertEquals(Prayer.PROTECT_FROM_MELEE, PrayerHints.forNpc(rec, "Mountain troll"));
		assertEquals(Prayer.PROTECT_FROM_MELEE, PrayerHints.forNpc(rec, "Unknown thing"));
		assertEquals(1, PrayerHints.candidatesForNpc(rec, "Unknown thing").size());
		assertNull(PrayerHints.forNpc(null, "Hydra"));
		assertTrue(PrayerHints.candidatesForNpc(null, "Hydra").isEmpty());
	}
}
