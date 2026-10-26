package com.slayersuite.advisor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.CollisionDataFlag;

/**
 * Breadth-first path over the loaded scene's collision flags. Walls, objects and
 * water are respected. Tiles marked as doors may be crossed through their wall
 * (the door opens when you click it); stairs and shortcuts are not known. When
 * the target is not reachable it paths to the reachable tile nearest to it, so
 * the line always points the right way.
 */
public final class ScenePath
{
	private static final int BLOCKED = CollisionDataFlag.BLOCK_MOVEMENT_FULL | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR
		| CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;

	private ScenePath()
	{
	}

	/** Scene coordinates from start (inclusive) to the end tile. Empty when the start is off the grid. */
	public static List<int[]> find(int[][] flags, int sx, int sy, int tx, int ty, int maxTiles)
	{
		return find(flags, null, sx, sy, tx, ty, maxTiles);
	}

	/** As above; doors[x][y] true lets a step cross the wall on that tile. */
	public static List<int[]> find(int[][] flags, boolean[][] doors, int sx, int sy, int tx, int ty, int maxTiles)
	{
		if (flags == null || flags.length == 0 || !inside(flags, sx, sy))
		{
			return Collections.emptyList();
		}
		int w = flags.length;
		int h = flags[0].length;
		int[] prev = new int[w * h];
		java.util.Arrays.fill(prev, -1);
		boolean[] seen = new boolean[w * h];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int start = sx * h + sy;
		seen[start] = true;
		queue.add(start);
		int best = start;
		double bestDist = dist(sx, sy, tx, ty);
		int expanded = 0;
		while (!queue.isEmpty() && expanded < w * h)
		{
			int cur = queue.poll();
			expanded++;
			int cx = cur / h;
			int cy = cur % h;
			double d = dist(cx, cy, tx, ty);
			if (d < bestDist)
			{
				bestDist = d;
				best = cur;
			}
			if (cx == tx && cy == ty)
			{
				break;
			}
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int nx = cx + dx;
					int ny = cy + dy;
					if (!inside(flags, nx, ny) || seen[nx * h + ny] || !canStep(flags, doors, cx, cy, dx, dy))
					{
						continue;
					}
					seen[nx * h + ny] = true;
					prev[nx * h + ny] = cur;
					queue.add(nx * h + ny);
				}
			}
		}
		List<int[]> path = new ArrayList<>();
		for (int at = best; at != -1; at = prev[at])
		{
			path.add(new int[]{at / h, at % h});
			if (at == start)
			{
				break;
			}
		}
		Collections.reverse(path);
		if (path.size() > maxTiles)
		{
			return new ArrayList<>(path.subList(0, maxTiles));
		}
		return path;
	}

	/** BFS distance from the start to every tile; -1 where unreachable. */
	public static int[][] distances(int[][] flags, int sx, int sy)
	{
		return distances(flags, null, sx, sy);
	}

	public static int[][] distances(int[][] flags, boolean[][] doors, int sx, int sy)
	{
		int w = flags.length;
		int h = flags[0].length;
		int[][] dist = new int[w][h];
		for (int[] row : dist)
		{
			java.util.Arrays.fill(row, -1);
		}
		if (!inside(flags, sx, sy))
		{
			return dist;
		}
		ArrayDeque<int[]> queue = new ArrayDeque<>();
		dist[sx][sy] = 0;
		queue.add(new int[]{sx, sy});
		while (!queue.isEmpty())
		{
			int[] c = queue.poll();
			for (int dx = -1; dx <= 1; dx++)
			{
				for (int dy = -1; dy <= 1; dy++)
				{
					if (dx == 0 && dy == 0)
					{
						continue;
					}
					int nx = c[0] + dx;
					int ny = c[1] + dy;
					if (inside(flags, nx, ny) && dist[nx][ny] < 0 && canStep(flags, doors, c[0], c[1], dx, dy))
					{
						dist[nx][ny] = dist[c[0]][c[1]] + 1;
						queue.add(new int[]{nx, ny});
					}
				}
			}
		}
		return dist;
	}

	static boolean inside(int[][] flags, int x, int y)
	{
		return x >= 0 && y >= 0 && x < flags.length && y < flags[0].length;
	}

	static boolean blocked(int[][] flags, int x, int y)
	{
		return (flags[x][y] & BLOCKED) != 0;
	}

	static boolean canStep(int[][] flags, int x, int y, int dx, int dy)
	{
		return canStep(flags, null, x, y, dx, dy);
	}

	/**
	 * One step from (x,y) by (dx,dy), with OSRS's rule that a diagonal needs both cardinals free.
	 * A cardinal step onto or off a door tile ignores the wall between the two tiles.
	 */
	static boolean canStep(int[][] flags, boolean[][] doors, int x, int y, int dx, int dy)
	{
		int nx = x + dx;
		int ny = y + dy;
		if (!inside(flags, nx, ny) || blocked(flags, nx, ny))
		{
			return false;
		}
		if (dx != 0 && dy != 0)
		{
			return canStep(flags, doors, x, y, dx, 0) && canStep(flags, doors, x, y, 0, dy)
				&& canStep(flags, doors, x + dx, y, 0, dy) && canStep(flags, doors, x, y + dy, dx, 0);
		}
		if (doors != null && (doors[x][y] || doors[nx][ny]))
		{
			return true;
		}
		int from = flags[x][y];
		int to = flags[nx][ny];
		if (dx == 1)
		{
			return (from & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0 && (to & CollisionDataFlag.BLOCK_MOVEMENT_WEST) == 0;
		}
		if (dx == -1)
		{
			return (from & CollisionDataFlag.BLOCK_MOVEMENT_WEST) == 0 && (to & CollisionDataFlag.BLOCK_MOVEMENT_EAST) == 0;
		}
		if (dy == 1)
		{
			return (from & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0 && (to & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0;
		}
		return (from & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) == 0 && (to & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) == 0;
	}

	private static double dist(int x, int y, int tx, int ty)
	{
		return Math.hypot(tx - x, ty - y);
	}
}
