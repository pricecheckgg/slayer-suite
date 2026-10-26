package com.slayersuite.advisor;

import com.google.gson.Gson;
import com.slayersuite.data.ItemNames;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/** travel.json: access requirements and teleport options per location name. */
@Slf4j
@Singleton
public class TravelData
{
	@Data
	public static class Requirement
	{
		private String quest;
		private boolean started;
		private String diary;
		private String skill;
		private int level;
	}

	@Data
	public static class Teleport
	{
		private String item;
		private String spell;
		private String book;
		private String fairy;
		/** A house facility; with item set it is the jewellery box standing in for that item. */
		private String poh;
		private String quest;
		private int level;
		private int hops = 2;
		/** Where this teleport puts you, when known (per entry, else from the LANDINGS table). */
		private Integer x;
		private Integer y;

		public String label()
		{
			if (poh != null)
			{
				return "House: " + (item != null ? poh + " (" + item + ")" : poh);
			}
			if (item != null)
			{
				return item;
			}
			if (spell != null)
			{
				return spell;
			}
			if (fairy != null)
			{
				return "Fairy ring " + fairy;
			}
			return "teleport";
		}
	}

	@Data
	public static class Entry
	{
		private String name;
		private boolean wilderness;
		private List<Requirement> requires = new ArrayList<>();
		private List<Teleport> teleports = new ArrayList<>();
		private transient String norm;
	}

	private static class File
	{
		String comment;
		List<Entry> locations;
	}

	private final Gson gson;
	private List<Entry> entries = new ArrayList<>();

	/** Where common teleports land. Fairy rings are keyed by code. */
	private static final java.util.Map<String, int[]> LANDINGS = new java.util.HashMap<>();

	static
	{
		String[][] rows = {
			{"Xeric's talisman", "1639", "3673"}, {"Kourend Castle Teleport", "1643", "3673"}, {"Kharyrll Teleport", "3492", "3471"},
			{"Rada's blessing", "1310", "3796"}, {"Digsite pendant", "3346", "3445"}, {"Burning amulet", "3028", "3842"},
			{"Trollheim Teleport", "2891", "3678"}, {"Ghommal's hilt", "2912", "3754"}, {"Games necklace", "2898", "3545"},
			{"Falador Teleport", "2965", "3379"}, {"Lumbridge Teleport", "3222", "3218"}, {"Ardougne Teleport", "2662", "3305"},
			{"Moonclan Teleport", "2113", "3915"}, {"Waterbirth Teleport", "2546", "3755"}, {"Ectophial", "3660", "3524"},
			{"Morytania legs", "3484", "3236"}, {"Enchanted lyre", "2660", "3464"}, {"Desert amulet", "3431", "2913"},
			{"Karamja gloves", "2827", "2999"}, {"Amulet of glory", "3087", "3496"}, {"Combat bracelet", "2882", "3548"},
			{"Skills necklace", "2611", "3391"}, {"Ring of dueling", "3150", "3635"}, {"Explorer's ring", "3052", "3287"},
			{"Royal seed pod", "2465", "3495"}, {"Spirit tree", "2461", "3444"}, {"Teleport crystal", "2329", "3171"},
			{"Iorwerth camp teleport", "2199", "3261"}, {"Drakan's medallion", "3649", "3230"}, {"Mythical cape", "2459", "2847"},
			{"Dorgesh-kaan sphere", "2720", "5351"}, {"Camulet", "3191", "2923"}, {"Pharaoh's sceptre", "3288", "2798"},
			{"Kharedst's memoirs", "1639", "3673"}, {"Book of the dead", "1639", "3673"}, {"Falador shield", "2965", "3379"},
			{"CKS", "3447", "3470"}, {"AIQ", "2996", "3114"}, {"AJR", "2780", "3613"}, {"CIR", "1302", "3762"}, {"DJR", "1455", "3658"},
			{"DLQ", "3423", "3016"}, {"BIQ", "3251", "3095"}, {"DKP", "2900", "3111"}, {"AJQ", "2735", "5221"}, {"ALP", "2503", "3636"},
			{"DKS", "2744", "3719"}, {"BKS", "2412", "4434"},
		};
		for (String[] r : rows)
		{
			LANDINGS.put(r[0].toLowerCase(Locale.ROOT), new int[]{Integer.parseInt(r[1]), Integer.parseInt(r[2])});
		}
	}

