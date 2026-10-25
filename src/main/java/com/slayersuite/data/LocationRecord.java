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
	private List<String> notes = new ArrayList<>();

	public boolean hasCoordinates()
	{
		return x != null && y != null;
	}

	public boolean coversRegion(int region)
	{
		return regions != null && regions.contains(region);
	}
}
