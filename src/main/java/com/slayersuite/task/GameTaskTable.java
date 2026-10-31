package com.slayersuite.task;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The game's own slayer tables, read from the client cache: which task the target
 * value means, which area the area value means, and how each master weights
 * its assignments. This is how the client itself names your task, so it works
 * for every master, Mortimer included, with no gem check.
 */
@Slf4j
@Singleton
public class GameTaskTable
{
	/** Target value the game uses for "a boss task"; the boss id then lives in another value. */
	private static final int BOSS_TARGET = 98;

	@Value
	public static class GameTask
	{
		int taskId;
		String name;
		int remaining;
		String area;
		int masterId;
	}

	private final Client client;
	private final Map<Integer, String> nameCache = new LinkedHashMap<>();
	private final Map<Integer, String> areaCache = new LinkedHashMap<>();
	private final Map<Integer, Map<Integer, Integer>> weightCache = new LinkedHashMap<>();
	private final Map<Integer, TaskModifier.Kind> kindCache = new LinkedHashMap<>();

	@Inject
	public GameTaskTable(Client client)
	{
		this.client = client;
	}

	/** The current assignment straight from the game, or null when there is none or the tables are unavailable. */
	public GameTask currentTask()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		try
		{
			int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
			if (remaining <= 0)
			{
				return null;
			}
			int target = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
			int taskId = target;
			if (target == BOSS_TARGET)
			{
				int bossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
				List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTaskSublist.ID, DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID, 0, bossId);
				if (rows == null || rows.isEmpty())
				{
					return null;
				}
				Object[] f = client.getDBTableField(rows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0);
				if (f == null || f.length == 0 || !(f[0] instanceof Integer))
				{
					return null;
				}
				taskId = (Integer) f[0];
			}
			String name = taskName(taskId);
			if (name == null)
			{
				return null;
			}
			String area = null;
			int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
			if (areaId > 0)
			{
				area = areaName(areaId);
			}
			int master = client.getVarbitValue(VarbitID.SLAYER_MASTER);
			return new GameTask(taskId, name, remaining, area, master);
		}
		catch (RuntimeException e)
		{
			log.debug("slayer table lookup failed", e);
			return null;
		}
	}

	/** Mortimer's bonus on the current task, from the game's modifier values; NONE on another master's task or when logged out. */
	public TaskModifier currentModifier()
	{
		return modifier(VarbitID.SLAYER_MODIFIER_ID, VarbitID.SLAYER_MODIFIER_VALUE, VarbitID.SLAYER_MODIFIER_NEGATIVE);
	}

	/** The bonus on the task you have stored, when there is one. */
	public TaskModifier storedModifier()
	{
		return modifier(VarbitID.SLAYER_MODIFIER_ID_STORED, VarbitID.SLAYER_MODIFIER_VALUE_STORED, VarbitID.SLAYER_MODIFIER_NEGATIVE_STORED);
	}

	private TaskModifier modifier(int idVarbit, int valueVarbit, int negativeVarbit)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return TaskModifier.NONE;
		}
		try
		{
			int id = client.getVarbitValue(idVarbit);
			if (id <= 0)
			{
				return TaskModifier.NONE;
			}
			return TaskModifier.of(modifierKind(id), client.getVarbitValue(valueVarbit), client.getVarbitValue(negativeVarbit) == 1);
		}
		catch (RuntimeException ex)
		{
			log.debug("Slayer Suite: modifier values unavailable", ex);
			return TaskModifier.NONE;
		}
	}

	/** Which bonus a modifier id is: the game's SlayerModifiers table when it can be read, else the table's known order. */
	TaskModifier.Kind modifierKind(int id)
	{
		TaskModifier.Kind cached = kindCache.get(id);
		if (cached != null)
		{
			return cached;
		}
		TaskModifier.Kind kind = TaskModifier.kindOf(id);
		try
		{
			List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerModifiers.ID, DBTableID.SlayerModifiers.COL_ID, 0, id);
			TaskModifier.Kind byRow = rows != null && rows.size() == 1 ? kindOfRow(rows.get(0)) : null;
			if (byRow != null)
			{
				kind = byRow;
			}
		}
		catch (RuntimeException ex)
		{
			log.debug("Slayer Suite: SlayerModifiers table unavailable", ex);
		}
		kindCache.put(id, kind);
		return kind;
	}

	private static TaskModifier.Kind kindOfRow(int row)
	{
		switch (row)
		{
			case DBTableID.SlayerModifiers.Row.SLAYER_MODIFIER_NONE: return TaskModifier.Kind.NONE;
			case DBTableID.SlayerModifiers.Row.SLAYER_MODIFIER_POINTS: return TaskModifier.Kind.POINTS;
			case DBTableID.SlayerModifiers.Row.SLAYER_MODIFIER_QUANTITY: return TaskModifier.Kind.QUANTITY;
			case DBTableID.SlayerModifiers.Row.SLAYER_MODIFIER_CLUES: return TaskModifier.Kind.CLUES;
			case DBTableID.SlayerModifiers.Row.SLAYER_MODIFIER_SUPERIOR_CHANCE: return TaskModifier.Kind.SUPERIOR_CHANCE;
			case DBTableID.SlayerModifiers.Row.SLAYER_MODIFIER_XP_BOOST: return TaskModifier.Kind.XP_BOOST;
			default: return null;
		}
	}

	/** Display name of a task id from the SlayerTask table, or null. */
	public String taskName(int taskId)
	{
		String cached = nameCache.get(taskId);
		if (cached != null)
		{
			return cached;
		}
		List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
		if (rows == null || rows.isEmpty())
		{
			return null;
		}
		Object[] f = client.getDBTableField(rows.get(0), DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
		if (f == null || f.length == 0 || !(f[0] instanceof String))
		{
			f = client.getDBTableField(rows.get(0), DBTableID.SlayerTask.COL_NAME_LOWERCASE, 0);
		}
		if (f == null || f.length == 0 || !(f[0] instanceof String))
		{
			return null;
		}
		String name = (String) f[0];
		nameCache.put(taskId, name);
		return name;
	}

	public String areaName(int areaId)
	{
		String cached = areaCache.get(areaId);
		if (cached != null)
		{
			return cached;
		}
		List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
		if (rows == null || rows.isEmpty())
		{
			return null;
		}
		Object[] f = client.getDBTableField(rows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0);
		if (f == null || f.length == 0 || !(f[0] instanceof String))
		{
			f = client.getDBTableField(rows.get(0), DBTableID.SlayerArea.COL_AREA_TEXT, 0);
		}
		if (f == null || f.length == 0 || !(f[0] instanceof String))
		{
			return null;
		}
		String name = (String) f[0];
		areaCache.put(areaId, name);
		return name;
	}

	/** task id -> weight for one master, from the SlayerMasterTask table (empty when unavailable). */
	public Map<Integer, Integer> masterWeights(int masterId)
	{
		Map<Integer, Integer> cached = weightCache.get(masterId);
		if (cached != null)
		{
			return cached;
		}
		Map<Integer, Integer> out = new LinkedHashMap<>();
		try
		{
			List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerMasterTask.ID, DBTableID.SlayerMasterTask.COL_MASTER_ID, 0, masterId);
			if (rows != null)
			{
				for (int row : rows)
				{
					Object[] task = client.getDBTableField(row, DBTableID.SlayerMasterTask.COL_TASK, 0);
					Object[] weight = client.getDBTableField(row, DBTableID.SlayerMasterTask.COL_WEIGHT, 0);
					if (task != null && task.length > 0 && task[0] instanceof Integer && weight != null && weight.length > 0 && weight[0] instanceof Integer)
					{
						out.merge((Integer) task[0], (Integer) weight[0], Integer::sum);
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("master weights lookup failed", e);
		}
		if (!out.isEmpty())
		{
			weightCache.put(masterId, out);
		}
		return out;
	}

	/** Share of this master's rolls that land on the task, 0..1, or -1 when unknown. Ignores unlocks and blocks. */
	public double rollChance(int masterId, int taskId)
	{
		Map<Integer, Integer> w = masterWeights(masterId);
		if (w.isEmpty() || !w.containsKey(taskId))
		{
			return -1;
		}
		long total = 0;
		for (int v : w.values())
		{
			total += v;
		}
		return total <= 0 ? -1 : w.get(taskId) / (double) total;
	}

	/** Min and max amount this master assigns for the task, or null. */
	public int[] masterAmounts(int masterId, int taskId)
	{
		try
		{
			List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerMasterTask.ID, DBTableID.SlayerMasterTask.COL_MASTER_ID, 0, masterId);
			if (rows == null)
			{
				return null;
			}
			for (int row : rows)
			{
				Object[] task = client.getDBTableField(row, DBTableID.SlayerMasterTask.COL_TASK, 0);
				if (task != null && task.length > 0 && task[0] instanceof Integer && (Integer) task[0] == taskId)
				{
					Object[] min = client.getDBTableField(row, DBTableID.SlayerMasterTask.COL_MIN_AMOUNT, 0);
					Object[] max = client.getDBTableField(row, DBTableID.SlayerMasterTask.COL_MAX_AMOUNT, 0);
					if (min != null && max != null && min.length > 0 && max.length > 0 && min[0] instanceof Integer && max[0] instanceof Integer)
					{
						return new int[]{(Integer) min[0], (Integer) max[0]};
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("master amounts lookup failed", e);
		}
		return null;
	}

	public static String masterName(int masterId)
	{
		switch (masterId)
		{
			case 1: return "Turael";
			case 2: return "Mazchna";
			case 3: return "Vannaka";
			case 4: return "Chaeldar";
			case 5: return "Duradel";
			case 6: return "Nieve";
			case 7: return "Krystilia";
			case 8: return "Konar";
			case 10: return "Mortimer";
			default: return "your master";
		}
	}
}
