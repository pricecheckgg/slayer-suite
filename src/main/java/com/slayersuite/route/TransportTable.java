package com.slayersuite.route;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Quest;
import net.runelite.api.Skill;

/**
 * The transports of the game, read from the Shortest Path project's tables (BSD 2-Clause, see
 * THIRD-PARTY-NOTICES.md) by column name: Origin, Destination, "menuOption menuTarget objectID",
 * Skills ("70 Agility"), Items, Quests, Varbits and VarPlayers ("id=value;id>value"), Duration.
 */
public final class TransportTable
{
	private static final String DIR = "/com/slayersuite/route/transports/";

	private final Map<Integer, List<Transport>> byOrigin;
	private final List<Transport> all;
	private final Map<Transport.Kind, List<Transport>> stops;
	private final Set<Integer> stopTiles;

	private TransportTable(List<Transport> all)
	{
		this.all = Collections.unmodifiableList(all);
		Map<Integer, List<Transport>> map = new HashMap<>();
		Map<Transport.Kind, List<Transport>> byKind = new java.util.EnumMap<>(Transport.Kind.class);
		Set<Integer> tiles = new java.util.HashSet<>();
		for (Transport t : all)
		{
			if (t.kind.network())
			{
				// a stop of a network: rides between stops are planned, not searched tile by tile
				byKind.computeIfAbsent(t.kind, k -> new ArrayList<>()).add(t);
				tiles.add(t.origin);
				continue;
			}
			map.computeIfAbsent(t.origin, k -> new ArrayList<>(2)).add(t);
		}
		this.byOrigin = map;
		this.stops = byKind;
		this.stopTiles = tiles;
	}

	/** Reads every bundled table: objects, shortcuts, and the rides between the stops of each network. */
	public static TransportTable load()
	{
		List<Transport> all = new ArrayList<>(80000);
		all.addAll(parse(resource("transports.tsv"), Transport.Kind.OBJECT));
		all.addAll(parse(resource("agility_shortcuts.tsv"), Transport.Kind.SHORTCUT));
		all.addAll(parse(resource("boats.tsv"), Transport.Kind.BOAT));
		all.addAll(parse(resource("ships.tsv"), Transport.Kind.SHIP));
		all.addAll(parse(resource("charter_ships.tsv"), Transport.Kind.CHARTER));
		all.addAll(parse(resource("teleportation_portals.tsv"), Transport.Kind.PORTAL));
		all.addAll(parse(resource("teleportation_levers.tsv"), Transport.Kind.LEVER));
		all.addAll(parse(resource("spirit_trees.tsv"), Transport.Kind.SPIRIT_TREE));
		all.addAll(parse(resource("fairy_rings.tsv"), Transport.Kind.FAIRY_RING));
		all.addAll(parse(resource("gnome_gliders.tsv"), Transport.Kind.GLIDER));
		all.addAll(parse(resource("magic_mushtrees.tsv"), Transport.Kind.MUSHTREE));
		all.addAll(parse(resource("quetzals.tsv"), Transport.Kind.QUETZAL));
		return new TransportTable(all);
	}

	/** A stop of a network sits here (a ring, a tree, a glider pad), possibly on a solid tile. */
	public boolean isStop(int packed)
	{
		return stopTiles.contains(packed);
	}

	private static InputStream resource(String name)
	{
		InputStream in = TransportTable.class.getResourceAsStream(DIR + name);
		if (in == null)
		{
			throw new IllegalStateException("missing " + DIR + name);
		}
		return in;
	}

	/** A table with these transports added; the old one is untouched, so a running search keeps its view. */
	public TransportTable withAdded(List<Transport> extra)
	{
		List<Transport> merged = new ArrayList<>(all.size() + extra.size());
		merged.addAll(all);
		merged.addAll(extra);
		return new TransportTable(merged);
	}

	/** The stops of a network, each with its own requirements and name. */
	public List<Transport> stops(Transport.Kind kind)
	{
		List<Transport> list = stops.get(kind);
		return list == null ? Collections.emptyList() : list;
	}

	/** Every network kind that has stops. */
	public Set<Transport.Kind> networks()
	{
		return stops.keySet();
	}

	/** True when a transport through this object already leaves the tile. */
	public boolean knows(int origin, int objectId)
	{
		for (Transport t : from(origin))
		{
			if (t.objectId == objectId)
			{
				return true;
			}
		}
		return false;
	}

	public List<Transport> from(int packed)
	{
		List<Transport> list = byOrigin.get(packed);
		return list == null ? Collections.emptyList() : list;
	}

	public List<Transport> all()
	{
		return all;
	}

	public int size()
	{
		return all.size();
	}

