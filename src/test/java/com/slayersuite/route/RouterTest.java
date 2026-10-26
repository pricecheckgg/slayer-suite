package com.slayersuite.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

public class RouterTest
{
	static WorldMap map;
	static TransportTable table;
	static Set<Transport> all;

	@BeforeClass
	public static void load()
	{
		map = WorldMap.load();
		table = TransportTable.load();
		all = new HashSet<>(table.all());
	}

	@Test
	public void walksAcrossLumbridge()
	{
		int start = Packed.of(3222, 3218, 0);
		int goal = Packed.of(3210, 3210, 0);
		Router.Route r = Router.find(map, table, all, start, goal, 200_000, System.nanoTime() + 2_000_000_000L);
		assertTrue(r.reached);
		assertEquals(start, (int) r.tiles.get(0));
		assertEquals(goal, r.end());
		assertTrue(r.tiles.size() >= 13 && r.tiles.size() <= 40);
		assertTrue(r.indexOf(goal) == r.tiles.size() - 1);
	}

	@Test
	public void entersTheCatacombsThroughTheStatue()
	{
		int start = Packed.of(1643, 3669, 0);
		int goal = Packed.of(1666, 10052, 0);
		Router.Route r = Router.find(map, table, all, start, goal, 300_000, System.nanoTime() + 3_000_000_000L);
		assertTrue(r.reached);
		assertEquals(1, r.steps.size());
		assertEquals("Investigate Statue", r.steps.get(0).transport.label());
		assertEquals(Packed.of(1639, 3673, 0), (int) r.tiles.get(r.steps.get(0).index));
	}

	@Test
	public void unreachableEndsNearest()
	{
		int start = Packed.of(3222, 3218, 0);
		int goal = Packed.of(1, 1, 0);
		Router.Route r = Router.find(map, table, new HashSet<>(), start, goal, 2_000, System.nanoTime() + 1_000_000_000L);
		assertTrue(!r.reached);
		assertTrue(r.tiles.size() >= 1);
	}

	@Test
	public void headsForTheGroundAboveADungeon()
	{
		// Pollnivneach to the Smoke Dungeon's dust devils, 6400 tiles "north" in the data: down the well
		Router.Route r = Router.find(map, table, all, Packed.of(3359, 2971, 0), Packed.of(3179, 9362, 0), 400_000, System.nanoTime() + 3_000_000_000L);
		assertTrue(r.reached);
		boolean well = false;
		for (Router.Step s : r.steps)
		{
			well |= s.transport.label().contains("Smokey well");
		}
		assertTrue(well);
	}

	@Test
	public void keepsOutOfTheWildernessWhenAsked()
	{
		int tower = Packed.of(3428, 3533, 0);
		int catacombs = Packed.of(1708, 10014, 0);
		Router.Route free = Router.find(map, table, all, tower, catacombs, 800_000, System.nanoTime() + 3_000_000_000L, false);
		assertTrue(free.reached);
		Router.Route safe = Router.find(map, table, all, tower, catacombs, 800_000, System.nanoTime() + 3_000_000_000L, true);
		for (int tile : safe.tiles)
		{
			assertTrue(!Router.inWilderness(tile));
		}
		for (Router.Step s : safe.steps)
		{
			assertTrue(!s.transport.label().contains("Wilderness Ditch"));
		}
	}

	@Test
	public void aPinWithTheWrongFloorMovesToTheFloorThatExists()
	{
		// the Slayer Tower basement is floor 3 in the map; the dataset's pins say floor 0
		int pin = Packed.of(3427, 9943, 0);
		assertTrue(map.isBlocked(pin));
		int fixed = Router.walkableNear(map, pin);
		assertEquals(3, Packed.plane(fixed));
		Router.Route r = Router.find(map, table, all, Packed.of(3428, 3533, 0), fixed, 300_000, System.nanoTime() + 3_000_000_000L);
		assertTrue(r.reached);
		assertEquals("Climb-down Ladder", r.steps.get(0).transport.label());
	}
}
