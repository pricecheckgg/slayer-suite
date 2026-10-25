package com.slayersuite.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Data;

/** The item groups from items.json, keyed by group id (e.g. "slayer_helm"). */
@Data
public class ItemGroups
{
	@Data
	public static class Group
	{
		private String label;
		private List<String> names = new ArrayList<>();
		private boolean requireAll;
		private transient Set<String> normalized;

		Set<String> normalized()
		{
			if (normalized == null)
			{
				Set<String> s = new HashSet<>();
				for (String n : names)
				{
					s.add(ItemNames.normalize(n));
				}
				normalized = s;
			}
			return normalized;
		}
	}

	private String comment;
	private Map<String, Group> groups = new HashMap<>();

	public Group get(String key)
	{
		return groups.get(key);
	}

	public String label(String key)
	{
		Group g = groups.get(key);
		return g == null || g.getLabel() == null ? key.replace('_', ' ') : g.getLabel();
	}

	/** True when the held item names (already normalised) satisfy the group. */
	public boolean satisfied(String key, Set<String> heldNormalized)
	{
		Group g = groups.get(key);
		if (g == null)
		{
			return false;
		}
		if (g.isRequireAll())
		{
			return heldNormalized.containsAll(g.normalized());
		}
		for (String n : g.normalized())
		{
			if (heldNormalized.contains(n))
			{
				return true;
			}
		}
		return false;
	}

	public boolean anySatisfied(List<String> keys, Set<String> heldNormalized)
	{
		if (keys == null)
		{
			return true;
		}
		for (String k : keys)
		{
			if (satisfied(k, heldNormalized))
			{
				return true;
			}
		}
		return false;
	}

	/** Which of the group's names is held, for display; null if none. */
	public String heldName(List<String> keys, Set<String> heldNormalized)
	{
		if (keys == null)
		{
			return null;
		}
		for (String k : keys)
		{
			Group g = groups.get(k);
			if (g == null)
			{
				continue;
			}
			for (String n : g.getNames())
			{
				if (heldNormalized.contains(ItemNames.normalize(n)))
				{
					return n;
				}
			}
		}
		return null;
	}

	public String describe(List<String> keys)
	{
		if (keys == null || keys.isEmpty())
		{
			return "";
		}
		List<String> labels = new ArrayList<>();
		for (String k : keys)
		{
			labels.add(label(k));
		}
		return String.join(" or ", labels);
	}

	public Set<String> keys()
	{
		return Collections.unmodifiableSet(groups.keySet());
	}
}
