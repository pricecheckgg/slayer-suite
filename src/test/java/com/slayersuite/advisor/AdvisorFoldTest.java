package com.slayersuite.advisor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.slayersuite.data.LocationRecord;
import java.util.Arrays;
import net.runelite.api.coords.WorldPoint;
import org.junit.Test;

public class AdvisorFoldTest
{
	private static LocationRecord spot(int x, int y)
	{
		LocationRecord l = new LocationRecord();
		l.setName("spot");
		l.setX(x);
		l.setY(y);
		l.setPins(Arrays.asList(Arrays.asList(x, y + 20)));
		return l;
	}

	@Test
	public void caveMouthIsNextToTheCaveBelowIt()
	{
		// Iorwerth Dungeon lies 6400 tiles "north" of Prifddinas; its cave mouth is a few tiles from the spawns
		LocationRecord iorwerth = spot(3228, 12371);
		assertTrue(Advisor.foldedDistance(iorwerth, new WorldPoint(3230, 6005, 0)) < Advisor.AT_HAND_TILES);
		// the Fremennik Slayer Dungeon is a continent away from that cave mouth
		LocationRecord fremennik = spot(2701, 9976);
		assertTrue(Advisor.foldedDistance(fremennik, new WorldPoint(3230, 6005, 0)) > 2000);
	}

	@Test
	public void sameLevelDistanceIsPlain()
	{
		assertEquals(10, Advisor.foldedDistance(spot(100, 100), new WorldPoint(100, 110, 0)));
		assertEquals(-1, Advisor.foldedDistance(new LocationRecord(), new WorldPoint(1, 1, 0)));
	}
}
