package com.slayersuite;

import com.google.gson.Gson;
import com.slayersuite.advisor.AccountView;
import com.slayersuite.advisor.LocationScorer;
import com.slayersuite.advisor.ScoredLocation;
import com.slayersuite.advisor.TravelData;
import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.TaskRecord;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocationScorerTest
{
	private static class Stub implements AccountView
	{
		Set<String> quests = new HashSet<>();
		Set<String> items = new HashSet<>();
		boolean cannon;
		int book = 0;

		@Override
		public boolean questFinished(String q)
		{
			return quests.contains(q);
		}

		@Override
		public boolean questStarted(String q)
		{
			return quests.contains(q);
		}

		@Override
		public boolean diaryComplete(String d)
		{
			return false;
		}

		@Override
		public int spellbook()
		{
			return book;
		}

		@Override
		public boolean ownsItem(String n)
		{
			return items.contains(n);
		}

		@Override
		public boolean fairyRingsUsable()
		{
			return false;
		}

		@Override
		public boolean ownsCannon()
		{
			return cannon;
		}

		@Override
		public int skillLevel(String skill)
		{
			return 60;
		}

		Set<String> houseFacilities = new HashSet<>();
		boolean homeTele = true;

		@Override
		public boolean hasHouseFacility(String f)
		{
			return houseFacilities.contains(f.toLowerCase());
		}

		@Override
		public boolean jewelleryBoxCovers(String item)
		{
			return houseFacilities.contains("ornate jewellery box");
		}

		@Override
		public boolean houseScanned()
		{
			return !houseFacilities.isEmpty();
		}

		@Override
		public boolean canTeleportHome()
		{
			return homeTele;
		}
	}

	private static LocationRecord loc(String name, Boolean cannon, Boolean multi, Boolean safe, int spawns, Integer region)
	{
		LocationRecord l = new LocationRecord();
		l.setName(name);
		l.setCannon(cannon);
		l.setMulticombat(multi);
		l.setSafespot(safe);
		l.setSpawnCount(spawns);
		if (region != null)
		{
			l.setRegions(Arrays.asList(region));
		}
		return l;
	}

	private TravelData travel()
	{
		TravelData t = new TravelData(new Gson());
		t.load();
		assertTrue(t.size() > 20);
		return t;
	}

	@Test
	public void ranksCannonSpotFirstWhenYouOwnACannon()
	{
		TaskRecord rec = new TaskRecord();
		rec.setLocations(Arrays.asList(
			loc("Slayer Tower (1st floor)", false, false, true, 14, 13623),
			loc("Stronghold Slayer Cave", true, false, false, 13, 9880)));
		Stub acc = new Stub();
		acc.quests.add("PRIEST_IN_PERIL");
		acc.quests.add("DWARF_CANNON");
		acc.items.add("Slayer ring");
		acc.cannon = true;
		List<ScoredLocation> r = new LocationScorer(travel()).rank(rec, acc, new LocationScorer.Options());
		assertEquals("Stronghold Slayer Cave", r.get(0).getLocation().getName());
		assertFalse(r.get(0).isLocked());
		assertEquals("Slayer ring", r.get(0).getTravel());
		assertTrue(r.get(0).getReasons().contains("cannon"));
	}

	@Test
	public void locksSpotsBehindQuests()
	{
		TaskRecord rec = new TaskRecord();
		rec.setLocations(Arrays.asList(loc("Slayer Tower", false, false, true, 14, 13623)));
		Stub acc = new Stub();
		ScoredLocation s = new LocationScorer(travel()).rank(rec, acc, new LocationScorer.Options()).get(0);
		assertTrue(s.isLocked());
		assertTrue(s.getScore() < 0);
		assertTrue(s.getReasons().get(0).startsWith("needs Priest in peril"));
	}

	@Test
	public void cannonBonusNeedsTheQuest()
	{
		TaskRecord rec = new TaskRecord();
		rec.setLocations(Arrays.asList(loc("Stronghold Slayer Cave", true, false, false, 13, 9880)));
		Stub acc = new Stub();
		acc.cannon = true;
		ScoredLocation s = new LocationScorer(travel()).rank(rec, acc, new LocationScorer.Options()).get(0);
		assertTrue(s.getReasons().contains("cannon (finish Dwarf Cannon first)"));
		acc.quests.add("DWARF_CANNON");
		s = new LocationScorer(travel()).rank(rec, acc, new LocationScorer.Options()).get(0);
		assertTrue(s.getReasons().contains("cannon"));
	}

	@Test
	public void housePortalCountsAsTravelAndSpellsAreGated()
	{
		TaskRecord rec = new TaskRecord();
		rec.setLocations(Arrays.asList(loc("Slayer Tower", false, false, true, 14, 13623)));
		Stub acc = new Stub();
		acc.quests.add("PRIEST_IN_PERIL");
		ScoredLocation none = new LocationScorer(travel()).rank(rec, acc, new LocationScorer.Options()).get(0);
		assertTrue("Kharyrll spell needs Desert Treasure and 66 Magic", none.getTravel() == null);
		acc.houseFacilities.add("kharyrll portal");
		ScoredLocation withHouse = new LocationScorer(travel()).rank(rec, acc, new LocationScorer.Options()).get(0);
		assertEquals("House: Kharyrll Portal", withHouse.getTravel());
		assertTrue(withHouse.getScore() > none.getScore());
	}

	@Test
	public void locksSpotsBehindSkillLevels()
	{
		TaskRecord rec = new TaskRecord();
		rec.setLocations(Arrays.asList(loc("Ynysdail Cavern", false, false, true, 16, 9018)));
		ScoredLocation s = new LocationScorer(travel()).rank(rec, new Stub(), new LocationScorer.Options()).get(0);
		assertTrue(s.isLocked());
		assertTrue(s.getReasons().get(0).startsWith("needs 73 Sailing"));
	}

	@Test
	public void wildernessDetectedFromRegionsAndPenalised()
	{
		TaskRecord rec = new TaskRecord();
		rec.setLocations(Arrays.asList(
			loc("Wilderness Slayer Cave", true, true, false, 10, (3400 >> 6) << 8 | (3850 >> 6)),
			loc("Catacombs of Kourend", false, true, true, 12, 6556)));
		Stub acc = new Stub();
		acc.cannon = true;
		acc.quests.add("DWARF_CANNON");
		LocationScorer.Options opt = new LocationScorer.Options();
		List<ScoredLocation> r = new LocationScorer(travel()).rank(rec, acc, opt);
		assertEquals("Catacombs of Kourend", r.get(0).getLocation().getName());
		assertTrue(r.get(1).isWilderness());
		opt.avoidWilderness = false;
		r = new LocationScorer(travel()).rank(rec, acc, opt);
		assertEquals("Wilderness Slayer Cave", r.get(0).getLocation().getName());
	}

	@Test
	public void samePlaceMatchesKonarAreasToDatasetNames()
	{
		org.junit.Assert.assertTrue(com.slayersuite.advisor.Advisor.samePlace("Fremennik Slayer Dungeon", "Fremennik Slayer Cave"));
		org.junit.Assert.assertTrue(com.slayersuite.advisor.Advisor.samePlace("Catacombs of Kourend", "Catacombs of Kourend"));
		org.junit.Assert.assertTrue(com.slayersuite.advisor.Advisor.samePlace("Kalphite Lair", "Kalphite Lair (east entrance)"));
		org.junit.Assert.assertFalse(com.slayersuite.advisor.Advisor.samePlace("Slayer Tower", "Stronghold Slayer Cave"));
	}
}
