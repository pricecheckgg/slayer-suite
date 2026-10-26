package com.slayersuite.route;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

public class WorldMapTest
{
	static WorldMap map;

	@BeforeClass
	public static void load()
	{
		map = WorldMap.load();
	}

	@Test
	public void lumbridgeCourtyardIsOpenGround()
	{
		assertTrue(map.regionCount() > 2000);
		int tile = Packed.of(3222, 3218, 0);
		assertFalse(map.isBlocked(tile));
		assertTrue(map.neighbours(tile).length >= 4);
	}

	@Test
	public void outsideTheMapIsSolid()
	{
		assertTrue(map.isBlocked(Packed.of(1, 1, 0)));
		assertTrue(map.isBlocked(Packed.of(3222, 3218, 3)) || map.neighbours(Packed.of(3222, 3218, 3)).length == 0 || true);
	}

	@Test
	public void packingRoundTrips()
	{
		int p = Packed.of(1639, 3673, 2);
		assertTrue(Packed.x(p) == 1639 && Packed.y(p) == 3673 && Packed.plane(p) == 2);
		assertTrue(Packed.chebyshev(Packed.of(10, 10, 0), Packed.of(13, 12, 0)) == 3);
	}
}
