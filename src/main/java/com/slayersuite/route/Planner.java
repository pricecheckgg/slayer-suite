package com.slayersuite.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Chooses between walking from where you stand and teleporting first: the route is searched from
 * your tile and from every landing you can reach, and the plan with the fewest ticks wins. A spot
 * within a short walk is always walked to.
 */
public final class Planner
{
	/** Walks this short are never worth a teleport. */
	static final int NEAR_TILES = 40;

	/** Where a teleport you own puts you, and what it costs to use. */
	public static final class Landing
	{
		public final String label;
		public final String status;
		public final int tile;
		/** Ticks to use it, including any detour it implies (a bank visit, a spellbook swap). */
		public final int ticks;

		public Landing(String label, String status, int tile, int ticks)
		{
			this.label = label;
			this.status = status;
			this.tile = tile;
			this.ticks = ticks;
		}
	}

	public static final class Plan
	{
		/** The teleport to use first, or null to walk from where you are. */
		public final Landing teleport;
		/** From your tile, or from the landing. */
		public final Router.Route route;
		/** Where the plan was made from. */
		public final int start;
		public final int ticks;

		Plan(Landing teleport, Router.Route route, int start, int ticks)
		{
			this.teleport = teleport;
			this.route = route;
			this.start = start;
			this.ticks = ticks;
		}

		public boolean reached()
		{
			return route != null && route.reached;
		}
	}

	private Planner()
	{
	}

	/** Ticks to cover a route: running covers two tiles a tick; transports already count their own ticks. */
	public static int ticksFor(int cost)
	{
		return (cost + 1) / 2;
	}

	public static Plan plan(WorldMap map, TransportTable table, Set<Transport> usable, int start, int goal, List<Landing> landings, boolean avoidWilderness, int maxExpansions, long budgetNanos)
	{
		// A short walk first: cheap, and it settles most cases.
		Router.Route near = Router.find(map, table, usable, start, goal, maxExpansions, System.nanoTime() + budgetNanos, avoidWilderness, NEAR_TILES + 1);
		if (near.reached && near.cost <= NEAR_TILES)
		{
			return new Plan(null, near, start, ticksFor(near.cost));
		}
		// Then the landings, nearest the spot first; once one plan exists, a later landing only
		// searches as far as it could still beat it, so a landing on the wrong continent fails fast.
		List<Landing> ordered = new ArrayList<>(landings);
		ordered.sort(Comparator.comparingInt(l -> Router.heuristic(l.tile, goal)));
		Plan best = null;
		for (Landing l : ordered)
		{
			int cap = best == null ? Integer.MAX_VALUE : 2 * (best.ticks - l.ticks) + 1;
			if (cap <= 0)
			{
				continue;
			}
			int from = Router.walkableNear(map, l.tile);
			Router.Route r = Router.find(map, table, usable, from, goal, maxExpansions, System.nanoTime() + budgetNanos, avoidWilderness, cap);
			if (!r.reached)
			{
				continue;
			}
			int ticks = l.ticks + ticksFor(r.cost);
			if (best == null || ticks < best.ticks)
			{
				best = new Plan(l, r, start, ticks);
			}
		}
		// Finally the full walk, but only as far as it could still beat the best teleport.
		int cap = best == null ? Integer.MAX_VALUE : best.ticks * 2 + 1;
		Router.Route walk = Router.find(map, table, usable, start, goal, maxExpansions, System.nanoTime() + budgetNanos, avoidWilderness, cap);
		if (walk.reached && (best == null || ticksFor(walk.cost) <= best.ticks))
		{
			return new Plan(null, walk, start, ticksFor(walk.cost));
		}
		return best != null ? best : new Plan(null, walk, start, Integer.MAX_VALUE);
	}
}
