package com.slayersuite;

import com.slayersuite.data.TravelPoints;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TravelPointsTest
{
	private static TravelPoints load()
	{
		TravelPoints p = new TravelPoints();
		p.load();
		return p;
	}

	private static TravelPoints.Point bank(TravelPoints p, String name)
	{
		for (TravelPoints.Point b : p.banks())
		{
			if (b.getName().equals(name))
			{
				return b;
			}
		}
		return null;
	}

	@Test
	public void banksAndMastersLoad()
	{
		TravelPoints p = load();
		assertTrue("only " + p.banks().size() + " banks", p.banks().size() >= 90);
		TravelPoints.Point edge = bank(p, "Edgeville bank");
		assertNotNull(edge);
		assertEquals(3095, edge.getX());
		assertEquals(3494, edge.getY());
		assertEquals("", edge.getRequirement());
		TravelPoints.Point zanaris = bank(p, "Zanaris bank");
		assertNotNull(zanaris);
		assertEquals("Lost City", zanaris.getRequirement());
		assertEquals(14, p.masters().size());
		TravelPoints.Point konar = p.master("Konar quo Maten");
		assertNotNull(konar);
		assertEquals(1309, konar.getX());
		assertEquals(3785, konar.getY());
		assertEquals(8, konar.getMasterId());
		assertEquals("Mount Karuulm", konar.getPlace());
		TravelPoints.Point duradel = p.masterFor(5);
		assertNotNull(duradel);
		assertEquals("Duradel", duradel.getName());
		assertEquals(1, duradel.getPlane());
		assertEquals("Shilo Village", duradel.getPlace());
		assertEquals(3109, p.master("Krystilia").getX());
		assertEquals(8614, p.master("Mortimer").getY());
		assertEquals("Edgeville Dungeon", p.master("Vannaka").getPlace());
		assertNull(p.masterFor(-1));
	}

	@Test
	public void nearestBankFoldsDungeons()
	{
		TravelPoints p = load();
		List<TravelPoints.Point> near = TravelPoints.nearest(p.banks(), 3100, 3500, 3);
		assertEquals("Edgeville bank", near.get(0).getName());
		// Vannaka stands in the Edgeville Dungeon, 6400 tiles north in the map: the banks above it come first, not Keldagrim's
		near = TravelPoints.nearest(p.banks(), 3147, 9914, 2);
		assertTrue(near.get(0).getName(), near.get(0).getName().equals("Grand Exchange") || near.get(0).getName().equals("Edgeville bank"));
		assertTrue(TravelPoints.distance(near.get(0), 3147, 9914) < 60);
		assertTrue(TravelPoints.nearest(p.banks(), 1300, 3790, 2).get(0).getName().contains("Karuulm"));
	}

	@Test
	public void requirementsTheClientCanCheck()
	{
		assertEquals(TravelPoints.Requirement.Kind.NONE, TravelPoints.requirement("").kind);
		assertEquals(TravelPoints.Requirement.Kind.NONE, TravelPoints.requirement("None").kind);
		TravelPoints.Requirement lost = TravelPoints.requirement("Lost City");
		assertEquals(TravelPoints.Requirement.Kind.QUEST, lost.kind);
		assertEquals("LOST_CITY", lost.key);
		assertEquals("MOURNINGS_END_PART_I", TravelPoints.requirement("Mourning's End Part I").key);
		assertEquals("THE_FREMENNIK_ISLES", TravelPoints.requirement("Fremennik Isles").key);
		assertEquals("LEGENDS_QUEST", TravelPoints.requirement("Legends' Quest").key);
		assertEquals("THE_CORSAIR_CURSE", TravelPoints.requirement("The Corsair Curse").key);
		assertEquals(TravelPoints.Requirement.Kind.QUEST, TravelPoints.requirement("Children of the Sun").kind);
		TravelPoints.Requirement farm = TravelPoints.requirement("Farming 85");
		assertEquals(TravelPoints.Requirement.Kind.SKILL, farm.kind);
		assertEquals("FARMING", farm.key);
		assertEquals(85, farm.level);
		assertEquals(TravelPoints.Requirement.Kind.UNKNOWN, TravelPoints.requirement("Sailing 67, must be built before use").kind);
		assertEquals(TravelPoints.Requirement.Kind.UNKNOWN, TravelPoints.requirement("Fire cape").kind);
		TravelPoints.Requirement diary = TravelPoints.requirement("Easy Fremennik Diary");
		assertEquals(TravelPoints.Requirement.Kind.DIARY, diary.kind);
		assertEquals("FREMENNIK_EASY", diary.key);
	}
}
