package com.slayersuite.route;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

public class PlannerTest
{
	static WorldMap map;
	static TransportTable table;
	static Set<Transport> all;
	static final int SMOKE_DUNGEON = Packed.of(3179, 9362, 0);
	static final Planner.Landing POLLNIVNEACH = new Planner.Landing("Fairy ring DLQ", "fairy rings", Packed.of(3359, 2971, 0), 10);

	@BeforeClass
	public static void load()
	{
		map = WorldMap.load();
		table = TransportTable.load();
		all = new HashSet<>(table.all());
	}

	@Test
	public void teleportsWhenTheWalkIsLong()
	{
		Planner.Plan p = Planner.plan(map, table, all, Packed.of(3222, 3218, 0), SMOKE_DUNGEON, Arrays.asList(POLLNIVNEACH), false, 800_000, 3_000_000_000L);
		assertTrue(p.reached());
		assertNotNull(p.teleport);
		assertEquals("Fairy ring DLQ", p.teleport.label);
		assertTrue(p.ticks < 200);
	}

	@Test
	public void walksWhenAlreadyClose()
	{
		Planner.Plan p = Planner.plan(map, table, all, Packed.of(3359, 2971, 0), SMOKE_DUNGEON, Arrays.asList(POLLNIVNEACH), false, 800_000, 3_000_000_000L);
		assertTrue(p.reached());
		assertNull(p.teleport);
	}

	@Test
	public void aShortWalkIsNeverTeleported()
	{
		List<Planner.Landing> silly = Collections.singletonList(new Planner.Landing("Anything", "carried", Packed.of(3210, 3210, 0), 0));
		Planner.Plan p = Planner.plan(map, table, all, Packed.of(3222, 3218, 0), Packed.of(3215, 3212, 0), silly, false, 100_000, 2_000_000_000L);
		assertNull(p.teleport);
		assertTrue(p.reached());
	}
}
