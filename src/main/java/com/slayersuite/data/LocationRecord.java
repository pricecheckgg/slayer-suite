package com.slayersuite.data;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class LocationRecord
{
	private String name;
	private String page;
	private Integer floor;
	private Integer plane;
	private Integer mapId;
	private List<Integer> regions = new ArrayList<>();
	/** Median of the wiki's spawn pins; null when the wiki row has no map. */
	private Integer x;
	private Integer y;
	/** Individual spawn pins from the wiki map, [x, y] each; the path aims for the nearest reachable one. */
	private List<List<Integer>> pins = new ArrayList<>();
	private int spawnCount;
	private String amount;
	/** true, false, or null when the wiki does not say. */
	private Boolean multicombat;
	private Boolean cannon;
	private Boolean safespot;
	/** A boss arena that happens to count for the task (set by hand in overrides): never ranked or pinned. */
	private Boolean boss;
	private List<String> notes = new ArrayList<>();

	public boolean hasCoordinates()
	{
		return x != null && y != null;
	}

	/** Tiles a Dwarf multicannon reaches in each direction. */
	public static final int CANNON_REACH = 7;

	/**
	 * The pin, or the spawns' middle, that has the most spawn pins within a cannon's reach; null
	 * with fewer than three pins. Ties go to the tile nearest the middle of the spawns.
	 */
	public int[] cannonTile()
	{
		List<int[]> valid = new ArrayList<>();
		for (List<Integer> pin : pins)
		{
			if (pin != null && pin.size() >= 2)
			{
				valid.add(new int[]{pin.get(0), pin.get(1)});
			}
		}
		if (valid.size() < 3)
		{
			return null;
		}
		long sx = 0;
		long sy = 0;
		for (int[] p : valid)
		{
			sx += p[0];
			sy += p[1];
		}
		int[] middle = {(int) Math.round(sx / (double) valid.size()), (int) Math.round(sy / (double) valid.size())};
		List<int[]> candidates = new ArrayList<>(valid);
		candidates.add(middle);
		int[] best = null;
		int bestCovered = -1;
		double bestMid = Double.MAX_VALUE;
		for (int[] c : candidates)
		{
			int covered = 0;
			for (int[] p : valid)
			{
				if (Math.max(Math.abs(p[0] - c[0]), Math.abs(p[1] - c[1])) <= CANNON_REACH)
				{
					covered++;
				}
			}
			double mid = Math.hypot(c[0] - middle[0], c[1] - middle[1]);
			if (covered > bestCovered || (covered == bestCovered && mid < bestMid))
			{
				best = c;
				bestCovered = covered;
				bestMid = mid;
			}
		}
		return best;
	}

	public boolean coversRegion(int region)
	{
		return regions != null && regions.contains(region);
	}
}
