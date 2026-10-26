package com.slayersuite;

import com.slayersuite.history.PointsMath;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PointsMathTest
{
	@Test
	public void milestonesPayTheWikiMultipliers()
	{
		assertEquals(1, PointsMath.milestoneMultiplier(7));
		assertEquals(5, PointsMath.milestoneMultiplier(10));
		assertEquals(15, PointsMath.milestoneMultiplier(50));
		assertEquals(25, PointsMath.milestoneMultiplier(100));
		assertEquals(35, PointsMath.milestoneMultiplier(250));
		assertEquals(50, PointsMath.milestoneMultiplier(1000));
		assertEquals(5, PointsMath.milestoneMultiplier(110));
	}

	@Test
	public void averageIncludesBonusesOverTheCycle()
	{
		// 900 plain + 80 x5 + 8 x15 + 8 x25 + 3 x35 + 1 x50 = 1775 base over 1000 tasks
		assertEquals(15 * 1.775, PointsMath.averagePointsPerTask(5), 0.001);
		assertEquals(0, PointsMath.averagePointsPerTask(1), 0.0);
	}

	@Test
	public void nextMilestones()
	{
		assertEquals(130, PointsMath.nextBonusTask(128));
		assertEquals(10, PointsMath.nextBonusTask(0));
		assertEquals(150, PointsMath.nextBigMilestone(128));
		assertEquals(250, PointsMath.nextBigMilestone(200));
		assertEquals(800, PointsMath.nextBigMilestone(750));
		assertEquals(1000, PointsMath.nextBigMilestone(950));
	}

	@Test
	public void skipPayback()
	{
		assertEquals(30 / (15 * 1.775), PointsMath.paybackTasks(30, PointsMath.averagePointsPerTask(5)), 0.001);
		assertEquals(-1, PointsMath.paybackTasks(30, 0), 0.0);
	}

	@Test
	public void blockingTheSlowFrequentTaskGainsMost()
	{
		Map<String, Integer> w = new LinkedHashMap<>();
		w.put("slow", 10);
		w.put("fast", 10);
		w.put("unknown", 5);
		Map<String, Double> x = new HashMap<>();
		x.put("slow", 20_000.0);
		x.put("fast", 60_000.0);
		List<PointsMath.BlockCandidate> c = PointsMath.blockCandidates(w, x, 40_000.0, 3);
		assertEquals(1, c.size());
		assertEquals("slow", c.get(0).getKey());
		assertTrue(c.get(0).getGainPct() > 20);
		assertEquals(0.4, c.get(0).getShare(), 0.001);
	}
}
