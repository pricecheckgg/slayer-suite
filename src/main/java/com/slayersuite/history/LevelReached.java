package com.slayersuite.history;

import java.util.List;
import lombok.Value;
import net.runelite.api.Skill;

/** Posted when a skill levels up, with what happened since that skill's previous level. */
@Value
public class LevelReached
{
	Skill skill;
	int level;
	/** True when the plugin has no record of the previous level, so the counters are unknown. */
	boolean firstSeen;
	long kills;
	long activeMs;
	long lootValue;
	long xpToNext;
	List<TaskHistory.LootItem> loot;
	long missedValue;
	List<TaskHistory.LootItem> missed;
	/** Fired by the test button: show everything, record nothing. */
	boolean test;
}
