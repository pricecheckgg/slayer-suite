package com.slayersuite.history;

import com.google.gson.Gson;
import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.alerts.AlertEngine;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.guard.AttackGuard;
import com.slayersuite.task.AssignmentStarted;
import com.slayersuite.task.KillCounted;
import com.slayersuite.task.PointsSpent;
import com.slayersuite.task.TaskStored;
import com.slayersuite.task.TaskChanged;
import com.slayersuite.task.TaskFinished;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.TripMath;
import com.slayersuite.trip.TripTracker;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.client.eventbus.EventBus;
import net.runelite.api.GameState;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * Per-task kills per hour, Slayer experience per hour and loot value per hour,
 * measured from your own play and stored on the RuneScape profile so it follows
 * the account. Feeds the ETA before the first kills of a task and the skip advice.
 */
@Slf4j
@Singleton
public class TaskHistory
{
	static final String PREFIX = "history.";
	private static final long BREAK_GAP_MS = 4 * 60_000L;
	private static final long SAVE_EVERY_MS = 60_000L;
	private static final int ADVICE_MIN_KILLS = 20;

	private final ConfigManager configManager;
	private final Gson gson;
	private final Client client;
	private final TaskTracker tracker;
	private final SlayerDataset dataset;
	private final SlayerSuiteConfig config;
	private final ItemManager itemManager;
	private final EventBus eventBus;
	private final com.slayersuite.task.GameTaskTable table;
	/** Points, milestones, skip payback and block candidates, recomputed on the client thread every few seconds. */
	@Getter
	private volatile PointsSummary pointsSummary;
	private int lastMasterId = -1;
	private int pointsTick;
	private final Map<Integer, Map<String, Integer>> weightKeysCache = new HashMap<>();
	/** One line about the last finished task, ready for the clipboard. */
	@Getter
	private volatile String lastReport = "";
	/** Task name -> varbit of its "longer tasks" Slayer reward unlock. */
	private static final Map<String, Integer> LONGER_UNLOCKS = new LinkedHashMap<>();

	static
	{
		LONGER_UNLOCKS.put("Dark beasts", 4031);
		LONGER_UNLOCKS.put("Ankou", 4085);
		LONGER_UNLOCKS.put("Suqah", 4086);
		LONGER_UNLOCKS.put("Black dragons", 4087);
		LONGER_UNLOCKS.put("Iron dragons", 4088);
		LONGER_UNLOCKS.put("Steel dragons", 4088);
		LONGER_UNLOCKS.put("Abyssal demons", 4090);
		LONGER_UNLOCKS.put("Black demons", 4091);
		LONGER_UNLOCKS.put("Greater demons", 4092);
		LONGER_UNLOCKS.put("Bloodveld", 4746);
		LONGER_UNLOCKS.put("Aberrant spectres", 4747);
		LONGER_UNLOCKS.put("Aviansies", 4748);
		LONGER_UNLOCKS.put("Mithril dragons", 4749);
		LONGER_UNLOCKS.put("Cave horrors", 4750);
		LONGER_UNLOCKS.put("Dust devils", 4751);
		LONGER_UNLOCKS.put("Skeletal wyverns", 4752);
		LONGER_UNLOCKS.put("Gargoyles", 4753);
		LONGER_UNLOCKS.put("Nechryael", 4754);
		LONGER_UNLOCKS.put("Cave kraken", 4755);
		LONGER_UNLOCKS.put("Spiritual creatures", 4757);
		LONGER_UNLOCKS.put("Fossil island wyverns", 5733);
		LONGER_UNLOCKS.put("Adamant dragons", 6094);
		LONGER_UNLOCKS.put("Rune dragons", 6095);
		LONGER_UNLOCKS.put("Basilisks", 9455);
		LONGER_UNLOCKS.put("Vampyres", 10389);
		LONGER_UNLOCKS.put("Araxytes", 11022);
		LONGER_UNLOCKS.put("Revenants", 14822);
		LONGER_UNLOCKS.put("Gryphons", 15398);
		LONGER_UNLOCKS.put("Frost dragons", 15399);
		LONGER_UNLOCKS.put("Custodians", 17219);
		LONGER_UNLOCKS.put("Wyrms", 19602);
		LONGER_UNLOCKS.put("Aquanites", 19603);
	}

	@lombok.Value
	public static class BlockRow
	{
		String name;
		double share;
		double xpPerHour;
		double gainPct;
	}

	@lombok.Value
	public static class PointsSummary
	{
		int masterId;
		String master;
		int points;
		int streak;
		double avgPerTask;
		int nextBonusTask;
		int nextBonusPoints;
		int bigTask;
		int bigPoints;
		/** Your average active time per finished task; 0 when unknown. */
		long msPerTask;
		double skipPaybackTasks;
		double medianXpPerHour;
		List<BlockRow> blocks;
		List<String> tips;
	}

	/** Median xp/h over the tasks you have real rates for; 0 with fewer than three. */
	double medianXpPerHour()
	{
		return medianXpPerHour(all());
	}

	static double medianXpPerHour(List<TaskStats> stats)
	{
		List<Double> rates = new ArrayList<>();
		for (TaskStats t : stats)
		{
			if (t.getKills() >= ADVICE_MIN_KILLS && t.hasRate())
			{
				rates.add(t.xpPerHour());
			}
		}
		if (rates.size() < 3)
		{
			return 0;
		}
		rates.sort(Double::compare);
		return rates.get(rates.size() / 2);
	}

