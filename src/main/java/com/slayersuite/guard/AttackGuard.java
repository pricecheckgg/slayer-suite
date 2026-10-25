package com.slayersuite.guard;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.task.TaskTracker;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.PostMenuSort;
import net.runelite.client.eventbus.Subscribe;

/**
 * Removes or deprioritises the Attack option on NPCs that do not count for the
 * current task. Reads menu entries and edits the menu only; never clicks.
 */
@Singleton
public class AttackGuard
{
	private final Client client;
	private final SlayerSuiteConfig config;
	private final TaskTracker tracker;
	private final SlayerDataset dataset;
	private final javax.inject.Provider<com.slayersuite.task.SlayerMode> mode;

	@Inject
	public AttackGuard(Client client, SlayerSuiteConfig config, TaskTracker tracker, SlayerDataset dataset, javax.inject.Provider<com.slayersuite.task.SlayerMode> mode)
	{
		this.client = client;
		this.config = config;
		this.tracker = tracker;
		this.dataset = dataset;
		this.mode = mode;
	}

	private boolean active()
	{
		if (!config.guardEnabled() || !mode.get().active())
		{
			return false;
		}
		return tracker.hasTask() || config.guardWithoutTask();
	}

	private static boolean isNpcOption(MenuAction type)
	{
		return type == MenuAction.NPC_FIRST_OPTION || type == MenuAction.NPC_SECOND_OPTION
			|| type == MenuAction.NPC_THIRD_OPTION || type == MenuAction.NPC_FOURTH_OPTION
			|| type == MenuAction.NPC_FIFTH_OPTION;
	}

	/** True when the NPC should keep its Attack option. */
	public boolean isTaskTarget(NPC npc)
	{
		if (npc == null)
		{
			return true;
		}
		if (!tracker.hasTask())
		{
			return false;
		}
		if (tracker.getTargets().contains(npc))
		{
			return true;
		}
		// The core plugin matches by name as well; fall back to the dataset in case its list lags a tick.
		NPCComposition comp = npc.getTransformedComposition();
		String name = comp == null ? npc.getName() : comp.getName();
		if (name == null)
		{
			return false;
		}
		TaskRecord rec = tracker.getRecord();
		if (rec != null)
		{
			for (String n : rec.getCoreTargetNames())
			{
				if (name.equalsIgnoreCase(n))
				{
					return true;
				}
			}
			TaskRecord byMonster = dataset.forMonsterName(name);
			return byMonster == rec;
		}
		return false;
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded e)
	{
		if (!active() || config.guardMode() != GuardMode.DEPRIORITIZE)
		{
			return;
		}
		if (!isNpcOption(e.getMenuEntry().getType()) || !"Attack".equalsIgnoreCase(e.getOption()))
		{
			return;
		}
		NPC npc = e.getMenuEntry().getNpc();
		if (npc != null && !isTaskTarget(npc))
		{
			e.getMenuEntry().setDeprioritized(true);
		}
	}

	@Subscribe
	public void onPostMenuSort(PostMenuSort sort)
	{
		if (!active() || config.guardMode() != GuardMode.HIDE || client.isMenuOpen())
		{
			return;
		}
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries == null || entries.length == 0)
		{
			return;
		}
		List<MenuEntry> kept = null;
		for (int i = 0; i < entries.length; i++)
		{
			MenuEntry me = entries[i];
			boolean drop = false;
			if (isNpcOption(me.getType()) && "Attack".equalsIgnoreCase(me.getOption()))
			{
				NPC npc = me.getNpc();
				drop = npc != null && !isTaskTarget(npc);
			}
			if (drop)
			{
				if (kept == null)
				{
					kept = new ArrayList<>(entries.length);
					for (int j = 0; j < i; j++)
					{
						kept.add(entries[j]);
					}
				}
			}
			else if (kept != null)
			{
				kept.add(me);
			}
		}
		if (kept != null)
		{
			client.getMenu().setMenuEntries(kept.toArray(new MenuEntry[0]));
		}
	}
}
