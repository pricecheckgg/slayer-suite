package com.slayersuite.route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses between walking from where you stand and teleporting first: the route is searched from
 * your tile and from every landing you can reach, and the plan with the fewest ticks wins. A spot
 * within a short walk is always walked to.
 */
public final class Planner
{
	/** Walks this short are never worth a teleport: a charge is a charge, and the chevrons are the point. */
	static final int NEAR_TILES = 100;
	/** A teleport or ride must save at least this many ticks, and this share of the walk, to replace it. */
	static final int MARGIN_TICKS = 25;
	static final double MARGIN_SHARE = 0.7;
	/** Tiles a search to or from a network stop may look at; stops are close to roads, so this is plenty. */
	static final int NETWORK_EXPANSIONS = 120_000;

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
		/** The plain walk from where you stand, when one reaches the spot; null otherwise. */
		public final Router.Route walk;

		Plan(Landing teleport, Router.Route route, int start, int ticks)
		{
			this(teleport, route, start, ticks, null);
		}

		Plan(Landing teleport, Router.Route route, int start, int ticks, Router.Route walk)
		{
			this.teleport = teleport;
			this.route = route;
			this.start = start;
			this.ticks = ticks;
			this.walk = walk;
		}

		/** Ticks the plain walk would take; -1 when there is none. */
		public int walkTicks()
		{
			return walk != null && walk.reached ? ticksFor(walk.cost) : -1;
		}

		public boolean reached()
		{
			return route != null && route.reached;
		}
	}

	private Planner()
	{
	}

	/** The n stops nearest a tile as the crow flies, dungeons folded onto the ground above them. */
	static List<Transport> nearest(List<Transport> stops, int tile, int n)
	{
		List<Transport> sorted = new ArrayList<>(stops);
		sorted.sort(Comparator.comparingInt(s -> Router.heuristic(s.origin, tile)));
		return sorted.size() > n ? new ArrayList<>(sorted.subList(0, n)) : sorted;
	}

	/** Ticks to cover a route: running covers two tiles a tick; transports already count their own ticks. */
	public static int ticksFor(int cost)
	{
		return (cost + 1) / 2;
	}

	public static Plan plan(WorldMap map, TransportTable table, Set<Transport> usable, int start, int goal, List<Landing> landings, boolean avoidWilderness, int maxExpansions, long budgetNanos)
	{
		return plan(map, table, usable, start, goal, landings, avoidWilderness, maxExpansions, budgetNanos, 200);
	}

	/**
	 * As above; walkUpTo is how long a walk is still searched for the chevrons when a teleport is
	 * quicker, so there is a line to follow from where you stand whenever one exists.
	 */
	public static Plan plan(WorldMap map, TransportTable table, Set<Transport> usable, int start, int goal, List<Landing> landings, boolean avoidWilderness, int maxExpansions, long budgetNanos, int walkUpTo)
	{
		// A short walk first: cheap, and it settles most cases.
		Router.Route near = Router.find(map, table, usable, start, goal, maxExpansions, System.nanoTime() + budgetNanos, avoidWilderness, NEAR_TILES + 1);
		if (near.reached && near.cost <= NEAR_TILES)
		{
			return new Plan(null, near, start, ticksFor(near.cost), near);
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
			int from = map.isBlocked(l.tile) && !table.from(l.tile).isEmpty() ? l.tile : Router.walkableNear(map, l.tile);
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
		// Rides: from a stop near you to a stop near the spot, for every network you can use. Each
		// search is small, pairs that cannot beat the best plan by straight line are skipped, and the
		// walk to a stop near you is searched once however many far stops it serves.
		for (Transport.Kind kind : table.networks())
		{
			List<Transport> usableStops = new ArrayList<>();
			for (Transport s : table.stops(kind))
			{
				if (usable.contains(s))
				{
					usableStops.add(s);
				}
			}
			if (usableStops.size() < 2)
			{
				continue;
			}
			List<Transport> nearMe = nearest(usableStops, start, 2);
			List<Transport> nearGoal = nearest(usableStops, goal, 2);
			Map<Transport, Router.Route> heads = new HashMap<>();
			for (Transport far : nearGoal)
			{
				int farCap = best == null ? Integer.MAX_VALUE : 2 * best.ticks + 1;
				int bound = ticksFor(Router.heuristic(start, nearMe.get(0).origin)) + kind.defaultDuration() + ticksFor(Router.heuristic(far.origin, goal));
				if (best != null && bound >= best.ticks)
				{
					continue;
				}
				Router.Route tail = Router.find(map, table, usable, far.origin, goal, NETWORK_EXPANSIONS, System.nanoTime() + budgetNanos, avoidWilderness, farCap);
				if (!tail.reached)
				{
					continue;
				}
				for (Transport stopNear : nearMe)
				{
					if (stopNear == far || Packed.chebyshev(stopNear.origin, far.origin) <= 5)
					{
						continue;
					}
					Transport ride = Transport.ride(stopNear, far);
					int cap = best == null ? Integer.MAX_VALUE : 2 * (best.ticks - ride.duration - ticksFor(tail.cost)) + 1;
					if (cap <= 0)
					{
						continue;
					}
					Router.Route head = heads.get(stopNear);
					if (head == null)
					{
						head = Router.find(map, table, usable, start, stopNear.origin, NETWORK_EXPANSIONS, System.nanoTime() + budgetNanos, avoidWilderness, cap);
						heads.put(stopNear, head);
					}
					if (!head.reached)
					{
						continue;
					}
					Router.Route whole = Router.join(head, ride, tail);
					int ticks = ticksFor(whole.cost);
					if (best == null || ticks < best.ticks)
					{
						best = new Plan(null, whole, start, ticks);
					}
				}
			}
		}
		// Finally the full walk, but only as far as it could still beat the best plan so far. (The
		// straight line is no lower bound here: a well or a cave can jump the route a long way.)
		// A teleport or ride only replaces a walk it beats clearly, so the walk is searched a little
		// beyond the point where it would tie.
		int cap = best == null ? Integer.MAX_VALUE : (int) Math.max(walkUpTo + 1, Math.min(Integer.MAX_VALUE, 2L * ((best.ticks + MARGIN_TICKS) / MARGIN_SHARE) + 1));
		int walkBudget = best == null ? maxExpansions : Math.min(maxExpansions, 400_000);
		Router.Route walk = Router.find(map, table, usable, start, goal, walkBudget, System.nanoTime() + budgetNanos, avoidWilderness, cap);
		if (walk.reached)
		{
			int walkTicks = ticksFor(walk.cost);
			boolean clearlyBetter = best != null && best.ticks + MARGIN_TICKS <= walkTicks && best.ticks <= walkTicks * MARGIN_SHARE;
			if (!clearlyBetter)
			{
				return new Plan(null, walk, start, walkTicks, walk);
			}
			return new Plan(best.teleport, best.route, start, best.ticks, walk);
		}
		return best != null ? best : new Plan(null, walk, start, Integer.MAX_VALUE);
	}
}
