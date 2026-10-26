package com.slayersuite.history;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Turns inventory gains into picked-up loot by matching them against drops the loot log saw
 * recently. Pure, so it can be tested without a client.
 */
public final class PickupMatcher
{
	/** A drop the loot log reported that has not reached the inventory yet. */
	public static final class Pending
	{
		public final int id;
		public long quantity;
		public final long priceEach;
		public final long at;
		/** Where the monster died; -1 when unknown. */
		public final int x;
		public final int y;
		public final int plane;
		/** True once the task's kill counter moved for this death. */
		public boolean killConfirmed;
		/** Goes straight into a carried herb sack or seed box, so the inventory never shows it. */
		public boolean autoStored;
		/** The task the drop came from (an index the caller owns); -1 when unknown. */
		public int task = -1;

		public Pending(int id, long quantity, long priceEach, long at)
		{
			this(id, quantity, priceEach, at, -1, -1, -1, true);
		}

		public Pending(int id, long quantity, long priceEach, long at, int x, int y, int plane, boolean killConfirmed)
		{
			this.id = id;
			this.quantity = quantity;
			this.priceEach = priceEach;
			this.at = at;
			this.x = x;
			this.y = y;
			this.plane = plane;
			this.killConfirmed = killConfirmed;
		}

		/** Within reach of the player: same plane and at most maxTiles away, or unknown on either side. */
		public boolean near(int px, int py, int pplane, int maxTiles)
		{
			if (x < 0 || px < 0)
			{
				return true;
			}
			return plane == pplane && Math.max(Math.abs(x - px), Math.abs(y - py)) <= maxTiles;
		}
	}

	private PickupMatcher()
	{
	}

	/**
	 * Removes drops older than the window. Those whose kill the task counted, and that still have
	 * quantity left, were never picked up: they go to missedOut when it is given.
	 */
	public static void expire(List<Pending> pending, long nowMs, long windowMs, List<Pending> missedOut)
	{
		for (Iterator<Pending> it = pending.iterator(); it.hasNext(); )
		{
			Pending pd = it.next();
			boolean stale = nowMs - pd.at > windowMs;
			if (stale || pd.quantity <= 0)
			{
				if (stale && pd.quantity > 0 && pd.killConfirmed && missedOut != null)
				{
					missedOut.add(pd);
				}
				it.remove();
			}
		}
	}

	/**
	 * Drops pending drops older than the window, then for every item whose count grew between
	 * the two inventory snapshots takes as much as the pending drops of that item allow.
	 * Returns the takes as {item id, quantity, value, task}; the pending list is updated in place.
	 */
	public static List<long[]> match(Map<Integer, Integer> before, Map<Integer, Integer> now, List<Pending> pending, long nowMs, long windowMs)
	{
		return match(before, now, pending, nowMs, windowMs, -1, -1, -1, Integer.MAX_VALUE);
	}

	/**
	 * As above, but a drop is only taken when its monster died within maxTiles of where the
	 * player stands now and the task's kill counter confirmed that death.
	 */
	public static List<long[]> match(Map<Integer, Integer> before, Map<Integer, Integer> now, List<Pending> pending, long nowMs, long windowMs, int px, int py, int pplane, int maxTiles)
	{
		List<long[]> out = new ArrayList<>();
		expire(pending, nowMs, windowMs, null);
		if (pending.isEmpty())
		{
			return out;
		}
		for (Map.Entry<Integer, Integer> en : now.entrySet())
		{
			long gain = en.getValue() - before.getOrDefault(en.getKey(), 0);
			if (gain <= 0)
			{
				continue;
			}
			for (Iterator<Pending> it = pending.iterator(); it.hasNext() && gain > 0; )
			{
				Pending pd = it.next();
				if (pd.id != en.getKey() || !pd.killConfirmed || !pd.near(px, py, pplane, maxTiles))
				{
					continue;
				}
				long take = Math.min(gain, pd.quantity);
				out.add(new long[]{pd.id, take, take * pd.priceEach, pd.task});
				pd.quantity -= take;
				gain -= take;
				if (pd.quantity <= 0)
				{
					it.remove();
				}
			}
		}
		return out;
	}
}