	static List<Transport> parse(InputStream source, Transport.Kind kind)
	{
		boolean network = kind.network();
		List<Transport> out = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8)))
		{
			String header = in.readLine();
			if (header == null)
			{
				return out;
			}
			String[] names = header.replaceFirst("^#\\s*", "").split("\t");
			Map<String, Integer> col = new HashMap<>();
			for (int i = 0; i < names.length; i++)
			{
				col.put(names[i].trim(), i);
			}
			for (String key : new String[]{"Origin", "Destination", "menuOption menuTarget objectID"})
			{
				if (!col.containsKey(key))
				{
					throw new IllegalStateException("transport table without a " + key + " column");
				}
			}
			String line;
			String heading = "";
			while ((line = in.readLine()) != null)
			{
				if (line.startsWith("#"))
				{
					heading = line.substring(1).trim();
					continue;
				}
				if (line.isEmpty())
				{
					continue;
				}
				String[] f = line.split("\t", -1);
				int origin = point(cell(f, col, "Origin"));
				int destination = point(cell(f, col, "Destination"));
				if (origin == Integer.MIN_VALUE || (destination == Integer.MIN_VALUE && !network))
				{
					continue;
				}
				if (network)
				{
					// a stop: its destination is filled in when the rides are made
					destination = origin;
				}
				String option = cell(f, col, "menuOption menuTarget objectID").trim();
				String[] tok = option.isEmpty() ? new String[0] : option.split(" +");
				int objectId = -1;
				int nameEnd = tok.length;
				if (tok.length > 1 && tok[tok.length - 1].matches("\\d+"))
				{
					objectId = Integer.parseInt(tok[tok.length - 1]);
					nameEnd--;
				}
				String action = tok.length == 0 ? "Use" : tok[0];
				String name = nameEnd > 1 ? String.join(" ", java.util.Arrays.copyOfRange(tok, 1, nameEnd)) : "";
				String items = cell(f, col, "Items").trim();
				int duration = kind.defaultDuration();
				try
				{
					String d = cell(f, col, "Duration").trim();
					duration = d.isEmpty() ? kind.defaultDuration() : Math.max(1, Integer.parseInt(d));
				}
				catch (NumberFormatException ignored)
				{
					// a blank or odd duration counts as the kind's usual one
				}
				String display = cell(f, col, "Display info").trim();
				if (display.isEmpty() && network)
				{
					display = heading;
				}
				out.add(new Transport(origin, destination, action, name, objectId, duration, display,
					skills(cell(f, col, "Skills")), quests(cell(f, col, "Quests")), vars(cell(f, col, "Varbits")), vars(cell(f, col, "VarPlayers")),
					needsItems(items), kind));
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
		return out;
	}

	private static String cell(String[] f, Map<String, Integer> col, String name)
	{
		Integer i = col.get(name);
		return i == null || i >= f.length ? "" : f[i];
	}

	/** "x y plane" to a packed tile; MIN_VALUE when blank or malformed. */
	private static int point(String s)
	{
		String[] p = s.trim().split(" +");
		if (p.length != 3)
		{
			return Integer.MIN_VALUE;
		}
		try
		{
			return Packed.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
		}
		catch (NumberFormatException e)
		{
			return Integer.MIN_VALUE;
		}
	}

	private static Map<Skill, Integer> skills(String s)
	{
		if (s == null || s.trim().isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<Skill, Integer> out = new EnumMap<>(Skill.class);
		for (String part : s.split(";"))
		{
			String[] p = part.trim().split(" +");
			if (p.length != 2)
			{
				continue;
			}
			int level;
			try
			{
				level = "Max".equals(p[0]) ? 99 : Integer.parseInt(p[0]);
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			for (Skill sk : Skill.values())
			{
				if (sk.getName().equalsIgnoreCase(p[1]))
				{
					out.put(sk, Math.max(level, out.getOrDefault(sk, 0)));
				}
			}
			// total level, combat level and quest points are not checked and count as met
		}
		return out.isEmpty() ? Collections.emptyMap() : out;
	}

	private static Set<Quest> quests(String s)
	{
		if (s == null || s.trim().isEmpty())
		{
			return Collections.emptySet();
		}
		Set<Quest> out = EnumSet.noneOf(Quest.class);
		for (String part : s.split(";"))
		{
			String want = part.trim();
			for (Quest q : Quest.values())
			{
				if (q.getName().equals(want))
				{
					out.add(q);
					break;
				}
			}
		}
		return out.isEmpty() ? Collections.emptySet() : out;
	}

	private static List<Transport.VarCheck> vars(String s)
	{
		if (s == null || s.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		List<Transport.VarCheck> out = new ArrayList<>(2);
		for (String part : s.split(";"))
		{
			String r = part.trim();
			int at = -1;
			for (int i = 0; i < r.length(); i++)
			{
				if ("=><&@".indexOf(r.charAt(i)) >= 0)
				{
					at = i;
					break;
				}
			}
			if (at <= 0 || at >= r.length() - 1)
			{
				continue;
			}
			try
			{
				out.add(new Transport.VarCheck(Integer.parseInt(r.substring(0, at)), r.charAt(at), Integer.parseInt(r.substring(at + 1))));
			}
			catch (NumberFormatException ignored)
			{
				// a malformed condition is skipped rather than blocking the row
			}
		}
		return out.isEmpty() ? Collections.emptyList() : out;
	}

	/**
	 * True when the row asks for an item the plugin cannot see. A quantity of zero asks for
	 * nothing, and coins are assumed carried.
	 */
	private static boolean needsItems(String items)
	{
		if (items.isEmpty())
		{
			return false;
		}
		for (String and : items.replace(" ", "").split("&"))
		{
			boolean satisfied = false;
			for (String or : and.split("\\|"))
			{
				String[] p = or.split("=");
				if (p.length == 2 && ("0".equals(p[1]) || "COINS".equals(p[0])))
				{
					satisfied = true;
				}
			}
			if (!satisfied)
			{
				return true;
			}
		}
		return false;
	}
}
