package com.slayersuite;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.slayersuite.data.LocationRecord;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class CannonTileTest
{
	private static LocationRecord withPins(int[]... pins)
	{
		LocationRecord l = new LocationRecord();
		List<List<Integer>> list = new ArrayList<>();
		for (int[] p : pins)
		{
			list.add(Arrays.asList(p[0], p[1]));
		}
		l.setPins(list);
		return l;
	}

	@Test
	public void picksTheTileCoveringTheMostSpawns()
	{
		// five spawns in a cluster and two far off: the cannon goes in the cluster, not at the spawns' middle
		LocationRecord l = withPins(new int[]{100, 100}, new int[]{103, 102}, new int[]{98, 104}, new int[]{101, 97}, new int[]{104, 99}, new int[]{140, 140}, new int[]{60, 60});
		int[] tile = l.cannonTile();
		int covered = 0;
		for (int[] p : new int[][]{{100, 100}, {103, 102}, {98, 104}, {101, 97}, {104, 99}})
		{
			if (Math.max(Math.abs(p[0] - tile[0]), Math.abs(p[1] - tile[1])) <= LocationRecord.CANNON_REACH)
			{
				covered++;
			}
		}
		assertEquals(5, covered);
	}

	@Test
	public void needsAFewSpawnsToSayAnything()
	{
		assertNull(withPins(new int[]{1, 1}, new int[]{2, 2}).cannonTile());
	}
}
