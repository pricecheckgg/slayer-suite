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
		return c;
	}
}
