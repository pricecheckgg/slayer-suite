package com.slayersuite.history;

/** Plain arithmetic behind the floor marks, kept apart from the client so it can be tested. */
public final class FloorLootMath
{
	/** The pickup windows run this much past the moment the item really vanishes. */
	static final long WINDOW_SLACK_MS = 5_000L;
	/** A drop with this long left is about to vanish. */
	public static final long URGENT_MS = 30_000L;

	private FloorLootMath()
	{
	}

	/** How long after it fell a drop with this pickup window really lies on the floor. */
	public static long vanishAfter(long windowMs)
	{
		return Math.max(1_000L, windowMs - WINDOW_SLACK_MS);
	}

	/** "2:05" from milliseconds, rounded up to the second; "gone" at or below zero. */
	public static String countdown(long ms)
	{
		if (ms <= 0)
		{
			return "gone";
		}
		long s = (ms + 999) / 1000;
		return (s / 60) + ":" + String.format("%02d", s % 60);
	}
}
