package com.slayersuite.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds a way from one tile to another over the world map, stepping tile to tile and using any
 * transport you are allowed. A* with the straight-line tile distance as its guide. When the
 * target cannot be reached within the budget, the route runs to the tile that got nearest.
 */
public final class Router
{
	/** A transport used on the route, at the given index of its tiles. */
	public static final class Step
	{
		public final int index;
		public final Transport transport;

		Step(int index, Transport transport)
		{
			this.index = index;
			this.transport = transport;
		}
	}

	public static final class Route
	{
		/** Packed tiles from the start to the end, inclusive. */
		public final List<Integer> tiles;
		public final List<Step> steps;
		public final boolean reached;
		public final int cost;
		private final IntIntMap index;

		Route(List<Integer> tiles, List<Step> steps, boolean reached, int cost)
		{
			this.tiles = Collections.unmodifiableList(tiles);
			this.steps = Collections.unmodifiableList(steps);
			this.reached = reached;
			this.cost = cost;
			index = new IntIntMap(tiles.size());
			for (int i = 0; i < tiles.size(); i++)
			{
				index.put(tiles.get(i), i);
			}
		}

		/** Where a tile sits on the route, or -1. */
		public int indexOf(int packed)
		{
			return index.get(packed, -1);
		}

		public int end()
		{
			return tiles.get(tiles.size() - 1);
		}
	}

	private Router()
	{
	}

	/**
	 * @param usable the transports whose requirements you meet; others are ignored
	 * @param maxExpansions tiles to look at before giving up on reaching the target
	 * @param deadlineNanos System.nanoTime() after which the search stops and returns its best
	 */
	public static Route find(WorldMap map, TransportTable transports, Set<Transport> usable, int start, int target, int maxExpansions, long deadlineNanos)
	{
		return find(map, transports, usable, start, target, maxExpansions, deadlineNanos, false);
	}

	/** As above; with avoidWilderness the route never sets foot in the Wilderness, on the surface or below it. */
	public static Route find(WorldMap map, TransportTable transports, Set<Transport> usable, int start, int target, int maxExpansions, long deadlineNanos, boolean avoidWilderness)
	{
		return find(map, transports, usable, start, target, maxExpansions, deadlineNanos, avoidWilderness, Integer.MAX_VALUE);
	}