	/** Client thread. */
	private void computePointsSummary()
	{
		com.slayersuite.task.GameTaskTable.GameTask gt = tracker.getGameTask();
		if (gt != null)
		{
			lastMasterId = gt.getMasterId();
		}
		int masterId = lastMasterId;
		int streak = tracker.getTaskStreak();
		int base = PointsMath.basePoints(masterId);
		double avg = PointsMath.averagePointsPerTask(masterId);
		int nextBonus = PointsMath.nextBonusTask(streak);
		int big = PointsMath.nextBigMilestone(streak);
		List<TaskStats> stats = all();
		long activeMs = 0;
		int finished = 0;
		for (TaskStats t : stats)
		{
			activeMs += t.getActiveMs();
			finished += t.getTasksFinished();
		}
		double median = medianXpPerHour(stats);
		List<BlockRow> blocks = new ArrayList<>();
		if (masterId > 0 && median > 0)
		{
			Map<String, Integer> byKey = weightKeysCache.get(masterId);
			if (byKey == null)
			{
				byKey = new LinkedHashMap<>();
				for (Map.Entry<Integer, Integer> e : table.masterWeights(masterId).entrySet())
				{
					String name = table.taskName(e.getKey());
					TaskRecord rec = name == null ? null : dataset.find(name);
					if (rec != null)
					{
						byKey.merge(rec.getKey(), e.getValue(), Integer::sum);
					}
				}
				if (!byKey.isEmpty())
				{
					weightKeysCache.put(masterId, byKey);
				}
			}
			Map<String, Double> xph = new HashMap<>();
			for (TaskStats t : stats)
			{
				if (t.getKills() >= ADVICE_MIN_KILLS && t.hasRate())
				{
					xph.put(t.getKey(), t.xpPerHour());
				}
			}
			for (PointsMath.BlockCandidate c : PointsMath.blockCandidates(byKey, xph, median, 3))
			{
				if (c.getGainPct() < 2)
				{
					continue;
				}
				TaskStats st = statsFor(c.getKey());
				TaskRecord rec = dataset.find(c.getKey());
				String name = st != null && st.getName() != null ? st.getName() : rec != null ? rec.title() : c.getKey();
				blocks.add(new BlockRow(name, c.getShare(), c.getXpPerHour(), c.getGainPct()));
			}
		}
		List<String> tips = new ArrayList<>();
		TaskRecord rec = tracker.getRecord();
		if (rec != null && client.getGameState() == GameState.LOGGED_IN)
		{
			for (Map.Entry<String, Integer> e : LONGER_UNLOCKS.entrySet())
			{
				TaskRecord r = dataset.find(e.getKey());
				if (r != null && r.getKey().equals(rec.getKey()))
				{
					if (client.getVarbitValue(e.getValue()) == 0)
					{
						tips.add("Longer tasks of this are a Slayer reward unlock you have not bought.");
					}
					break;
				}
			}
			TaskStats st = current;
			if (st != null && st.hasRate() && median > 0)
			{
				double pct = st.xpPerHour() / median;
				if (pct >= 1.2)
				{
					tips.add("One of your better tasks: a bracelet of slaughter would stretch it.");
				}
				else if (pct <= 0.8)
				{
					tips.add("One of your weaker tasks: a bracelet of expeditious would get it over with.");
				}
			}
		}
		pointsSummary = new PointsSummary(masterId, com.slayersuite.task.GameTaskTable.masterName(masterId), tracker.getSlayerPoints(), streak, avg,
			nextBonus, base * PointsMath.milestoneMultiplier(nextBonus), big, base * PointsMath.milestoneMultiplier(big),
			finished > 0 ? activeMs / finished : 0, PointsMath.paybackTasks(PointsMath.SKIP_COST, avg), median, blocks, tips);
	}

	/** "Idle 9m, away 6m; best run idle 2m" for the current assignment, or "" when there is nothing to say. */
	public String assignmentDiagnostics()
	{
		if (assignmentKills < 10 || assignmentMs < 120_000L)
		{
			return "";
		}
		StringBuilder d = new StringBuilder();
		if (assignmentIdleMs >= 60_000L)
		{
			d.append("Idle ").append(TaskTracker.formatDuration(assignmentIdleMs));
		}
		if (assignmentAwayMs >= 60_000L)
		{
			d.append(d.length() > 0 ? ", away " : "Away ").append(TaskTracker.formatDuration(assignmentAwayMs));
		}
		TaskStats st = current;
		if (st != null && st.getBestKph() > 0 && st.getBestMs() > 0)
		{
			double kph = assignmentKills * 3_600_000.0 / assignmentMs;
			if (kph < st.getBestKph() * 0.9)
			{
				d.append(d.length() > 0 ? "; " : "").append("your best run was ").append(Math.round(st.getBestKph())).append("/h")
					.append(st.getBestIdleMs() + st.getBestAwayMs() < 60_000L ? " with under a minute idle" : " with " + TaskTracker.formatDuration(st.getBestIdleMs() + st.getBestAwayMs()) + " idle or away");
			}
		}
		int balls = trip.get().getBallsUsed();
		if (balls > 0)
		{
			d.append(d.length() > 0 ? "; " : "").append("cannon ").append(balls).append(" balls");
			if (trip.get().getBallsCost() > 0)
			{
				d.append(" (").append(TripMath.formatK(trip.get().getBallsCost())).append(")");
			}
			if (st != null && st.getCannonKph() > 0 && st.getPlainKph() > 0)
			{
				d.append(", ").append(Math.round(st.getCannonKph())).append("/h vs ").append(Math.round(st.getPlainKph())).append("/h without");
			}
		}
		return d.toString();
	}

