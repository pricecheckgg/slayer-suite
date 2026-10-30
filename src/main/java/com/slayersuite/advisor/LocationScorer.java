package com.slayersuite.advisor;

import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.TaskRecord;
import java.util.ArrayList;
import java.util.List;

/**
 * Ranks a task's locations for one account. Pure: everything it reads comes
 * through AccountView and the data files, so it can be unit-tested.
 */
public class LocationScorer
{
	public static class Options
	{
		public boolean avoidWilderness = true;
		public boolean preferCannon = true;
		public boolean preferMulti = true;
		/** Spot name -> {kills/h, gp/h, xp/h} from your own history on this task. */
		public java.util.Map<String, double[]> ownRates = java.util.Collections.emptyMap();
		/** Spot name -> ticks from where you stand, from the router; absent or negative means not known yet. */
		public java.util.Map<String, Integer> travelTicks = java.util.Collections.emptyMap();
		/** Where you stand (y folded onto the surface) for the straight-line guess; -1 when not logged in. */
		public int playerX = -1;
		public int playerY = -1;
		/** Kills left and your rate on this task, so travel is weighed against how long the task runs. */
		public int remainingKills;
		public double killsPerHour;
	}

	private final TravelData travel;

	public LocationScorer(TravelData travel)
	{
		this.travel = travel;
	}

	public List<ScoredLocation> rank(TaskRecord rec, AccountView account, Options opt)
	{
		List<ScoredLocation> out = new ArrayList<>();
		if (rec == null)
		{
			return out;
		}
		boolean immune = rec.cannonImmune();
		for (LocationRecord loc : rec.getLocations())
		{
			if (Boolean.TRUE.equals(loc.getBoss()))
			{
				continue;
			}
			out.add(score(loc, account, opt, immune));
		}
		out.sort((a, b) -> Integer.compare(b.getScore(), a.getScore()));
		return out;
	}

	public ScoredLocation score(LocationRecord loc, AccountView account, Options opt)
	{
		return score(loc, account, opt, false);
	}

	public ScoredLocation score(LocationRecord loc, AccountView account, Options opt, boolean monstersCannonImmune)
	{
		List<String> reasons = new ArrayList<>();
		int score = 0;
		boolean locked = false;
		TravelData.Entry entry = travel.find(loc.getName());
		boolean wild = isWilderness(loc) || (entry != null && entry.isWilderness());

		if (entry != null)
		{
			for (TravelData.Requirement r : entry.getRequires())
			{
				if (r.getQuest() != null)
				{
					boolean ok = r.isStarted() ? account.questStarted(r.getQuest()) : account.questFinished(r.getQuest());
					if (!ok)
					{
						locked = true;
						reasons.add("needs " + pretty(r.getQuest()) + (r.isStarted() ? " started" : ""));
					}
				}
				if (r.getDiary() != null && !account.diaryComplete(r.getDiary()))
				{
					locked = true;
					reasons.add("needs " + pretty(r.getDiary()) + " diary");
				}
				if (r.getSkill() != null && r.getLevel() > 0)
				{
					int have = account.skillLevel(r.getSkill());
					if (have >= 0 && have < r.getLevel())
					{
						locked = true;
						reasons.add("needs " + r.getLevel() + " " + pretty(r.getSkill()) + " (you have " + have + ")");
					}
				}
			}
		}

		if (Boolean.TRUE.equals(loc.getCannon()))
		{
			if (monstersCannonImmune)
			{
				reasons.add("cannon allowed but the monsters are immune");
			}
			else if (opt.preferCannon && account.ownsCannon())
			{
				if (account.questFinished("DWARF_CANNON"))
				{
					score += 30;
					reasons.add("cannon");
				}
				else
				{
					score += 5;
					reasons.add("cannon (finish Dwarf Cannon first)");
				}
			}
			else
			{
				score += 5;
			}
		}
		if (Boolean.TRUE.equals(loc.getMulticombat()))
		{
			// multi only pays with monsters to share it: a one-spawn multi area is no better than single
			boolean worthIt = loc.getSpawnCount() == 0 || loc.getSpawnCount() >= 3;
			score += worthIt ? (opt.preferMulti ? 20 : 5) : 2;
			reasons.add("multi");
		}
		if (Boolean.TRUE.equals(loc.getSafespot()))
		{
			score += 8;
			reasons.add("safespot");
		}
		score += Math.min(loc.getSpawnCount(), 15);
		String own = null;
		double[] mine = opt.ownRates.get(loc.getName());
		if (mine != null)
		{
			own = Math.round(mine[0]) + " kills/h, " + com.slayersuite.trip.TripMath.formatK(Math.round(mine[1])) + " gp/h for you here";
			// the spot where you have done best, by xp/h then gp/h, gets the edge once you have played two or more
			boolean best = opt.ownRates.size() >= 2;
			for (double[] other : opt.ownRates.values())
			{
				if (other != mine && (other[2] > mine[2] || (other[2] == mine[2] && other[1] > mine[1])))
				{
					best = false;
				}
			}
			if (best)
			{
				score += 15;
				reasons.add("your best spot");
			}
		}

		String travelLabel = null;
		int bestHops = 99;
		if (entry != null)
		{
			for (TravelData.Teleport t : entry.getTeleports())
			{
				int hops = t.getHops();
				boolean have;
				if (t.getPoh() != null)
				{
					have = t.getItem() != null && t.getPoh().equalsIgnoreCase("Jewellery Box")
						? account.jewelleryBoxCovers(t.getItem()) : account.hasHouseFacility(t.getPoh());
					if (have && !account.canTeleportHome())
					{
						hops += 1;
					}
				}
				else if (t.getItem() != null)
				{
					have = account.ownsItem(t.getItem());
				}
				else if (t.getFairy() != null)
				{
					have = account.fairyRingsUsable();
				}
				else if (t.getSpell() != null)
				{
					have = (t.getQuest() == null || account.questFinished(t.getQuest()))
						&& (t.getLevel() <= 0 || account.skillLevel("MAGIC") < 0 || account.skillLevel("MAGIC") >= t.getLevel());
					int book = bookIndex(t.getBook());
					if (have && book >= 0 && account.spellbook() >= 0 && account.spellbook() != book)
					{
						hops += 1; // needs a spellbook swap first
					}
				}
				else
				{
					have = false;
				}
				if (have && hops < bestHops)
				{
					bestHops = hops;
					travelLabel = t.label();
				}
			}
		}
		if (travelLabel != null)
		{
			score += bestHops <= 1 ? 25 : bestHops == 2 ? 15 : 5;
			reasons.add(travelLabel + (bestHops <= 1 ? "" : bestHops == 2 ? " + short run" : " + long run"));
		}
		else if (entry != null && !entry.getTeleports().isEmpty())
		{
			reasons.add("no teleport you own");
		}

		// Getting there costs time the task does not have: a second of travel is worth 100/T points,
		// so a two-minute trip to a cannon spot loses on a forty-kill task and wins on a two-hundred-kill one.
		int travelSeconds = travelSeconds(loc, opt, travelLabel, bestHops);
		if (travelSeconds >= 0)
		{
			double taskSeconds = opt.remainingKills > 0 && opt.killsPerHour > 0 ? opt.remainingKills * 3600.0 / opt.killsPerHour : 600;
			taskSeconds = Math.max(120, Math.min(3600, taskSeconds));
			score -= (int) Math.round(travelSeconds * 100.0 / taskSeconds);
			reasons.add(travelWords(travelSeconds) + " from here");
		}

		if (wild)
		{
			reasons.add("Wilderness");
			if (opt.avoidWilderness)
			{
				score -= 40;
			}
		}
		if (locked)
		{
			score -= 100;
		}
		return new ScoredLocation(loc, score, locked, wild, travelLabel, reasons, own, travelSeconds);
	}

