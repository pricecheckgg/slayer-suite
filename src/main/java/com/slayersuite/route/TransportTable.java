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

	private TransportTable(List<Transport> all)
	{
		this.all = Collections.unmodifiableList(all);
		Map<Integer, List<Transport>> map = new HashMap<>();
		for (Transport t : all)
		{
			map.computeIfAbsent(t.origin, k -> new ArrayList<>(2)).add(t);
		}
		this.byOrigin = map;
	}

	/** Reads the bundled general transports and Agility shortcuts. */
	public static TransportTable load()
	{
		List<Transport> all = new ArrayList<>(8000);
		all.addAll(parse(resource("transports.tsv"), false));
		all.addAll(parse(resource("agility_shortcuts.tsv"), true));
		return new TransportTable(all);
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

	static List<Transport> parse(InputStream source, boolean shortcut)
	{
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
			while ((line = in.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				String[] f = line.split("\t", -1);
				int origin = point(cell(f, col, "Origin"));
				int destination = point(cell(f, col, "Destination"));
				if (origin == Integer.MIN_VALUE || destination == Integer.MIN_VALUE)
				{
					continue;
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
				int duration = 1;
				try
				{
					String d = cell(f, col, "Duration").trim();
					duration = d.isEmpty() ? 1 : Math.max(1, Integer.parseInt(d));
				}
				catch (NumberFormatException ignored)
				{
					// a blank or odd duration counts as one tick
				}
				out.add(new Transport(origin, destination, action, name, objectId, duration, cell(f, col, "Display info").trim(),
					skills(cell(f, col, "Skills")), quests(cell(f, col, "Quests")), vars(cell(f, col, "Varbits")), vars(cell(f, col, "VarPlayers")),
					needsItems(items), shortcut));
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
