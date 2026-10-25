package com.slayersuite;

import com.slayersuite.history.PickupMatcher;
import com.slayersuite.history.PickupMatcher.Pending;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PickupMatcherTest
{
	private static Map<Integer, Integer> inv(Object... kv)
	{
		Map<Integer, Integer> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.put((Integer) kv[i], (Integer) kv[i + 1]);
		}
		return m;
	}

	@Test
	public void gainMatchingADropIsTaken()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(995, 1200, 1, 1000));
		List<long[]> takes = PickupMatcher.match(inv(995, 500), inv(995, 1700), pending, 2000, 90_000);
		assertEquals(1, takes.size());
		assertEquals(995, takes.get(0)[0]);
		assertEquals(1200, takes.get(0)[1]);
		assertEquals(1200, takes.get(0)[2]);
		assertTrue(pending.isEmpty());
	}

	@Test
	public void gainWithoutADropIsIgnored()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 1, 1800, 1000));
		List<long[]> takes = PickupMatcher.match(inv(), inv(995, 300), pending, 2000, 90_000);
		assertTrue(takes.isEmpty());
		assertEquals(1, pending.size());
	}

	@Test
	public void partialPickupLeavesTheRestPending()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 3, 1800, 1000));
		List<long[]> takes = PickupMatcher.match(inv(), inv(207, 2), pending, 2000, 90_000);
		assertEquals(1, takes.size());
		assertEquals(2, takes.get(0)[1]);
		assertEquals(3600, takes.get(0)[2]);
		assertEquals(1, pending.size());
		assertEquals(1, pending.get(0).quantity);
	}

	@Test
	public void gainLargerThanTheDropOnlyCountsTheDrop()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 1, 1800, 1000));
		List<long[]> takes = PickupMatcher.match(inv(207, 0), inv(207, 5), pending, 2000, 90_000);
		assertEquals(1, takes.size());
		assertEquals(1, takes.get(0)[1]);
		assertTrue(pending.isEmpty());
	}

	@Test
	public void staleDropsArePruned()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 1, 1800, 1000));
		List<long[]> takes = PickupMatcher.match(inv(), inv(207, 1), pending, 200_000, 90_000);
		assertTrue(takes.isEmpty());
		assertTrue(pending.isEmpty());
	}

	@Test
	public void twoDropsOfTheSameItemFillInOrder()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 1, 1800, 1000));
		pending.add(new Pending(207, 1, 1900, 1500));
		List<long[]> takes = PickupMatcher.match(inv(), inv(207, 2), pending, 2000, 90_000);
		assertEquals(2, takes.size());
		assertEquals(1800, takes.get(0)[2]);
		assertEquals(1900, takes.get(1)[2]);
		assertTrue(pending.isEmpty());
	}

	@Test
	public void dropFarFromThePlayerIsNotTaken()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 1, 1800, 1000, 3200, 3200, 0, true));
		List<long[]> takes = PickupMatcher.match(inv(), inv(207, 1), pending, 2000, 90_000, 3250, 3200, 0, 10);
		assertTrue(takes.isEmpty());
		assertEquals(1, pending.size());
		takes = PickupMatcher.match(inv(), inv(207, 1), pending, 2500, 90_000, 3205, 3203, 0, 10);
		assertEquals(1, takes.size());
	}

	@Test
	public void dropOnAnotherPlaneIsNotTaken()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 1, 1800, 1000, 3200, 3200, 1, true));
		List<long[]> takes = PickupMatcher.match(inv(), inv(207, 1), pending, 2000, 90_000, 3200, 3200, 0, 10);
		assertTrue(takes.isEmpty());
	}

	@Test
	public void dropWithoutACountedKillIsNotTaken()
	{
		List<Pending> pending = new ArrayList<>();
		Pending pd = new Pending(207, 1, 1800, 1000, -1, -1, -1, false);
		pending.add(pd);
		List<long[]> takes = PickupMatcher.match(inv(), inv(207, 1), pending, 2000, 90_000);
		assertTrue(takes.isEmpty());
		pd.killConfirmed = true;
		takes = PickupMatcher.match(inv(207, 1), inv(207, 2), pending, 2500, 90_000);
		assertEquals(1, takes.size());
	}

	@Test
	public void expiredConfirmedDropsAreReportedMissed()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 2, 1800, 1000, -1, -1, -1, true));
		pending.add(new Pending(995, 50, 1, 1000, -1, -1, -1, false));
		pending.add(new Pending(208, 1, 900, 150_000, -1, -1, -1, true));
		List<Pending> missed = new ArrayList<>();
		PickupMatcher.expire(pending, 200_000, 90_000, missed);
		assertEquals(1, missed.size());
		assertEquals(207, missed.get(0).id);
		assertEquals(2, missed.get(0).quantity);
		assertEquals(1, pending.size());
		assertEquals(208, pending.get(0).id);
	}

	@Test
	public void partlyPickedUpDropReportsOnlyTheRest()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 3, 1800, 1000));
		PickupMatcher.match(inv(), inv(207, 2), pending, 2000, 90_000);
		List<Pending> missed = new ArrayList<>();
		PickupMatcher.expire(pending, 200_000, 90_000, missed);
		assertEquals(1, missed.size());
		assertEquals(1, missed.get(0).quantity);
	}

	@Test
	public void losingItemsNeverCounts()
	{
		List<Pending> pending = new ArrayList<>();
		pending.add(new Pending(207, 1, 1800, 1000));
		List<long[]> takes = PickupMatcher.match(inv(207, 4), inv(207, 3), pending, 2000, 90_000);
		assertTrue(takes.isEmpty());
	}
}
