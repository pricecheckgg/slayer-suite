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