	/** As above; a route costing more than maxCost is not worth finding, so the search stops growing past it. */
	public static Route find(WorldMap map, TransportTable transports, Set<Transport> usable, int start, int target, int maxExpansions, long deadlineNanos, boolean avoidWilderness, int maxCost)
	{
		IntIntMap g = new IntIntMap(1 << 16);
		IntIntMap parent = new IntIntMap(1 << 16);
		Map<Integer, Transport> via = new HashMap<>();
		IntHeap open = new IntHeap(1 << 14);
		g.put(start, 0);
		open.push(heuristic(start, target), start);
		int best = start;
		int bestH = heuristic(start, target);
		boolean reached = false;
		int expanded = 0;
		while (!open.isEmpty())
		{
			int f = open.peekKey();
			int node = open.pop();
			int gn = g.get(node, Integer.MAX_VALUE);
			if (f > gn + heuristic(node, target))
			{
				continue;
			}
			if (node == target)
			{
				reached = true;
				best = node;
				break;
			}
			if (++expanded > maxExpansions || (expanded & 1023) == 0 && (System.nanoTime() > deadlineNanos || Thread.currentThread().isInterrupted()))
			{
				break;
			}
			if (gn >= maxCost)
			{
				continue;
			}
			int h = heuristic(node, target);
			if (h < bestH)
			{
				bestH = h;
				best = node;
			}
			for (int nb : map.neighbours(node))
			{
				if (avoidWilderness && inWilderness(nb))
				{
					continue;
				}
				int ng = gn + 1;
				if (ng < g.get(nb, Integer.MAX_VALUE))
				{
					g.put(nb, ng);
					parent.put(nb, node);
					via.remove(nb);
					open.push(ng + heuristic(nb, target), nb);
				}
			}
			// a stop that is solid in the map (a fairy ring, a tree) is stepped onto from beside it
			int nx = Packed.x(node);
			int ny = Packed.y(node);
			int nz = Packed.plane(node);
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int adj = Packed.of(nx + dx, ny + dy, nz);
					if (!map.isBlocked(adj) || (!transports.isStop(adj) && transports.from(adj).isEmpty()) || (avoidWilderness && inWilderness(adj)))
					{
						continue;
					}
					int ng = gn + 1;
					if (ng < g.get(adj, Integer.MAX_VALUE))
					{
						g.put(adj, ng);
						parent.put(adj, node);
						via.remove(adj);
						open.push(ng + heuristic(adj, target), adj);
					}
				}
			}
			for (Transport t : transports.from(node))
			{
				if (!usable.contains(t) || (avoidWilderness && inWilderness(t.destination)))
				{
					continue;
				}
				int ng = gn + t.duration;
				if (ng < g.get(t.destination, Integer.MAX_VALUE))
				{
					g.put(t.destination, ng);
					parent.put(t.destination, node);
					via.put(t.destination, t);
					open.push(ng + heuristic(t.destination, target), t.destination);
				}
			}
		}
		List<Integer> tiles = new ArrayList<>();
		List<Step> steps = new ArrayList<>();
		for (int at = best; ; at = parent.get(at, Integer.MIN_VALUE))
		{
			tiles.add(at);
			if (at == start || parent.get(at, Integer.MIN_VALUE) == Integer.MIN_VALUE)
			{
				break;
			}
		}
		Collections.reverse(tiles);
		for (int i = 1; i < tiles.size(); i++)
		{
			Transport t = via.get(tiles.get(i));
			if (t != null && t.origin == tiles.get(i - 1))
			{
				steps.add(new Step(i - 1, t));
			}
		}
		return new Route(tiles, steps, reached, g.get(best, 0));
	}

	/** One route: the head, then a ride at its last tile, then the tail. */
	static Route join(Route head, Transport ride, Route tail)
	{
		List<Integer> tiles = new ArrayList<>(head.tiles.size() + tail.tiles.size());
		tiles.addAll(head.tiles);
		tiles.addAll(tail.tiles);
		List<Step> steps = new ArrayList<>(head.steps);
		steps.add(new Step(head.tiles.size() - 1, ride));
		for (Step s : tail.steps)
		{
			steps.add(new Step(s.index + head.tiles.size(), s.transport));
		}
		return new Route(tiles, steps, head.reached && tail.reached, head.cost + ride.duration + tail.cost);
	}

	/**
	 * Tiles between the two points as the crow flies, with dungeons folded onto the ground above
	 * them (they are stored 6400 tiles north), so a search for a spot underground heads for the
	 * ground over it, where its entrance usually is. Floors are ignored; a staircase bridges them.
	 */
	static int heuristic(int a, int b)
	{
		int ay = Packed.y(a);
		int by = Packed.y(b);
		if (ay >= 6400)
		{
			ay -= 6400;
		}
		if (by >= 6400)
		{
			by -= 6400;
		}
		return Math.max(Math.abs(Packed.x(a) - Packed.x(b)), Math.abs(ay - by));
	}

	/**
	 * The tile itself when it can be stood on, else the same spot on another floor that can: the
	 * data's spawn pins carry no floor, and some basements sit on floor 3 in the map.
	 */
	public static int walkableNear(WorldMap map, int packed)
	{
		if (!map.isBlocked(packed))
		{
			return packed;
		}
		int x = Packed.x(packed);
		int y = Packed.y(packed);
		for (int plane = 0; plane < 4; plane++)
		{
			int p = Packed.of(x, y, plane);
			if (!map.isBlocked(p))
			{
				return p;
			}
		}
		return packed;
	}

	/** The Wilderness on the surface, and the dungeons under it, whose caves run further east than the ditch line. */
	static boolean inWilderness(int p)
	{
		int x = Packed.x(p);
		int y = Packed.y(p);
		if (y >= 9920 && y <= 10367)
		{
			return x >= 2944 && x <= 3455;
		}
		return x >= 2944 && x <= 3391 && y >= 3525 && y <= 3967;
	}

	/** A binary heap of (key, value) ints, smallest key first. */
	static final class IntHeap
	{
		private int[] keys;
		private int[] vals;
		private int size;

		IntHeap(int capacity)
		{
			keys = new int[capacity];
			vals = new int[capacity];
		}

		boolean isEmpty()
		{
			return size == 0;
		}

		int peekKey()
		{
			return keys[0];
		}

		void push(int key, int val)
		{
			if (size == keys.length)
			{
				keys = java.util.Arrays.copyOf(keys, size * 2);
				vals = java.util.Arrays.copyOf(vals, size * 2);
			}
			int i = size++;
			while (i > 0)
			{
				int p = (i - 1) >>> 1;
				if (keys[p] <= key)
				{
					break;
				}
				keys[i] = keys[p];
				vals[i] = vals[p];
				i = p;
			}
			keys[i] = key;
			vals[i] = val;
		}

		int pop()
		{
			int out = vals[0];
			int key = keys[--size];
			int val = vals[size];
			int i = 0;
			while (true)
			{
				int l = 2 * i + 1;
				if (l >= size)
				{
					break;
				}
				int r = l + 1;
				int c = r < size && keys[r] < keys[l] ? r : l;
				if (keys[c] >= key)
				{
					break;
				}
				keys[i] = keys[c];
				vals[i] = vals[c];
				i = c;
			}
			if (size > 0)
			{
				keys[i] = key;
				vals[i] = val;
			}
			return out;
		}
	}
}
