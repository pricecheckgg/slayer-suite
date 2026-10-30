package com.slayersuite.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/** One slayer assignment as the dataset describes it. */
@Data
public class TaskRecord
{
	private String key;
	private String name;
	private String displayName;
	private String wikiPage;
	private List<String> aliases = new ArrayList<>();
	private Integer slayerLevel;
	private Integer combatLevel;
	private String otherRequirements;
	private Integer taskId;
	private Map<String, MasterRange> masters = new HashMap<>();
	private List<MonsterRecord> monsters = new ArrayList<>();
	private List<String> superiors = new ArrayList<>();
	private List<LocationRecord> locations = new ArrayList<>();
	private List<ItemRule> requiredItems = new ArrayList<>();
	private ItemRule finishingItem;
	private List<ItemRule> protection = new ArrayList<>();
	private String style;
	private List<String> notes = new ArrayList<>();
	private List<String> coreTargetNames = new ArrayList<>();
	private boolean reviewed;
	private boolean fallback;
	/** Item whose sprite stands for this task (the same one the client's slayer infobox uses). */
	private Integer iconItemId;

	public String title()
	{
		return displayName != null && !displayName.isEmpty() ? displayName : name;
	}

	/** Every rule the readiness check evaluates, required ones first. */
	public List<ItemRule> allRules()
	{
		List<ItemRule> out = new ArrayList<>();
		if (requiredItems != null)
		{
			out.addAll(requiredItems);
		}
		if (finishingItem != null)
		{
			out.add(finishingItem);
		}
		if (protection != null)
		{
			out.addAll(protection);
		}
		out.sort((a, b) -> Boolean.compare(b.isRequired(), a.isRequired()));
		return out;
	}

	public MonsterRecord primaryMonster()
	{
		if (monsters == null)
		{
			return null;
		}
		for (MonsterRecord m : monsters)
		{
			if (!m.isSuperior())
			{
				return m;
			}
		}
		return monsters.isEmpty() ? null : monsters.get(0);
	}

	public boolean anyPoisonous()
	{
		if (monsters == null)
		{
			return false;
		}
		for (MonsterRecord m : monsters)
		{
			if (isRegular(m) && (m.isPoisonous() || m.isVenomous()))
			{
				return true;
			}
		}
		return false;
	}

	public boolean anyVenomous()
	{
		if (monsters == null)
		{
			return false;
		}
		for (MonsterRecord m : monsters)
		{
			if (isRegular(m) && m.isVenomous())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * How much a cannon (or any multi-target method) helps on this task's monster, 0 to 1: nothing
	 * on things that die in a hit (crawling hands, 16 Hitpoints), everything from about 90 Hitpoints
	 * (bloodvelds, demons). Unknown Hitpoints count as full.
	 */
	public double cannonWorth()
	{
		MonsterRecord m = primaryMonster();
		Integer hp = m == null ? null : m.getHitpoints();
		if (hp == null || hp <= 0)
		{
			return 1.0;
		}
		return Math.max(0, Math.min(1, (hp - 15) / 75.0));
	}

	/** True when the monster you grind the task on cannot be hit by a cannon. */
	public boolean cannonImmune()
	{
		MonsterRecord m = primaryMonster();
		if (m != null && isRegular(m))
		{
			return m.isCannonImmune();
		}
		if (monsters == null || monsters.isEmpty())
		{
			return false;
		}
		for (MonsterRecord x : monsters)
		{
			if (isRegular(x) && !x.isCannonImmune())
			{
				return false;
			}
		}
		return true;
	}

	private transient List<DropRecord> rareCache;

	/** The rare drops of the monsters you grind this task on (superiors left out; a boss task uses the boss), rarest first, one row per item. */
	public synchronized List<DropRecord> rareDrops()
	{
		List<DropRecord> cached = rareCache;
		if (cached != null)
		{
			return cached;
		}
		List<DropRecord> out = collectRare(true);
		if (out.isEmpty())
		{
			out = collectRare(false);
		}
		out.sort((a, b) -> Double.compare(a.probability(), b.probability()));
		rareCache = out;
		return out;
	}

	private List<DropRecord> collectRare(boolean regularOnly)
	{
		List<DropRecord> out = new ArrayList<>();
		Map<String, DropRecord> seen = new HashMap<>();
		if (monsters != null)
		{
			for (MonsterRecord m : monsters)
			{
				if (m.isSuperior() || (regularOnly && !isRegular(m)) || m.getDrops() == null || m.getName() == null)
				{
					continue;
				}
				for (DropRecord d : m.getDrops())
				{
					if (d.getName() == null || d.probability() <= 0 || "superior".equals(d.getWhere()))
					{
						continue;
					}
					String k = ItemNames.key(d.getName()) + "|" + d.getWhere();
					DropRecord first = seen.get(k);
					if (first == null)
					{
						d.getFrom().clear();
						d.getFrom().add(m.getName());
						seen.put(k, d);
						out.add(d);
					}
					else if (!first.getFrom().contains(m.getName()))
					{
						first.getFrom().add(m.getName());
					}
				}
			}
		}
		return out;
	}

	/** The dataset monster matching an NPC name, or null. */
	public MonsterRecord monsterNamed(String npcName)
	{
		if (npcName == null || monsters == null)
		{
			return null;
		}
		for (MonsterRecord m : monsters)
		{
			if (npcName.equalsIgnoreCase(m.getName()) || npcName.equalsIgnoreCase(m.getLabel()))
			{
				return m;
			}
		}
		return null;
	}

	/** Not a superior and not a boss: the monsters you actually grind the task on. */
	private static boolean isRegular(MonsterRecord m)
	{
		if (m.isSuperior())
		{
			return false;
		}
		String cat = m.getCategory();
		return cat == null || !cat.toLowerCase().contains("boss");
	}
}
