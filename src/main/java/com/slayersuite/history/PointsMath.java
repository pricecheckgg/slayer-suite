package com.slayersuite.history;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Value;

/** Slayer reward point arithmetic, kept pure so it can be tested without a client. */
public final class PointsMath
{
	public static final int SKIP_COST = 30;
	public static final int BLOCK_COST = 100;

	private PointsMath()
	{
	}

	/** Base points per task by master id (1 Turael .. 10 Mortimer); diary bonuses are not counted. */
	public static int basePoints(int masterId)
	{
		switch (masterId)
		{
			case 2: return 6;   // Mazchna
			case 3: return 8;   // Vannaka
			case 4: return 10;  // Chaeldar
			case 5: return 15;  // Duradel
			case 6: return 12;  // Nieve / Steve
			case 7: return 25;  // Krystilia
			case 8: return 18;  // Konar
			default: return 0;  // Turael, Aya, Spria, Mortimer (varies)
		}
	}

	/** How many times the base the n-th task in a streak pays: 1000th 50x, 250th 35x, 100th 25x, 50th 15x, 10th 5x, else 1x. */
	public static int milestoneMultiplier(int taskNumber)
	{
		if (taskNumber <= 0)
		{
			return 1;
		}
		if (taskNumber % 1000 == 0)
		{
			return 50;
		}
		if (taskNumber % 250 == 0)
		{
			return 35;
		}
		if (taskNumber % 100 == 0)
		{
			return 25;
		}
		if (taskNumber % 50 == 0)
		{
			return 15;
		}
		if (taskNumber % 10 == 0)
		{
			return 5;
		}
		return 1;
	}

	/** Points per task averaged over a full 1000-task cycle, milestone bonuses included. */
	public static double averagePointsPerTask(int masterId)
	{
		int base = basePoints(masterId);
		if (base == 0)
		{
			return 0;
		}
		long sum = 0;
		for (int n = 1; n <= 1000; n++)
		{
			sum += milestoneMultiplier(n);
		}
		return base * (sum / 1000.0);
	}

	/** The next task number in the streak that pays a bonus (every 10th). */
	public static int nextBonusTask(int streak)
	{
		return (Math.max(0, streak) / 10 + 1) * 10;
	}

	/** The next 50th, 100th, 250th or 1000th task. */
	public static int nextBigMilestone(int streak)
	{
		int s = Math.max(0, streak);
		int[] steps = {50, 100, 250, 1000};
		int best = Integer.MAX_VALUE;
		for (int step : steps)
		{
			int next = (s / step + 1) * step;
			best = Math.min(best, next);
		}
		return best;
	}

	/** Tasks it takes to earn back a spend at the master's average rate; -1 when the master pays nothing. */
	public static double paybackTasks(int cost, double avgPerTask)
	{
		return avgPerTask <= 0 ? -1 : cost / avgPerTask;
	}

	/** One task worth blocking: how often it comes up, your rate on it, and how much your expected rate rises without it. */
	@Value
	public static class BlockCandidate
	{
		String key;
		double share;
		double xpPerHour;
		double gainPct;
	}

	/**
	 * Expected xp/h over the master's roll table using your own rates (tasks with no rate count at the
	 * fallback), and the rise from removing each task you have a rate for. Best gains first, at most n.
	 */
	public static List<BlockCandidate> blockCandidates(Map<String, Integer> weightsByKey, Map<String, Double> xphByKey, double fallbackXph, int n)
	{
		List<BlockCandidate> out = new ArrayList<>();
		double totalW = 0;
		double totalWx = 0;
		for (Map.Entry<String, Integer> e : weightsByKey.entrySet())
		{
			double w = e.getValue();
			Double x = xphByKey.get(e.getKey());
			totalW += w;
			totalWx += w * (x == null ? fallbackXph : x);
		}
		if (totalW <= 0 || totalWx <= 0)
		{
			return out;
		}
		double expected = totalWx / totalW;
		for (Map.Entry<String, Integer> e : weightsByKey.entrySet())
		{
			Double x = xphByKey.get(e.getKey());
			if (x == null)
			{
				continue;
			}
			double w = e.getValue();
			double without = (totalWx - w * x) / (totalW - w);
			double gain = (without - expected) / expected * 100.0;
			if (gain > 0)
			{
				out.add(new BlockCandidate(e.getKey(), w / totalW, x, gain));
			}
		}
		out.sort((a, b) -> Double.compare(b.getGainPct(), a.getGainPct()));
		return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
	}
}