	/** Seconds to the spot from where you stand: the router's plan when it has answered, else a straight-line guess against the best teleport you own. */
	static int travelSeconds(LocationRecord loc, Options opt, String travelLabel, int bestHops)
	{
		Integer ticks = loc.getName() == null || opt.travelTicks == null ? null : opt.travelTicks.get(loc.getName());
		if (ticks != null && ticks >= 0)
		{
			return (int) Math.round(ticks * 0.6);
		}
		if (opt.playerX < 0 || opt.playerY < 0)
		{
			return -1;
		}
		int d = straightLine(loc, opt.playerX, opt.playerY);
		if (d < 0)
		{
			return -1;
		}
		// running covers about 3.3 tiles a second and real routes run about 1.4x the straight line
		int walk = (int) Math.round(d / 2.4);
		int teleport = travelLabel == null ? Integer.MAX_VALUE : bestHops <= 1 ? 15 : bestHops == 2 ? 45 : 90;
		return Math.min(walk, teleport);
	}

	/** Tiles from (px, py) to the spot's nearest pin or its centre, dungeon levels folded onto the surface. */
	private static int straightLine(LocationRecord loc, int px, int py)
	{
		int best = -1;
		if (loc.getPins() != null)
		{
			for (List<Integer> pin : loc.getPins())
			{
				if (pin == null || pin.size() < 2)
				{
					continue;
				}
				int d = (int) Math.round(Math.hypot(pin.get(0) - px, fold(pin.get(1)) - py));
				if (best < 0 || d < best)
				{
					best = d;
				}
			}
		}
		if (best < 0 && loc.hasCoordinates())
		{
			best = (int) Math.round(Math.hypot(loc.getX() - px, fold(loc.getY()) - py));
		}
		return best;
	}

	private static int fold(int y)
	{
		return y >= 6400 ? y - 6400 : y;
	}

	/** "27s" or "3m". */
	public static String travelWords(int seconds)
	{
		return seconds < 90 ? seconds + "s" : Math.round(seconds / 60.0) + "m";
	}

	/** Region ids with y >= 3520 inside the Wilderness x band. */
	static boolean isWilderness(LocationRecord loc)
	{
		if (loc.getName() != null && loc.getName().toLowerCase(java.util.Locale.ROOT).contains("wilderness"))
		{
			return true;
		}
		if (loc.getRegions() == null)
		{
			return false;
		}
		for (int r : loc.getRegions())
		{
			int rx = r >> 8;
			int ry = r & 0xFF;
			// surface Wilderness: x 2944..3391 (columns 46..52), y 3520..3967 (rows 55..61); column 53 is Morytania
			if (ry >= 55 && ry <= 61 && rx >= 46 && rx <= 52)
			{
				return true;
			}
		}
		return false;
	}

	static int bookIndex(String book)
	{
		if (book == null)
		{
			return -1;
		}
		switch (book.toLowerCase())
		{
			case "standard":
				return 0;
			case "ancient":
				return 1;
			case "lunar":
				return 2;
			case "arceuus":
				return 3;
			default:
				return -1;
		}
	}

	static String pretty(String constant)
	{
		String s = constant.toLowerCase().replace("__", ": ").replace('_', ' ');
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
