package com.slayersuite.advisor;

import java.util.HashMap;
import java.util.Map;

/**
 * The object you click to get into a dungeon whose way in is not obviously named, keyed by the
 * dataset's location name: {object name, option}. A null option means whatever the object offers.
 * Anything not listed here is found by its name (cave, trapdoor, ladder, stairs, ...).
 */
final class Entrances
{
	private static final Map<String, String[]> KNOWN = new HashMap<>();

	static
	{
		put("Catacombs of Kourend", "Statue", "Investigate");
		put("Smoke Dungeon", "Smokey well", "Climb-down");
		put("Fremennik Slayer Dungeon", "Cave entrance", "Enter");
		put("Iorwerth Dungeon", "Cave", "Enter");
		put("Stronghold Slayer Cave", "Cave entrance", "Enter");
		put("Stronghold Slayer Dungeon", "Cave entrance", "Enter");
		put("Taverley Dungeon", "Ladder", "Climb-down");
		put("Brimhaven Dungeon", "Dungeon entrance", null);
		put("Asgarnian Ice Dungeon", "Trapdoor", "Climb-down");
		put("Lumbridge Swamp Caves", "Dark hole", "Climb-down");
		put("Edgeville Dungeon", "Trapdoor", "Climb-down");
		put("Varrock Sewers", "Manhole", null);
		put("Draynor Sewers", "Trapdoor", "Climb-down");
		put("Chasm of Fire", "Chasm", null);
		put("Karuulm Slayer Dungeon", "Elevator", null);
		put("Kalphite Lair", "Tunnel", null);
		put("Smoke Devil Dungeon", "Crevice", null);
		put("Kraken Cove", "Crevice", null);
		put("Mos Le'Harmless Cave", "Cave entrance", "Enter");
		put("Wilderness Slayer Cave", "Cave entrance", null);
		put("Ancient Cavern", "Whirlpool", null);
	}

	private Entrances()
	{
	}

	private static void put(String location, String object, String option)
	{
		KNOWN.put(location.toLowerCase(java.util.Locale.ROOT), new String[]{object, option});
	}

	/** {object name, option or null} for the location, or null when its way in is found by name. */
	static String[] forLocation(String location)
	{
		return location == null ? null : KNOWN.get(location.toLowerCase(java.util.Locale.ROOT));
	}
}
