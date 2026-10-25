package com.slayersuite.card;

import com.slayersuite.data.MonsterRecord;
import com.slayersuite.data.TaskRecord;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.Prayer;

/** Works out which protection prayer a task (or one of its monsters) calls for. */
public final class PrayerHints
{
	private PrayerHints()
	{
	}

	/** From the dataset's prayer text, e.g. "Protect from Melee (with antifire)". */
	public static Prayer fromText(String text)
	{
		if (text == null)
		{
			return null;
		}
		String t = text.toLowerCase(Locale.ROOT);
		if (!t.contains("protect"))
		{
			return null;
		}
		if (t.contains("melee"))
		{
			return Prayer.PROTECT_FROM_MELEE;
		}
		if (t.contains("missile") || t.contains("range"))
		{
			return Prayer.PROTECT_FROM_MISSILES;
		}
		if (t.contains("magic"))
		{
			return Prayer.PROTECT_FROM_MAGIC;
		}
		return null;
	}

	/** Every protection prayer a monster's styles call for (two or more means it switches). */
	public static Set<Prayer> candidates(List<String> styles)
	{
		Set<Prayer> out = EnumSet.noneOf(Prayer.class);
		if (styles == null)
		{
			return out;
		}
		for (String s : styles)
		{
			String t = s.toLowerCase(Locale.ROOT);
			if (t.contains("melee") || t.contains("stab") || t.contains("slash") || t.contains("crush"))
			{
				out.add(Prayer.PROTECT_FROM_MELEE);
			}
			else if (t.contains("range"))
			{
				out.add(Prayer.PROTECT_FROM_MISSILES);
			}
			else if (t.contains("magic") || t.contains("dragonfire") || t.contains("breath"))
			{
				out.add(Prayer.PROTECT_FROM_MAGIC);
			}
		}
		return out;
	}

	/** The prayers a specific NPC can call for: its own styles, else the task line. Empty when unknown. */
	public static Set<Prayer> candidatesForNpc(TaskRecord rec, String npcName)
	{
		if (rec == null)
		{
			return EnumSet.noneOf(Prayer.class);
		}
		MonsterRecord m = npcName == null ? null : rec.monsterNamed(npcName);
		Set<Prayer> set = m == null ? EnumSet.noneOf(Prayer.class) : candidates(m.getAttackStyles());
		if (set.isEmpty())
		{
			Prayer p = fromText(rec.getPrayer());
			if (p != null)
			{
				set.add(p);
			}
		}
		return set;
	}

	/** From a monster's attack styles when they point one way; null when mixed or unknown. */
	public static Prayer fromStyles(List<String> styles)
	{
		if (styles == null || styles.isEmpty())
		{
			return null;
		}
		boolean melee = false;
		boolean ranged = false;
		boolean magic = false;
		for (String s : styles)
		{
			String t = s.toLowerCase(Locale.ROOT);
			if (t.contains("melee") || t.contains("stab") || t.contains("slash") || t.contains("crush"))
			{
				melee = true; // "magical melee" and "ranged melee" are blocked by Protect from Melee
			}
			else if (t.contains("range"))
			{
				ranged = true;
			}
			else if (t.contains("magic") || t.contains("dragonfire") || t.contains("breath"))
			{
				magic = true;
			}
		}
		int n = (melee ? 1 : 0) + (ranged ? 1 : 0) + (magic ? 1 : 0);
		if (n != 1)
		{
			return null;
		}
		return melee ? Prayer.PROTECT_FROM_MELEE : ranged ? Prayer.PROTECT_FROM_MISSILES : Prayer.PROTECT_FROM_MAGIC;
	}

	/** The prayer for a specific NPC of the task: its own styles first, then the task's prayer line. */
	public static Prayer forNpc(TaskRecord rec, String npcName)
	{
		if (rec == null)
		{
			return null;
		}
		Prayer fromTask = fromText(rec.getPrayer());
		if (npcName != null)
		{
			for (MonsterRecord m : rec.getMonsters())
			{
				if (npcName.equalsIgnoreCase(m.getName()) || npcName.equalsIgnoreCase(m.getLabel()))
				{
					if (m.getAttackStyles() != null && !m.getAttackStyles().isEmpty())
					{
						// Known styles decide: a switcher (mixed styles) must not inherit the task's single prayer.
						return fromStyles(m.getAttackStyles());
					}
					break;
				}
			}
		}
		if (fromTask != null)
		{
			return fromTask;
		}
		MonsterRecord primary = rec.primaryMonster();
		return primary == null ? null : fromStyles(primary.getAttackStyles());
	}
}