	/** Landing point of a teleport, or null when unknown. For a house facility, where its teleport puts you. */
	public static int[] landing(Teleport t)
	{
		return landing(t, null);
	}

	/** As above; the entry lets a house fairy ring borrow the spot's own fairy ring code. */
	public static int[] landing(Teleport t, Entry entry)
	{
		if (t == null)
		{
			return null;
		}
		if (t.getX() != null && t.getY() != null)
		{
			return new int[]{t.getX(), t.getY()};
		}
		if (t.getPoh() != null && t.getItem() == null)
		{
			String poh = t.getPoh().toLowerCase(Locale.ROOT);
			if (poh.equals("fairy ring"))
			{
				if (entry != null)
				{
					for (Teleport other : entry.getTeleports())
					{
						if (other.getFairy() != null)
						{
							return LANDINGS.get(other.getFairy().toLowerCase(Locale.ROOT));
						}
					}
				}
				return null;
			}
			String spell = POH_LANDING_KEYS.get(poh);
			return spell == null ? LANDINGS.get(poh) : LANDINGS.get(spell);
		}
		String key = t.getFairy() != null ? t.getFairy() : t.getItem() != null ? t.getItem() : t.getSpell();
		return key == null ? null : LANDINGS.get(key.toLowerCase(Locale.ROOT));
	}

	/** House portals and mounts, to the landing key of the teleport they stand in for. */
	private static final Map<String, String> POH_LANDING_KEYS = new HashMap<>();

	static
	{
		POH_LANDING_KEYS.put("kourend portal", "kourend castle teleport");
		POH_LANDING_KEYS.put("kharyrll portal", "kharyrll teleport");
		POH_LANDING_KEYS.put("ardougne portal", "ardougne teleport");
		POH_LANDING_KEYS.put("falador portal", "falador teleport");
		POH_LANDING_KEYS.put("lumbridge portal", "lumbridge teleport");
		POH_LANDING_KEYS.put("lunar isle portal", "moonclan teleport");
		POH_LANDING_KEYS.put("waterbirth island portal", "waterbirth teleport");
		POH_LANDING_KEYS.put("troll stronghold portal", "trollheim teleport");
		POH_LANDING_KEYS.put("xeric's talisman", "xeric's talisman");
		POH_LANDING_KEYS.put("digsite pendant", "digsite pendant");
		POH_LANDING_KEYS.put("mythical cape", "mythical cape");
		POH_LANDING_KEYS.put("amulet of glory", "amulet of glory");
	}

	@Inject
	public TravelData(Gson gson)
	{
		this.gson = gson;
	}

	public void load()
	{
		try (InputStream in = TravelData.class.getResourceAsStream("/com/slayersuite/data/travel.json"))
		{
			if (in == null)
			{
				throw new IOException("missing travel.json");
			}
			Reader r = new InputStreamReader(in, StandardCharsets.UTF_8);
			File f = gson.fromJson(r, File.class);
			entries = f == null || f.locations == null ? new ArrayList<>() : f.locations;
			for (Entry e : entries)
			{
				e.norm = normalize(e.name);
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Slayer Suite: could not read travel.json", e);
			entries = new ArrayList<>();
		}
	}

	static String normalize(String name)
	{
		if (name == null)
		{
			return "";
		}
		String s = name.toLowerCase(Locale.ROOT).replaceAll("\\([^)]*\\)", " ");
		return ItemNames.key(s);
	}

	/** The entry whose name is a prefix of the location name (longest match wins), or null. */
	public Entry find(String locationName)
	{
		String n = normalize(locationName);
		Entry best = null;
		for (Entry e : entries)
		{
			if (e.norm.isEmpty())
			{
				continue;
			}
			if (n.startsWith(e.norm) || n.contains(e.norm))
			{
				if (best == null || e.norm.length() > best.norm.length())
				{
					best = e;
				}
			}
		}
		return best;
	}

	public int size()
	{
		return entries.size();
	}

	/** Every item name any teleport refers to. */
	public Set<String> itemNames()
	{
		Set<String> out = new java.util.HashSet<>();
		for (Entry e : entries)
		{
			for (Teleport t : e.getTeleports())
			{
				if (t.getItem() != null)
				{
					out.add(t.getItem());
				}
			}
		}
		return out;
	}
}
