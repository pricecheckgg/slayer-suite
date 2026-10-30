package com.slayersuite.history;

import lombok.Data;

/** Everything we have measured for one task across every assignment, stored per RuneScape profile. */
@Data
public class TaskStats
{
	private String key;
	private String name;
	private int tasksStarted;
	private int tasksFinished;
	private long kills;
	/** Milliseconds spent actually killing (gaps longer than a short break are not counted). */
	private long activeMs;
	private long slayerXp;
	private long lootGp;
	private long lastSeen;
	/** Exponential moving averages of consumption per kill, fed by the trip tracker. */
	private double foodPerKill;
	private double dosesPerKill;
	private double prayerPerKill;
	/** Slayer points earned finishing this task, and how many finishes that covers. */
	private long points;
	private int pointSamples;
	/** Times this task was skipped or blocked, and the points that cost. */
	private int skips;
	private int blocks;
	private long pointsSpent;
	/** Your fastest assignment of this task (20+ kills): rate and how much of it was idle or away. */
	private double bestKph;
	private long bestIdleMs;
	private long bestAwayMs;
	private long bestMs;
	/** Kills per hour with and without a cannon, smoothed over assignments; 0 until seen. */
	private double cannonKph;
	private double plainKph;
	private int superiors;
	/** What you did at each spot of this task: kills, active time, Slayer xp and picked-up loot. */
	private java.util.Map<String, SpotStats> spots = new java.util.LinkedHashMap<>();
	/** Rare drops seen on this task, by item name: {times seen, kills of the monsters that drop it when it last came, wall time}. */
	private java.util.Map<String, long[]> drops = new java.util.LinkedHashMap<>();
	/** Kills per monster on this task (deaths that dropped loot), by dataset monster name. */
	private java.util.Map<String, Long> monsterKills = new java.util.LinkedHashMap<>();

	public static class SpotStats
	{
		public long kills;
		public long activeMs;
		public long xp;
		public long loot;

		public double killsPerHour()
		{
			return activeMs > 0 ? kills * 3_600_000.0 / activeMs : 0;
		}

		public double gpPerHour()
		{
			return activeMs > 0 ? loot * 3_600_000.0 / activeMs : 0;
		}

		public double xpPerHour()
		{
			return activeMs > 0 ? xp * 3_600_000.0 / activeMs : 0;
		}

		/** Enough play at this spot for a rate to mean something. */
		public boolean hasRate()
		{
			return kills >= 15 && activeMs >= 120_000L;
		}
	}

	public long[] drop(String key)
	{
		if (drops == null)
		{
			drops = new java.util.LinkedHashMap<>();
		}
		return drops.computeIfAbsent(key, k -> new long[3]);
	}

	/** The stored mark for an item, or null when never seen. */
	public long[] dropMark(String key)
	{
		return drops == null ? null : drops.get(key);
	}

	public void monsterKill(String monster)
	{
		if (monsterKills == null)
		{
			monsterKills = new java.util.LinkedHashMap<>();
		}
		monsterKills.merge(monster, 1L, Long::sum);
	}

	public long monsterKills(String monster)
	{
		Long n = monsterKills == null ? null : monsterKills.get(monster);
		return n == null ? 0 : n;
	}

	public boolean anyMonsterKills()
	{
		return monsterKills != null && !monsterKills.isEmpty();
	}

	public SpotStats spot(String name)
	{
		if (spots == null)
		{
			spots = new java.util.LinkedHashMap<>();
		}
		return spots.computeIfAbsent(name, k -> new SpotStats());
	}

	public double avgPoints()
	{
		return pointSamples > 0 ? points / (double) pointSamples : 0;
	}

	public boolean hasRate()
	{
		return kills >= 5 && activeMs > 60_000L;
	}

	public double killsPerHour()
	{
		return hasRate() ? kills * 3_600_000.0 / activeMs : 0;
	}

	public double xpPerHour()
	{
		return hasRate() ? slayerXp * 3_600_000.0 / activeMs : 0;
	}

	public double gpPerHour()
	{
		return hasRate() ? lootGp * 3_600_000.0 / activeMs : 0;
	}

	public double xpPerKill()
	{
		return kills > 0 ? slayerXp / (double) kills : 0;
	}

	public double gpPerKill()
	{
		return kills > 0 ? lootGp / (double) kills : 0;
	}

	/** Adds one session's measurements. */
	public void add(long sessionKills, long sessionActiveMs, long sessionXp, long sessionGp)
	{
		kills += Math.max(0, sessionKills);
		activeMs += Math.max(0, sessionActiveMs);
		slayerXp += Math.max(0, sessionXp);
		lootGp += Math.max(0, sessionGp);
		lastSeen = System.currentTimeMillis();
	}

	public TaskStats copy()
	{
		TaskStats c = new TaskStats();
		c.key = key;
		c.name = name;
		c.tasksStarted = tasksStarted;
		c.tasksFinished = tasksFinished;
		c.kills = kills;
		c.activeMs = activeMs;
		c.slayerXp = slayerXp;
		c.lootGp = lootGp;
		c.lastSeen = lastSeen;
		c.foodPerKill = foodPerKill;
		c.dosesPerKill = dosesPerKill;
		c.prayerPerKill = prayerPerKill;
		c.points = points;
		c.pointSamples = pointSamples;
		c.skips = skips;
		c.blocks = blocks;
		c.pointsSpent = pointsSpent;
		c.bestKph = bestKph;
		c.bestIdleMs = bestIdleMs;
		c.bestAwayMs = bestAwayMs;
		c.bestMs = bestMs;
		c.cannonKph = cannonKph;
		c.plainKph = plainKph;
		c.superiors = superiors;
		c.monsterKills = monsterKills == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(monsterKills);
		c.drops = new java.util.LinkedHashMap<>();
		if (drops != null)
		{
			for (java.util.Map.Entry<String, long[]> e : drops.entrySet())
			{
				c.drops.put(e.getKey(), e.getValue() == null ? new long[3] : e.getValue().clone());
			}
		}
		c.spots = new java.util.LinkedHashMap<>();
		if (spots != null)
		{
			for (java.util.Map.Entry<String, SpotStats> e : spots.entrySet())
			{
				SpotStats x = new SpotStats();
				x.kills = e.getValue().kills;
				x.activeMs = e.getValue().activeMs;
				x.xp = e.getValue().xp;
				x.loot = e.getValue().loot;
				c.spots.put(e.getKey(), x);
			}
		}
		return c;
	}
}
