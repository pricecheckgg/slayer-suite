package com.slayersuite.data;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Quest;

/** Every bank and every Slayer master with a map position, from banks.tsv and masters.tsv (built from the wiki). */
@Slf4j
@Singleton
public class TravelPoints
{
	private static final String BANKS = "/com/slayersuite/data/banks.tsv";
	private static final String MASTERS = "/com/slayersuite/data/masters.tsv";

	@Value
	public static class Point
	{
		String name;
		int x;
		int y;
		int plane;
		boolean members;
		/** Plain words from the wiki: a quest name, "Farming 85", a diary, or empty. */
		String requirement;
		/** The id the game's task tables use for this master's list; -1 for a bank or a master not in them. */
		int masterId;
		/** The place a master stands in, for the label. */
		String place;
	}

	/** What a bank asks of you before you can use it, as far as the plugin can check. */
	public static final class Requirement
	{
		public enum Kind
		{
			NONE, QUEST, SKILL, DIARY, UNKNOWN
		}

		public final Kind kind;
		/** Quest constant, skill constant or diary key, by kind. */
		public final String key;
		public final int level;

		Requirement(Kind kind, String key, int level)
		{
			this.kind = kind;
			this.key = key;
			this.level = level;
		}
	}

	private volatile List<Point> banks = Collections.emptyList();
	private volatile List<Point> masters = Collections.emptyList();

	public synchronized void load()
	{
		banks = read(BANKS, false);
		masters = read(MASTERS, true);
		log.debug("Slayer Suite travel points: {} banks, {} masters", banks.size(), masters.size());
	}

	private static List<Point> read(String resource, boolean masterFile)
	{
		List<Point> out = new ArrayList<>();
		try (InputStream in = TravelPoints.class.getResourceAsStream(resource))
		{
			if (in == null)
			{
				throw new IOException("missing resource " + resource);
			}
			BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.isEmpty() || line.startsWith("#"))
				{
					continue;
				}
				String[] f = line.split("\t", -1);
				if (f.length < 4)
				{
					continue;
				}
				try
				{
					int x = Integer.parseInt(f[1].trim());
					int y = Integer.parseInt(f[2].trim());
					int plane = Integer.parseInt(f[3].trim());
					if (masterFile)
					{
						int id = f.length > 4 && !f[4].trim().isEmpty() ? Integer.parseInt(f[4].trim()) : -1;
						out.add(new Point(f[0].trim(), x, y, plane, true, "", id, f.length > 5 ? f[5].trim() : ""));
					}
					else
					{
						boolean members = f.length > 4 && f[4].trim().equalsIgnoreCase("yes");
						out.add(new Point(f[0].trim(), x, y, plane, members, f.length > 5 ? f[5].trim() : "", -1, ""));
					}
				}
				catch (NumberFormatException e)
				{
					log.debug("bad travel point line: {}", line);
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Slayer Suite: could not read {}", resource, e);
		}
		return out;
	}

	public List<Point> banks()
	{
		return banks;
	}

	public List<Point> masters()
	{
		return masters;
	}

	/** The Slayer master with this NPC name, or null. */
	public Point master(String npcName)
	{
		if (npcName == null)
		{
			return null;
		}
		for (Point p : masters)
		{
			if (p.getName().equalsIgnoreCase(npcName))
			{
				return p;
			}
		}
		return null;
	}

	/** The first master whose task list has this id, or null. */
	public Point masterFor(int masterId)
	{
		if (masterId < 0)
		{
			return null;
		}
		for (Point p : masters)
		{
			if (p.getMasterId() == masterId)
			{
				return p;
			}
		}
		return null;
	}

	/** Dungeon levels are stored 6400 tiles north of the ground they lie under; fold them back for distances. */
	public static int surfaceY(int y)
	{
		return y >= 6400 ? y - 6400 : y;
	}

	/** The n points nearest to (x, y) in straight-line tiles, dungeons folded onto the surface. */
	public static List<Point> nearest(List<Point> points, int x, int y, int n)
	{
		List<Point> sorted = new ArrayList<>(points);
		sorted.sort((a, b) -> Integer.compare(distance(a, x, y), distance(b, x, y)));
		return sorted.size() > n ? new ArrayList<>(sorted.subList(0, n)) : sorted;
	}

	public static int distance(Point p, int x, int y)
	{
		return Math.max(Math.abs(p.getX() - x), Math.abs(surfaceY(p.getY()) - surfaceY(y)));
	}

	/** Reads a requirement cell: nothing, a quest the client knows, "Skill N", "<Tier> <Area> Diary", or something the plugin cannot check. */
	public static Requirement requirement(String text)
	{
		String s = text == null ? "" : text.trim();
		if (s.isEmpty() || s.equalsIgnoreCase("none"))
		{
			return new Requirement(Requirement.Kind.NONE, null, 0);
		}
		java.util.regex.Matcher skill = java.util.regex.Pattern.compile("^([A-Za-z]+) (\\d+)$").matcher(s);
		if (skill.matches())
		{
			return new Requirement(Requirement.Kind.SKILL, skill.group(1).toUpperCase(Locale.ROOT), Integer.parseInt(skill.group(2)));
		}
		java.util.regex.Matcher diary = java.util.regex.Pattern.compile("^(Easy|Medium|Hard|Elite) ([A-Za-z' ]+?) Diary$", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
		if (diary.matches())
		{
			String area = diary.group(2).toUpperCase(Locale.ROOT).replace("'", "").replace(' ', '_');
			return new Requirement(Requirement.Kind.DIARY, area + "_" + diary.group(1).toUpperCase(Locale.ROOT), 0);
		}
		String constant = questConstant(s);
		if (constant != null)
		{
			return new Requirement(Requirement.Kind.QUEST, constant, 0);
		}
		return new Requirement(Requirement.Kind.UNKNOWN, s, 0);
	}

	/** The Quest constant a wiki quest name stands for, or null when the client has no such quest. */
	public static String questConstant(String name)
	{
		if (name == null)
		{
			return null;
		}
		String base = name.toUpperCase(Locale.ROOT).replace("'", "").replace("’", "").replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (base.isEmpty())
		{
			return null;
		}
		String[] tries = {base, "THE_" + base, base.startsWith("THE_") ? base.substring(4) : base};
		for (String t : tries)
		{
			try
			{
				Quest.valueOf(t);
				return t;
			}
			catch (IllegalArgumentException ignored)
			{
				// not this spelling
			}
		}
		return null;
	}
}