	/** Puts the last task's one-line report on the system clipboard. */
	public boolean copyLastReport()
	{
		String r = lastReport;
		if (r == null || r.isEmpty())
		{
			return false;
		}
		try
		{
			java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(r), null);
			return true;
		}
		catch (RuntimeException e)
		{
			log.debug("clipboard unavailable", e);
			return false;
		}
	}
	private final AttackGuard guard;
	private final Provider<TripTracker> trip;

	@Getter
	private TaskStats current;
	@Getter
	private long sessionKills;
	@Getter
	private long sessionActiveMs;
	@Getter
	private long sessionXp;
	@Getter
	private long sessionGp;
	@Getter
	private String advice;
	/** The current assignment's totals, across logouts: kills, Slayer xp, loot gp, active time. */
	@Getter
	private long assignmentKills;
	@Getter
	private long assignmentXp;
	@Getter
	private long assignmentGp;
	@Getter
	private long assignmentMs;
	/** Of the assignment's active time: gaps of 20 s or more between kills, split by whether you were in a task area. */
	@Getter
	private long assignmentIdleMs;
	@Getter
	private long assignmentAwayMs;
	private static final long IDLE_GAP_MS = 20_000L;
	/** Drops from task monsters that have landed in the inventory this assignment: item id -> {quantity, value}. */
	private final Map<Integer, long[]> assignmentItems = new LinkedHashMap<>();
	/** Drops from this assignment's task kills that were never picked up: item id -> {quantity, value}. */
	private final Map<Integer, long[]> assignmentMissed = new LinkedHashMap<>();
	/** Drops the loot log saw that have not been picked up yet. */
	private final List<PickupMatcher.Pending> pending = new ArrayList<>();
	private Map<Integer, Integer> inventory = new HashMap<>();
	private boolean bankOpen;
	/** Loot from your kills lies on the ground for three minutes before it vanishes; a little slack on top. */
	private static final long PICKUP_WINDOW_MS = 190_000L;
	/** A drop and the task's kill count must move within this of each other to count as a task kill. */
	private static final long KILL_MATCH_MS = 4_000L;
	private static final int PICKUP_TILES = 10;
	private long lastKillCountedAt;
	/** Lifetime slaying totals on this profile and, per skill, where they stood at its last level-up. */
	private Ledger ledger;
	private final Map<Skill, Integer> levels = new EnumMap<>(Skill.class);
	private volatile int slayerXp = -1;
	private static final String LEDGER_KEY = "ledger";
	private static final int LOG_CAP = 250;
	private long lastKillAt;
	private int lastSlayerXp = -1;
	private long lastSaveAt;
	private boolean dirty;

	@Inject
	public TaskHistory(ConfigManager configManager, Gson gson, Client client, TaskTracker tracker, SlayerDataset dataset,
		SlayerSuiteConfig config, ItemManager itemManager, AttackGuard guard, Provider<TripTracker> trip, EventBus eventBus, com.slayersuite.task.GameTaskTable table)
	{
		this.table = table;
		this.configManager = configManager;
		this.gson = gson;
		this.client = client;
		this.tracker = tracker;
		this.dataset = dataset;
		this.config = config;
		this.itemManager = itemManager;
		this.eventBus = eventBus;
		this.guard = guard;
		this.trip = trip;
	}

	public void reset()
	{
		current = null;
		advice = null;
		sessionKills = sessionActiveMs = sessionXp = sessionGp = 0;
		lastKillAt = 0;
		lastSlayerXp = -1;
		dirty = false;
	}

	// ---------------------------------------------------------------- persistence

	private static String keyFor(String taskName, TaskRecord rec)
	{
		if (rec != null && rec.getKey() != null)
		{
			return rec.getKey();
		}
		return taskName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
	}

	private TaskStats load(String key)
	{
		try
		{
			String json = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, PREFIX + key);
			if (json != null && !json.isEmpty())
			{
				TaskStats s = gson.fromJson(json, TaskStats.class);
				if (s != null)
				{
					s.setKey(key);
					return s;
				}
			}
		}
		catch (RuntimeException e)
		{
			log.debug("history load failed for {}", key, e);
		}
		TaskStats s = new TaskStats();
		s.setKey(key);
		return s;
	}

	private void save(TaskStats stats)
	{
		if (stats == null || stats.getKey() == null || configManager.getRSProfileKey() == null)
		{
			return;
		}
		try
		{
			configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, PREFIX + stats.getKey(), gson.toJson(stats));
			lastSaveAt = System.currentTimeMillis();
			dirty = false;
		}
		catch (RuntimeException e)
		{
			log.debug("history save failed", e);
		}
	}

	/** Every task with stored history for this profile. */
	public List<TaskStats> all()
	{
		List<TaskStats> out = new ArrayList<>();
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return out;
		}
		List<String> keys;
		try
		{
			keys = configManager.getRSProfileConfigurationKeys(SlayerSuiteConfig.GROUP, profile, PREFIX);
		}
		catch (RuntimeException e)
		{
			return out;
		}
		if (keys == null)
		{
			return out;
		}
		for (String k : keys)
		{
			int i = k.lastIndexOf(PREFIX);
			String key = i >= 0 ? k.substring(i + PREFIX.length()) : k;
			if (key.isEmpty())
			{
				continue;
			}
			TaskStats s = load(key);
			if (s.getKills() > 0)
			{
				out.add(s);
			}
		}
		return out;
	}

	/** Stored stats for any task key (the current task includes this session), or null when nothing is recorded. */
	public TaskStats statsFor(String key)
	{
		if (key == null)
		{
			return null;
		}
		if (current != null && key.equals(current.getKey()))
		{
			return snapshot();
		}
		TaskStats s = load(key);
		return s.getKills() > 0 ? s : null;
	}

	/** The stored stats merged with what this session has measured so far. */
	public TaskStats snapshot()
	{
		if (current == null)
		{
			return null;
		}
		TaskStats s = current.copy();
		s.add(sessionKills, sessionActiveMs, sessionXp, sessionGp);
		TripTracker t = trip.get();
		if (t != null && t.getSessionKills() >= 5)
		{
			s.setFoodPerKill(TripMath.ema(s.getFoodPerKill(), t.foodPerKill(), 0.5));
			s.setDosesPerKill(TripMath.ema(s.getDosesPerKill(), t.dosesPerKill(), 0.5));
			s.setPrayerPerKill(TripMath.ema(s.getPrayerPerKill(), t.prayerPerKill(), 0.5));
		}
		return s;
	}

	private static final String ASSIGN_KEY = "assignment";
	private static final String ASSIGNS_KEY = "assignments";
	private static final int ASSIGNS_KEPT = 3;

	private static class Assignment
	{
		String key;
		long kills;
		long xp;
		long gp;
		long ms;
		long idleMs;
		long awayMs;
		/** {item id, quantity, value} per picked-up drop. */
		List<long[]> items;
		/** The same for drops left behind. */
		List<long[]> missed;
		long savedAt;
	}

	private Map<String, Assignment> loadAssignments()
	{
		Map<String, Assignment> out = new LinkedHashMap<>();
		try
		{
			String json = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, ASSIGNS_KEY);
			if (json != null)
			{
				Assignment[] arr = gson.fromJson(json, Assignment[].class);
				if (arr != null)
				{
					for (Assignment a : arr)
					{
						if (a != null && a.key != null)
						{
							out.put(a.key, a);
						}
					}
				}
			}
			else
			{
				// the single-slot format from before task storage was handled
				String one = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, ASSIGN_KEY);
				Assignment a = one == null ? null : gson.fromJson(one, Assignment.class);
				if (a != null && a.key != null)
				{
					out.put(a.key, a);
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// start empty
		}
		return out;
	}

	private static class Ledger
	{
		long kills;
		long activeMs;
		long lootValue;
		/** Picked-up drops, newest last: {item id, quantity, value, wall time}. */
		List<long[]> log = new ArrayList<>();
		/** Drops left on the floor, newest last, same shape. */
		List<long[]> missedLog = new ArrayList<>();
		long missedValue;
		/** Slayer reward points spent, and on what. */
		long skipPoints;
		long blockPoints;
		long otherPoints;
		int skips;
		int blocks;
		/** Mortimer tasks completed, as the game last reported it. */
		int mortimerTasks;
		/** Skill name -> {kills, activeMs, lootValue, wall time} when it last levelled. */
		Map<String, long[]> marks = new HashMap<>();
		/** Task keys referenced by log entries' fifth value. */
		List<String> taskKeys = new ArrayList<>();
		/** Every level reached since the plugin started watching, oldest first. */
		List<LevelRecord> levels = new ArrayList<>();
		/** Item id -> name, filled on the client thread as drops arrive, for the panel's tooltips. */
		Map<String, String> names = new HashMap<>();
	}

	/** One level reached, with everything slain and picked up on the way. */
	public static class LevelRecord
	{
		public String skill;
		public int level;
		public long at;
		public long kills;
		public long activeMs;
		public long lootValue;
		/** {item id, quantity, value}, best first. */
		public List<long[]> items = new ArrayList<>();
		public long missedValue;
		public List<long[]> missed = new ArrayList<>();
	}

	/** A read-only snapshot for the panel's Loot page. */
	@lombok.Value
	public static class LootSummary
	{
		/** -1 until the game has reported it this login. */
		int slayerXp;
		boolean sinceKnown;
		/** When "this level" counting started: the last Slayer level-up, or the day the plugin first ran. */
		long sinceAt;
		long sinceKills;
		long sinceMs;
		long sinceValue;
		List<LootItem> sinceItems;
		long sinceMissedValue;
		List<LootItem> sinceMissed;
		long lifeKills;
		long lifeMs;
		long lifeValue;
		List<LootItem> recentItems;
		long lifeMissedValue;
		List<LootItem> recentMissed;
		/** Slayer levels reached, newest first. */
		List<LevelRecord> slayerLevels;
		Map<Integer, String> names;
		long skipPoints;
		long blockPoints;
		long otherPoints;
		int skips;
		int blocks;
	}

	private static final int LEVELS_CAP = 120;

	/** Drops in the log after a moment, merged per item and sorted by value, at most n. */
	private List<LootItem> aggregate(Ledger l, long sinceTime, int n)
	{
		return aggregate(l.log, sinceTime, n);
	}

	private static long sumAfter(List<long[]> log, long sinceTime)
	{
		long v = 0;
		for (long[] en : log)
		{
			if (en.length >= 4 && en[3] > sinceTime)
			{
				v += en[2];
			}
		}
		return v;
	}

	/** Index of the current task's key in the ledger's list, adding it on first sight; -1 without a task. */
	private int taskIndex(Ledger l)
	{
		if (current == null || current.getKey() == null)
		{
			return -1;
		}
		int i = l.taskKeys.indexOf(current.getKey());
		if (i < 0)
		{
			l.taskKeys.add(current.getKey());
			i = l.taskKeys.size() - 1;
		}
		return i;
	}

	/** The most valuable drops picked up on a task over the whole log, best first. */
	public synchronized List<LootItem> lootForTask(String key, int n)
	{
		Ledger l = ledger();
		int idx = l.taskKeys.indexOf(key);
		if (idx < 0)
		{
			return new ArrayList<>();
		}
		List<long[]> mine = new ArrayList<>();
		for (long[] en : l.log)
		{
			if (en.length >= 5 && en[4] == idx)
			{
				mine.add(en);
			}
		}
		return aggregate(mine, 0, n);
	}

	/** Spot name -> {kills/h, gp/h, xp/h} for the spots of a task you have played enough at. */
	public Map<String, double[]> ownRates(String key)
	{
		Map<String, double[]> out = new LinkedHashMap<>();
		TaskStats st = key == null ? null : (current != null && key.equals(current.getKey()) ? snapshot() : statsFor(key));
		if (st == null || st.getSpots() == null)
		{
			return out;
		}
		for (Map.Entry<String, TaskStats.SpotStats> e : st.getSpots().entrySet())
		{
			if (e.getValue().hasRate())
			{
				out.put(e.getKey(), new double[]{e.getValue().killsPerHour(), e.getValue().gpPerHour(), e.getValue().xpPerHour()});
			}
		}
		return out;
	}

	private static List<LootItem> aggregate(List<long[]> log, long sinceTime, int n)
	{
		Map<Integer, long[]> agg = new LinkedHashMap<>();
		for (long[] en : log)
		{
			if (en.length >= 4 && en[3] > sinceTime)
			{
				long[] t = agg.computeIfAbsent((int) en[0], k -> new long[2]);
				t[0] += en[1];
				t[1] += en[2];
			}
		}
		List<LootItem> items = new ArrayList<>();
		for (Map.Entry<Integer, long[]> en : agg.entrySet())
		{
			items.add(new LootItem(en.getKey(), en.getValue()[0], en.getValue()[1]));
		}
		items.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		return items.size() > n ? new ArrayList<>(items.subList(0, n)) : items;
	}

	public synchronized LootSummary lootSummary()
	{
		Ledger l = ledger();
		long[] mark = l.marks.get(Skill.SLAYER.name());
		List<LevelRecord> slayer = new ArrayList<>();
		for (int i = l.levels.size() - 1; i >= 0; i--)
		{
			LevelRecord r = l.levels.get(i);
			if (Skill.SLAYER.name().equals(r.skill))
			{
				slayer.add(r);
			}
		}
		Map<Integer, String> names = new HashMap<>();
		for (Map.Entry<String, String> en : l.names.entrySet())
		{
			try
			{
				names.put(Integer.parseInt(en.getKey()), en.getValue());
			}
			catch (NumberFormatException ignored)
			{
				// skip
			}
		}
		return new LootSummary(slayerXp, mark != null, mark == null ? 0 : mark[3],
			mark == null ? 0 : l.kills - mark[0], mark == null ? 0 : l.activeMs - mark[1], mark == null ? 0 : l.lootValue - mark[2],
			mark == null ? new ArrayList<>() : aggregate(l, mark[3], 15),
			mark == null ? 0 : sumAfter(l.missedLog, mark[3]), mark == null ? new ArrayList<>() : aggregate(l.missedLog, mark[3], 10),
			l.kills, l.activeMs, l.lootValue, aggregate(l, 0, 15), l.missedValue, aggregate(l.missedLog, 0, 10), slayer, names,
			l.skipPoints, l.blockPoints, l.otherPoints, l.skips, l.blocks);
	}

	private synchronized Ledger ledger()
	{
		if (ledger == null)
		{
			if (configManager.getRSProfileKey() == null)
			{
				// no account yet (login screen): hand out a scratch ledger and never cache or save it,
				// or the real one would be overwritten with nothing after login
				return new Ledger();
			}
			ledger = new Ledger();
			try
			{
				String json = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, LEDGER_KEY);
				Ledger l = json == null ? null : gson.fromJson(json, Ledger.class);
				if (l != null)
				{
					if (l.log == null)
					{
						l.log = new ArrayList<>();
					}
					if (l.marks == null)
					{
						l.marks = new HashMap<>();
					}
					if (l.levels == null)
					{
						l.levels = new ArrayList<>();
					}
					if (l.names == null)
					{
						l.names = new HashMap<>();
					}
					if (l.missedLog == null)
					{
						l.missedLog = new ArrayList<>();
					}
					if (l.taskKeys == null)
					{
						l.taskKeys = new ArrayList<>();
					}
					ledger = l;
				}
			}
			catch (RuntimeException ignored)
			{
				// start fresh
			}
		}
		return ledger;
	}

	private synchronized void saveLedger()
	{
		if (ledger == null || configManager.getRSProfileKey() == null)
		{
			return;
		}
		try
		{
			configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, LEDGER_KEY, gson.toJson(ledger));
		}
		catch (RuntimeException ignored)
		{
			// config unavailable
		}
	}

	/** The card for a level-up: everything slain and picked up since that skill's previous level. */
	private void levelUp(Skill skill, int level, boolean test)
	{
		LevelReached event;
		synchronized (this)
		{
			event = buildLevelUp(skill, level, test);
		}
		eventBus.post(event);
	}

	private LevelReached buildLevelUp(Skill skill, int level, boolean test)
	{
		Ledger l = ledger();
		long[] mark = l.marks.get(skill.name());
		long now = System.currentTimeMillis();
		long kills;
		long active;
		long value;
		long missedValue;
		List<LootItem> items;
		List<LootItem> missed;
		boolean first = mark == null;
		if (test)
		{
			kills = assignmentKills > 0 ? assignmentKills : 120;
			active = assignmentMs > 60_000L ? assignmentMs : 95 * 60_000L;
			value = assignmentGp > 0 ? assignmentGp : 410_000L;
			items = topLoot(8);
			missed = topMissed(6);
			missedValue = 0;
			for (LootItem li : missed)
			{
				missedValue += li.getValue();
			}
			first = false;
		}
		else if (first)
		{
			kills = active = value = missedValue = 0;
			items = new ArrayList<>();
			missed = new ArrayList<>();
		}
		else
		{
			kills = l.kills - mark[0];
			active = l.activeMs - mark[1];
			value = l.lootValue - mark[2];
			items = aggregate(l, mark[3], 8);
			missedValue = sumAfter(l.missedLog, mark[3]);
			missed = aggregate(l.missedLog, mark[3], 6);
		}
		long xpToNext = 0;
		if (level < Experience.MAX_REAL_LEVEL && client.getGameState() == GameState.LOGGED_IN)
		{
			xpToNext = Math.max(0, Experience.getXpForLevel(level + 1) - client.getSkillExperience(skill));
		}
		if (!test)
		{
			LevelRecord rec = new LevelRecord();
			rec.skill = skill.name();
			rec.level = level;
			rec.at = now;
			rec.kills = kills;
			rec.activeMs = active;
			rec.lootValue = value;
			for (LootItem li : first ? new ArrayList<LootItem>() : aggregate(l, mark[3], 15))
			{
				rec.items.add(new long[]{li.getId(), li.getQuantity(), li.getValue()});
			}
			rec.missedValue = missedValue;
			for (LootItem li : first ? new ArrayList<LootItem>() : aggregate(l.missedLog, mark[3], 10))
			{
				rec.missed.add(new long[]{li.getId(), li.getQuantity(), li.getValue()});
			}
			l.levels.add(rec);
			if (l.levels.size() > LEVELS_CAP)
			{
				l.levels.subList(0, l.levels.size() - LEVELS_CAP).clear();
			}
			l.marks.put(skill.name(), new long[]{l.kills, l.activeMs, l.lootValue, now});
			saveLedger();
		}
		return new LevelReached(skill, level, first, kills, active, value, xpToNext, items, missedValue, missed, test);
	}

	/** With no Slayer mark yet, counting for "this level" starts now rather than at the next level-up. */
	private synchronized void seedSlayerMark()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		Ledger l = ledger();
		if (!l.marks.containsKey(Skill.SLAYER.name()))
		{
			l.marks.put(Skill.SLAYER.name(), new long[]{l.kills, l.activeMs, l.lootValue, System.currentTimeMillis()});
			saveLedger();
		}
	}

	/** The ::ss level command and the panel button: a Slayer level-up card with sample numbers. */
	public void testLevelUp()
	{
		int lvl = client.getGameState() == GameState.LOGGED_IN ? client.getRealSkillLevel(Skill.SLAYER) : 80;
		levelUp(Skill.SLAYER, Math.min(99, lvl + 1), true);
	}

	/** One picked-up drop line for the completion card. */
	@lombok.Value
	public static class LootItem
	{
		int id;
		long quantity;
		long value;
	}

	/** The most valuable picked-up drops of this assignment, best first. */
	public List<LootItem> topLoot(int n)
	{
		List<LootItem> out = new ArrayList<>();
		synchronized (assignmentItems)
		{
			for (Map.Entry<Integer, long[]> e : assignmentItems.entrySet())
			{
				out.add(new LootItem(e.getKey(), e.getValue()[0], e.getValue()[1]));
			}
		}
		out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()) != 0 ? Long.compare(b.getValue(), a.getValue()) : Long.compare(b.getQuantity(), a.getQuantity()));
		return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
	}

	/** The most valuable drops of this assignment that were left on the floor, best first. */
	public List<LootItem> topMissed(int n)
	{
		List<LootItem> out = new ArrayList<>();
		synchronized (assignmentItems)
		{
			for (Map.Entry<Integer, long[]> e : assignmentMissed.entrySet())
			{
				out.add(new LootItem(e.getKey(), e.getValue()[0], e.getValue()[1]));
			}
		}
		out.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
	}

	/** Value of everything this assignment's task kills dropped that was never picked up. */
	public long getAssignmentMissedValue()
	{
		long v = 0;
		synchronized (assignmentItems)
		{
			for (long[] t : assignmentMissed.values())
			{
				v += t[1];
			}
		}
		return v;
	}

	private void clearLoot()
	{
		synchronized (assignmentItems)
		{
			assignmentItems.clear();
			assignmentMissed.clear();
		}
		// drops from the last task may still be on the floor; they keep their task and match on pickup
	}

	/** Pending drops past their window: the task counted the kill, nothing reached the inventory, so they were left behind. */
	private synchronized void expirePending(long t)
	{
		List<PickupMatcher.Pending> gone = new ArrayList<>();
		PickupMatcher.expire(pending, t, PICKUP_WINDOW_MS, gone);
		if (gone.isEmpty())
		{
			return;
		}
		Ledger l = ledger();
		int cur = taskIndex(l);
		for (PickupMatcher.Pending pd : gone)
		{
			long value = pd.quantity * pd.priceEach;
			int task = pd.task >= 0 ? pd.task : cur;
			if (task == cur && cur >= 0)
			{
				synchronized (assignmentItems)
				{
					long[] tally = assignmentMissed.computeIfAbsent(pd.id, k -> new long[2]);
					tally[0] += pd.quantity;
					tally[1] += value;
				}
			}
			l.missedValue += value;
			l.missedLog.add(new long[]{pd.id, pd.quantity, value, t, task});
			if (l.missedLog.size() > LOG_CAP)
			{
				l.missedLog.subList(0, l.missedLog.size() - LOG_CAP).clear();
			}
			dirty = true;
		}
	}

	/** Records a drop as picked up. */
	private synchronized void takeLoot(int id, long quantity, long value, long t)
	{
		takeLoot(id, quantity, value, t, -1);
	}

	/** Credits a pickup to the task the drop came from (task = ledger index), or to the current task when unknown. */
	private synchronized void takeLoot(int id, long quantity, long value, long t, int task)
	{
		Ledger l = ledger();
		int cur = taskIndex(l);
		if (task < 0)
		{
			task = cur;
		}
		boolean ours = task == cur && cur >= 0;
		if (ours)
		{
			synchronized (assignmentItems)
			{
				long[] tally = assignmentItems.computeIfAbsent(id, k -> new long[2]);
				tally[0] += quantity;
				tally[1] += value;
			}
		}
		l.lootValue += value;
		l.log.add(new long[]{id, quantity, value, t, task});
		com.slayersuite.data.LocationRecord here = tracker.getCurrentLocation();
		if (ours && current != null && here != null)
		{
			current.spot(here.getName()).loot += value;
		}
		if (l.log.size() > LOG_CAP)
		{
			l.log.subList(0, l.log.size() - LOG_CAP).clear();
		}
		dirty = true;
	}

	private void saveAssignment()
	{
		if (current == null || configManager.getRSProfileKey() == null)
		{
			return;
		}
		Assignment a = new Assignment();
		a.key = current.getKey();
		a.kills = assignmentKills;
		a.xp = assignmentXp;
		a.gp = assignmentGp;
		a.ms = assignmentMs;
		a.idleMs = assignmentIdleMs;
		a.awayMs = assignmentAwayMs;
		a.items = new ArrayList<>();
		a.missed = new ArrayList<>();
		synchronized (assignmentItems)
		{
			for (Map.Entry<Integer, long[]> e : assignmentItems.entrySet())
			{
				a.items.add(new long[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
			}
			for (Map.Entry<Integer, long[]> e : assignmentMissed.entrySet())
			{
				a.missed.add(new long[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
			}
		}
		a.savedAt = System.currentTimeMillis();
		try
		{
			Map<String, Assignment> all = loadAssignments();
			all.put(a.key, a);
			List<Assignment> list = new ArrayList<>(all.values());
			list.sort((x, y) -> Long.compare(y.savedAt, x.savedAt));
			if (list.size() > ASSIGNS_KEPT)
			{
				list = new ArrayList<>(list.subList(0, ASSIGNS_KEPT));
			}
			configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, ASSIGNS_KEY, gson.toJson(list.toArray(new Assignment[0])));
		}
		catch (RuntimeException ignored)
		{
			// config unavailable
		}
		saveLedger();
	}

	/** Continue the assignment's totals after a relog when the saved ones belong to this task. */
	private void restoreAssignment(String key)
	{
		assignmentKills = assignmentXp = assignmentGp = assignmentMs = assignmentIdleMs = assignmentAwayMs = 0;
		clearLoot();
		try
		{
			Assignment a = key == null ? null : loadAssignments().get(key);
			if (a != null)
			{
				assignmentKills = a.kills;
				assignmentXp = a.xp;
				assignmentGp = a.gp;
				assignmentMs = a.ms;
				assignmentIdleMs = a.idleMs;
				assignmentAwayMs = a.awayMs;
				if (a.items != null)
				{
					synchronized (assignmentItems)
					{
						for (long[] it : a.items)
						{
							if (it != null && it.length == 3)
							{
								assignmentItems.put((int) it[0], new long[]{it[1], it[2]});
							}
						}
					}
				}
				if (a.missed != null)
				{
					synchronized (assignmentItems)
					{
						for (long[] it : a.missed)
						{
							if (it != null && it.length == 3)
							{
								assignmentMissed.put((int) it[0], new long[]{it[1], it[2]});
							}
						}
					}
				}
			}
		}
		catch (RuntimeException ignored)
		{
			// start from zero
		}
	}

	@Subscribe
	public void onAssignmentStarted(AssignmentStarted e)
	{
		assignmentKills = assignmentXp = assignmentGp = assignmentMs = assignmentIdleMs = assignmentAwayMs = 0;
		clearLoot();
		if (current != null)
		{
			current.setTasksStarted(current.getTasksStarted() + 1);
			dirty = true;
		}
		saveAssignment();
	}

	private void flushCurrent()
	{
		if (current == null)
		{
			return;
		}
		TaskStats merged = snapshot();
		save(merged);
		saveAssignment();
		current = null;
		sessionKills = sessionActiveMs = sessionXp = sessionGp = 0;
		lastKillAt = 0;
	}

	// ---------------------------------------------------------------- events

	@Subscribe
	public void onTaskChanged(TaskChanged e)
	{
		if (!config.historyEnabled())
		{
			return;
		}
		flushCurrent();
		advice = null;
		if (e.getNewName() == null)
		{
			tracker.setPriorKph(0);
			return;
		}
		TaskRecord rec = tracker.getRecord();
		String key = keyFor(e.getNewName(), rec);
		current = load(key);
		current.setName(rec != null ? rec.title() : AlertEngine.pretty(e.getNewName()));
		restoreAssignment(key);
		lastSlayerXp = client.getGameState() == GameState.LOGGED_IN ? client.getSkillExperience(Skill.SLAYER) : -1;
		dirty = true;
		tracker.setPriorKph(current.killsPerHour());
		advice = computeAdvice(current);
		if (config.historyChat() && client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=00a0ff>Slayer Suite:</col> " + current.getName() + ". " + summary(current) + (advice != null ? " " + advice : ""), null);
		}
	}

	@Subscribe
	public void onTaskFinished(TaskFinished e)
	{
		if (e.isTest())
		{
			return;
		}
		if (current != null)
		{
			current.setTasksFinished(current.getTasksFinished() + 1);
			if (e.getPoints() > 0)
			{
				current.setPoints(current.getPoints() + e.getPoints());
				current.setPointSamples(current.getPointSamples() + 1);
			}
			dirty = true;
			long runMs = Math.max(assignmentMs, sessionActiveMs);
			long runKills = Math.max(assignmentKills, sessionKills);
			if (runKills >= 20 && runMs >= 120_000L)
			{
				double kph = runKills * 3_600_000.0 / runMs;
				if (kph > current.getBestKph())
				{
					current.setBestKph(kph);
					current.setBestMs(runMs);
					current.setBestIdleMs(assignmentIdleMs);
					current.setBestAwayMs(assignmentAwayMs);
				}
				if (trip.get().getBallsUsed() > 0)
				{
					current.setCannonKph(TripMath.ema(current.getCannonKph(), kph, 0.5));
				}
				else
				{
					current.setPlainKph(TripMath.ema(current.getPlainKph(), kph, 0.5));
				}
			}
			StringBuilder r = new StringBuilder(current.getName() == null ? AlertEngine.pretty(e.getName()) : current.getName());
			r.append(": ").append(Math.max(assignmentKills, sessionKills)).append(" kills");
			long ms = Math.max(assignmentMs, sessionActiveMs);
			if (ms > 60_000L)
			{
				r.append(" in ").append(TaskTracker.formatDuration(ms));
			}
			long xp = Math.max(assignmentXp, sessionXp);
			long gp = Math.max(assignmentGp, sessionGp);
			if (xp > 0)
			{
				r.append(", ").append(TripMath.formatK(xp)).append(" xp");
			}
			if (gp > 0)
			{
				r.append(", ").append(TripMath.formatK(gp)).append(" gp");
			}
			List<LootItem> best = topLoot(1);
			if (!best.isEmpty())
			{
				String name = ledger().names.get(String.valueOf(best.get(0).getId()));
				if (name != null)
				{
					r.append(" (best drop ").append(name).append(", ").append(TripMath.formatK(best.get(0).getValue())).append(")");
				}
			}
			if (e.getPoints() > 0)
			{
				r.append(", +").append(e.getPoints()).append(" pts");
			}
			if (e.getStreak() > 0)
			{
				r.append(", streak ").append(e.getStreak());
			}
			String diag = assignmentDiagnostics();
			if (!diag.isEmpty())
			{
				r.append(". ").append(diag);
			}
			lastReport = r.toString();
		}
	}

	@Subscribe
	public void onSuperiorSpawned(com.slayersuite.task.SuperiorSpawned e)
	{
		if (current != null)
		{
			current.setSuperiors(current.getSuperiors() + 1);
			dirty = true;
		}
	}

	@Subscribe
	public void onPointsSpent(PointsSpent e)
	{
		synchronized (this)
		{
			Ledger l = ledger();
			switch (e.getKind())
			{
				case SKIP:
					l.skipPoints += e.getAmount();
					l.skips++;
					break;
				case BLOCK:
					l.blockPoints += e.getAmount();
					l.blocks++;
					break;
				default:
					l.otherPoints += e.getAmount();
			}
			saveLedger();
		}
		if (e.getKind() != PointsSpent.Kind.OTHER && current != null && e.getTaskName() != null)
		{
			if (e.getKind() == PointsSpent.Kind.SKIP)
			{
				current.setSkips(current.getSkips() + 1);
			}
			else
			{
				current.setBlocks(current.getBlocks() + 1);
			}
			current.setPointsSpent(current.getPointsSpent() + e.getAmount());
			dirty = true;
		}
		if (config.historyChat() && client.getGameState() == GameState.LOGGED_IN)
		{
			String what = e.getKind() == PointsSpent.Kind.SKIP ? "Skipped " + AlertEngine.pretty(e.getTaskName())
				: e.getKind() == PointsSpent.Kind.BLOCK ? "Blocked " + AlertEngine.pretty(e.getTaskName()) : "Spent at the Slayer shop";
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=00a0ff>Slayer Suite:</col> " + what + ": -" + e.getAmount() + " points, " + String.format("%,d", e.getBalance()) + " left.", null);
		}
	}

	@Subscribe
	public void onTaskStored(TaskStored e)
	{
		if (config.historyChat() && client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=00a0ff>Slayer Suite:</col> " + AlertEngine.pretty(e.getName()) + " stored with " + e.getRemaining() + " left; its kills, xp and loot are kept for when you take it back.", null);
		}
	}

	@Subscribe
	public void onKillCounted(KillCounted e)
	{
		if (current == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (lastKillAt > 0 && now - lastKillAt < BREAK_GAP_MS)
		{
			sessionActiveMs += now - lastKillAt;
		}
		com.slayersuite.data.LocationRecord here = tracker.getCurrentLocation();
		TaskStats.SpotStats spot = here == null ? null : current.spot(here.getName());
		if (spot != null)
		{
			spot.kills += e.getDelta();
		}
		if (lastKillAt > 0 && now - lastKillAt < BREAK_GAP_MS)
		{
			long gap = now - lastKillAt;
			assignmentMs += gap;
			if (spot != null)
			{
				spot.activeMs += gap;
			}
			if (gap >= IDLE_GAP_MS)
			{
				if (tracker.getCurrentLocation() != null)
				{
					assignmentIdleMs += gap;
				}
				else
				{
					assignmentAwayMs += gap;
				}
			}
			synchronized (this)
			{
				ledger().activeMs += gap;
			}
		}
		lastKillAt = now;
		lastKillCountedAt = now;
		for (PickupMatcher.Pending pd : pending)
		{
			if (!pd.killConfirmed && now - pd.at <= KILL_MATCH_MS)
			{
				pd.killConfirmed = true;
			}
		}
		sessionKills += e.getDelta();
		assignmentKills += e.getDelta();
		synchronized (this)
		{
			ledger().kills += e.getDelta();
		}
		dirty = true;
		if (sessionKills >= 3)
		{
			advice = null;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		Integer prev = levels.put(e.getSkill(), e.getLevel());
		if (prev != null && e.getLevel() > prev && client.getGameState() == GameState.LOGGED_IN)
		{
			levelUp(e.getSkill(), e.getLevel(), false);
		}
		else if (prev == null && e.getSkill() == Skill.SLAYER && client.getGameState() == GameState.LOGGED_IN)
		{
			seedSlayerMark();
		}
		if (e.getSkill() != Skill.SLAYER)
		{
			return;
		}
		int xp = e.getXp();
		slayerXp = xp;
		if (current != null && lastSlayerXp >= 0 && xp > lastSlayerXp)
		{
			sessionXp += xp - lastSlayerXp;
			assignmentXp += xp - lastSlayerXp;
			com.slayersuite.data.LocationRecord here = tracker.getCurrentLocation();
			if (here != null)
			{
				current.spot(here.getName()).xp += xp - lastSlayerXp;
			}
			dirty = true;
		}
		lastSlayerXp = xp;
	}

	@Subscribe
	public void onNpcLootReceived(NpcLootReceived e)
	{
		if (current == null)
		{
			return;
		}
		NPC npc = e.getNpc();
		if (npc == null || !guard.isTaskTarget(npc))
		{
			return;
		}
		long gp = 0;
		long now = System.currentTimeMillis();
		WorldPoint where = npc.getWorldLocation();
		boolean killConfirmed = now - lastKillCountedAt <= KILL_MATCH_MS;
		for (ItemStack s : e.getItems())
		{
			int id = itemManager.canonicalize(s.getId());
			long price = id == ItemID.COINS ? 1 : itemManager.getItemPrice(id);
			gp += price * s.getQuantity();
			// counted as loot only once it reaches the inventory, near where the monster died, and
			// only if the task's kill counter moved for this death (see onItemContainerChanged)
			PickupMatcher.Pending pd = new PickupMatcher.Pending(id, s.getQuantity(), price, now,
				where == null ? -1 : where.getX(), where == null ? -1 : where.getY(), where == null ? -1 : where.getPlane(), killConfirmed);
			Ledger l = ledger();
			pd.task = taskIndex(l);
			String name = l.names.get(String.valueOf(id));
			if (name == null)
			{
				try
				{
					name = itemManager.getItemComposition(id).getName();
					l.names.put(String.valueOf(id), name);
				}
				catch (RuntimeException ignored)
				{
					// name unknown
				}
			}
			// a carried herb sack or seed box swallows these on pickup without touching the inventory
			if (name != null)
			{
				String n = name.toLowerCase(Locale.ROOT);
				boolean herbSack = inventory.containsKey(ItemID.SLAYER_HERB_SACK) || inventory.containsKey(ItemID.SLAYER_HERB_SACK_OPEN);
				boolean seedBox = inventory.containsKey(ItemID.SEED_BOX) || inventory.containsKey(ItemID.SEED_BOX_OPEN);
				pd.autoStored = (herbSack && n.startsWith("grimy ")) || (seedBox && n.endsWith(" seed"));
			}
			pending.add(pd);
		}
		sessionGp += gp;
		assignmentGp += gp;
		dirty = true;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN || e.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			bankOpen = true;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN || e.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			bankOpen = false;
		}
	}

	/** Inventory gains that match a recent drop from a task monster become picked-up loot. */
	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() != InventoryID.INV)
		{
			return;
		}
		ItemContainer inv = e.getItemContainer();
		Map<Integer, Integer> now = new HashMap<>();
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it != null && it.getId() > 0 && it.getQuantity() > 0)
				{
					now.merge(itemManager.canonicalize(it.getId()), it.getQuantity(), Integer::sum);
				}
			}
		}
		Map<Integer, Integer> before = inventory;
		inventory = now;
		long t = System.currentTimeMillis();
		expirePending(t);
		if (bankOpen || pending.isEmpty())
		{
			return;
		}
		WorldPoint me = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation();
		int px = me == null ? -1 : me.getX();
		int py = me == null ? -1 : me.getY();
		int pplane = me == null ? -1 : me.getPlane();
		// the last task's drops still on the floor match too: a drop is credited to the task it came from
		for (long[] take : PickupMatcher.match(before, now, pending, t, PICKUP_WINDOW_MS, px, py, pplane, PICKUP_TILES))
		{
			takeLoot((int) take[0], take[1], take[2], t, (int) take[3]);
		}
	}

	/** Each tick: retire drops past their window, and credit herb sack / seed box pickups when you stand on the drop. */
	private void tickPending()
	{
		if (pending.isEmpty())
		{
			return;
		}
		long t = System.currentTimeMillis();
		expirePending(t);
		WorldPoint me = client.getLocalPlayer() == null ? null : client.getLocalPlayer().getWorldLocation();
		if (me == null)
		{
			return;
		}
		for (Iterator<PickupMatcher.Pending> it = pending.iterator(); it.hasNext(); )
		{
			PickupMatcher.Pending pd = it.next();
			if (pd.autoStored && pd.killConfirmed && pd.x >= 0 && pd.near(me.getX(), me.getY(), me.getPlane(), 0))
			{
				takeLoot(pd.id, pd.quantity, pd.quantity * pd.priceEach, t);
				it.remove();
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			lastSlayerXp = -1;
		}
		else if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			saveLedger();
			ledger = null;
			levels.clear();
			slayerXp = -1;
			inventory = new HashMap<>();
			pending.clear();
			if (current != null && dirty)
			{
				save(snapshot());
			}
			lastKillAt = 0;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		tickPending();
		if (++pointsTick % 25 == 1)
		{
			computePointsSummary();
		}
		if (current != null && dirty && System.currentTimeMillis() - lastSaveAt > SAVE_EVERY_MS)
		{
			save(snapshot());
			saveAssignment();
		}
		int mort = tracker.getMortimerTasks();
		if (mort > 0)
		{
			Ledger l = ledger();
			boolean changed;
			synchronized (l)
			{
				changed = l.mortimerTasks != mort;
				if (changed)
				{
					l.mortimerTasks = mort;
				}
			}
			if (changed)
			{
				saveLedger();
			}
		}
	}

	/** Mortimer tasks completed: this session's chat count, else the last one remembered; 0 when never seen. */
	public int mortimerTasks()
	{
		int live = tracker.getMortimerTasks();
		if (live > 0)
		{
			return live;
		}
		Ledger l = ledger();
		synchronized (l)
		{
			return l.mortimerTasks;
		}
	}

	// ---------------------------------------------------------------- advice

	/** "92/h, 41k xp/h, 160k gp/h over 3 tasks", or "no history yet". */
	public String summary(TaskStats s)
	{
		if (s == null || !s.hasRate())
		{
			return "No history for this task yet.";
		}
		return "Your history: " + Math.round(s.killsPerHour()) + " kills/h, " + TripMath.formatK(Math.round(s.xpPerHour())) + " xp/h, "
			+ TripMath.formatK(Math.round(s.gpPerHour())) + " gp/h " + (s.getTasksFinished() == 0 ? "from " + s.getKills() + " kills so far." : "over " + s.getTasksFinished() + (s.getTasksFinished() == 1 ? " task." : " tasks."));
	}

	/** Compares this task's measured xp/h with the median of every task you have history for. */
	String computeAdvice(TaskStats s)
	{
		if (s == null || s.getKills() < ADVICE_MIN_KILLS || !s.hasRate())
		{
			return null;
		}
		List<Double> rates = new ArrayList<>();
		for (TaskStats t : all())
		{
			if (t.getKills() >= ADVICE_MIN_KILLS && t.hasRate())
			{
				rates.add(t.xpPerHour());
			}
		}
		if (rates.size() < 3)
		{
			return null;
		}
		rates.sort(Double::compare);
		double median = rates.get(rates.size() / 2);
		if (median <= 0)
		{
			return null;
		}
		double pct = s.xpPerHour() / median * 100.0;
		if (pct < config.skipBelowPercent())
		{
			double chance = tracker.rollChance();
			String roll = chance > 0 ? " It is " + Math.round(chance * 100) + "% of this master's rolls" + (chance >= 0.06 ? ": worth blocking." : ": skipping is enough.") : "";
			int have = tracker.getSlayerPoints();
			PointsSummary ps = pointsSummary;
			String payback = ps != null && ps.getSkipPaybackTasks() > 0 ? ", about " + String.format("%.1f", ps.getSkipPaybackTasks()) + " tasks of points at " + ps.getMaster() : "";
			String cost = have > 0 ? " A skip costs 30 points" + payback + " (you have " + String.format("%,d", have) + ")" + (chance >= 0.06 ? ", a block 100." : ".") : "";
			return "Low for you: " + Math.round(pct) + "% of your median xp/h. Consider skipping." + roll + cost;
		}
		if (pct > 150)
		{
			return "One of your best tasks (" + Math.round(pct) + "% of your median xp/h).";
		}
		return null;
	}
}
