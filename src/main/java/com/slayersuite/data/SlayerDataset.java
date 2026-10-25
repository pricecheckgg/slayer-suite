package com.slayersuite.data;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/** Loads monsters.json and items.json from the plugin resources and answers lookups by task name. */
@Slf4j
@Singleton
public class SlayerDataset
{
	private static final String MONSTERS = "/com/slayersuite/data/monsters.json";
	private static final String ITEMS = "/com/slayersuite/data/items.json";

	private final Gson gson;

	private final Map<String, TaskRecord> byKey = new HashMap<>();
	private final Map<String, TaskRecord> byName = new HashMap<>();
	private final Map<String, TaskRecord> byMonster = new HashMap<>();

	@Getter
	private List<TaskRecord> tasks = Collections.emptyList();
	@Getter
	private ItemGroups items = new ItemGroups();
	@Getter
	private String generated = "";
	@Getter
	private boolean loaded;

	private static class DatasetFile
	{
		int schemaVersion;
		String generated;
		String source;
		List<TaskRecord> tasks;
	}

	@Inject
	public SlayerDataset(Gson gson)
	{
		this.gson = gson;
	}

	public synchronized void load()
	{
		try (Reader r = open(MONSTERS))
		{
			DatasetFile f = gson.fromJson(r, DatasetFile.class);
			tasks = f.tasks == null ? Collections.emptyList() : f.tasks;
			generated = f.generated == null ? "" : f.generated;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Slayer Suite: could not read monsters.json", e);
			tasks = Collections.emptyList();
		}
		try (Reader r = open(ITEMS))
		{
			ItemGroups g = gson.fromJson(r, ItemGroups.class);
			items = g == null ? new ItemGroups() : g;
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("Slayer Suite: could not read items.json", e);
			items = new ItemGroups();
		}
		index();
		loaded = true;
		log.debug("Slayer Suite dataset: {} tasks, {} item groups, generated {}", tasks.size(), items.keys().size(), generated);
	}

	private Reader open(String resource) throws IOException
	{
		InputStream in = SlayerDataset.class.getResourceAsStream(resource);
		if (in == null)
		{
			throw new IOException("missing resource " + resource);
		}
		return new InputStreamReader(in, StandardCharsets.UTF_8);
	}

	private void index()
	{
		byKey.clear();
		byName.clear();
		byMonster.clear();
		for (TaskRecord t : tasks)
		{
			if (t.getKey() != null)
			{
				byKey.put(t.getKey(), t);
			}
			putName(t.getName(), t);
			putName(t.getDisplayName(), t);
			for (String a : t.getAliases())
			{
				putName(a, t);
			}
			if (!t.getCoreTargetNames().isEmpty())
			{
				putName(t.getCoreTargetNames().get(0), t);
			}
		}
		for (TaskRecord t : tasks)
		{
			for (MonsterRecord m : t.getMonsters())
			{
				byMonster.putIfAbsent(ItemNames.key(m.getName()), t);
				byMonster.putIfAbsent(ItemNames.key(m.getLabel()), t);
			}
			for (String n : t.getCoreTargetNames())
			{
				byMonster.putIfAbsent(ItemNames.key(n), t);
			}
		}
	}

	private void putName(String name, TaskRecord t)
	{
		String k = ItemNames.key(name);
		if (!k.isEmpty())
		{
			byName.putIfAbsent(k, t);
		}
	}

	/** Find the record for a task name as the core Slayer plugin reports it (any case, singular or plural). */
	public TaskRecord find(String taskName)
	{
		String k = ItemNames.key(taskName);
		if (k.isEmpty())
		{
			return null;
		}
		for (String cand : variants(k))
		{
			TaskRecord t = byName.get(cand);
			if (t != null)
			{
				return t;
			}
		}
		for (String cand : variants(k))
		{
			TaskRecord t = byMonster.get(cand);
			if (t != null)
			{
				return t;
			}
		}
		return null;
	}

	/** The task a monster name belongs to, or null. */
	public TaskRecord forMonsterName(String npcName)
	{
		String k = ItemNames.key(npcName);
		if (k.isEmpty())
		{
			return null;
		}
		for (String cand : variants(k))
		{
			TaskRecord t = byMonster.get(cand);
			if (t != null)
			{
				return t;
			}
		}
		return null;
	}

	public TaskRecord byKey(String key)
	{
		return byKey.get(key);
	}

	/** The dataset location whose regions include one of the loaded map regions, or null. */
	public LocationRecord locationFor(TaskRecord rec, int[] regions)
	{
		if (rec == null || regions == null)
		{
			return null;
		}
		for (LocationRecord loc : rec.getLocations())
		{
			for (int r : regions)
			{
				if (loc.coversRegion(r))
				{
					return loc;
				}
			}
		}
		return null;
	}

	private static List<String> variants(String k)
	{
		Set<String> set = new HashSet<>();
		List<String> out = new ArrayList<>();
		add(out, set, k);
		if (k.endsWith("ies"))
		{
			add(out, set, k.substring(0, k.length() - 3) + "y");
		}
		if (k.endsWith("es"))
		{
			add(out, set, k.substring(0, k.length() - 2));
		}
		if (k.endsWith("s"))
		{
			add(out, set, k.substring(0, k.length() - 1));
		}
		else
		{
			add(out, set, k + "s");
			add(out, set, k + "es");
		}
		if (k.startsWith("the"))
		{
			add(out, set, k.substring(3));
		}
		return out;
	}

	private static void add(List<String> out, Set<String> seen, String s)
	{
		if (!s.isEmpty() && seen.add(s))
		{
			out.add(s);
		}
	}
}
