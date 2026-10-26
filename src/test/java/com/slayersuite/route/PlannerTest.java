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

	@Test
	public void ridesTheSpiritTreeFromTheGrandExchange()
	{
		// from the Grand Exchange to the Gnome Stronghold tree: 20 tiles to the GE tree and a ride, not a 700-tile walk
		Planner.Plan p = Planner.plan(map, table, all, Packed.of(3170, 3490, 0), Packed.of(2461, 3444, 0), Collections.emptyList(), false, 800_000, 3_000_000_000L);
		assertTrue(p.reached());
		assertNull(p.teleport);
		boolean rode = false;
		for (Router.Step s : p.route.steps)
		{
			rode |= s.transport.kind == Transport.Kind.SPIRIT_TREE;
		}
		assertTrue(rode);
		assertTrue(p.ticks < 60);
	}

	@Test
	public void aSolidFairyRingCanStillBeUsed()
	{
		// Zanaris's ring sits on a solid tile; standing beside it, the plan still rides it
		Planner.Plan p = Planner.plan(map, table, all, Packed.of(2409, 4434, 0), Packed.of(3426, 3017, 0), Collections.emptyList(), false, 800_000, 3_000_000_000L);
		assertTrue(p.reached());
		boolean ring = false;
		for (Router.Step s : p.route.steps)
		{
			ring |= s.transport.kind == Transport.Kind.FAIRY_RING;
		}
		assertTrue(ring);
	}

	@Test
	public void aTeleportMustBeatTheWalkClearly()
	{
		// a landing that only shaves a few ticks off a short walk loses to the walk, and the walk is kept either way
		Planner.Landing nearby = new Planner.Landing("Nearby ring", "fairy rings", Packed.of(3215, 3212, 0), 10);
		Planner.Plan p = Planner.plan(map, table, all, Packed.of(3222, 3218, 0), Packed.of(3100, 3250, 0), Collections.singletonList(nearby), false, 800_000, 3_000_000_000L);
		assertTrue(p.reached());
		assertNull(p.teleport);
		assertNotNull(p.walk);
		Planner.Plan far = Planner.plan(map, table, all, Packed.of(3222, 3218, 0), SMOKE_DUNGEON, Arrays.asList(POLLNIVNEACH), false, 800_000, 3_000_000_000L);
		assertNotNull(far.teleport);
		assertTrue(far.walk == null || !far.walk.reached || far.walkTicks() > far.ticks + Planner.MARGIN_TICKS);
	}
}
