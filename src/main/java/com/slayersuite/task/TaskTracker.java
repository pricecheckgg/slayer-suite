package com.slayersuite.task;

import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.Skill;
import net.runelite.api.NPCComposition;
import net.runelite.client.config.ConfigManager;
import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.MonsterRecord;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import java.util.regex.Matcher;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.slayer.SlayerPluginService;

/**
 * Wraps the core Slayer plugin's service: current task, amounts, live targets,
 * plus what the service does not give us: kill timestamps for an ETA, the
 * dataset record, the dataset location you are standing in, superior spawns.
 */
@Slf4j
@Singleton
public class TaskTracker
{
	private static final String SUPERIOR_MESSAGE = "A superior foe has appeared...";
	private static final Pattern TASK_COMPLETE = Pattern.compile("^You've completed .*(task|Slayer master).*|^You have completed your task.*|^You've completed your task.*");
	private static final Pattern POINTS_AWARDED = Pattern.compile("received ([\\d,]+) points?");
	private static final Pattern MORTIMER_TASKS = Pattern.compile("completed ([\\d,]+) Mortimer tasks?");
	/** The game's own assignment lines: gem check, new task, partner task. We trust these over the core plugin. */
	private static final Pattern[] ASSIGNED = {
		Pattern.compile("^You're assigned to kill (?:the )?(?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<loc>.+?))?; only (?<n>[\\d,]+) more to go\\.?$"),
		Pattern.compile("^Your new task is to kill (?<n>[\\d,]+) (?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<loc>.+?))?\\.?$"),
		Pattern.compile("^You've been assigned to kill (?<n>[\\d,]+) (?<name>.+?)(?: (?:in|on|south of) (?:the )?(?<loc>.+?))?\\.?$"),
		Pattern.compile("^You have received a new Slayer assignment.*?: (?:the )?(?<name>.+?) \\((?<n>[\\d,]+)\\)\\.?$"),
		Pattern.compile("^(?:Your|The) (?:new )?(?:Slayer )?(?:task|assignment) (?:is|from .+? is) to kill (?<n>[\\d,]+) (?<name>.+?)\\.?$"),
	};
	private static final long RATE_WINDOW_MS = 15 * 60_000L;
	private static final long BREAK_GAP_MS = 4 * 60_000L;

	private final Client client;
	private final SlayerPluginService slayerService;
	private final SlayerDataset dataset;
	private final EventBus eventBus;
	private final ConfigManager configManager;
	private final GameTaskTable table;
	/** The task as the game's own tables name it; null when none or unavailable. */
	@Getter
	private GameTaskTable.GameTask gameTask;
	@Getter
	private boolean fromGame;

	/**
	 * When the game's chat says one thing and the core plugin another (new slayer masters store
	 * tasks in values the core plugin may not read), this holds what the game said.
	 */
	@Getter
	private boolean fromChat;
	private String chatName;
	private int chatRemaining;
	private int chatInitial;
	private long chatSince;
	private int lastSlayerXp = -1;
	private List<NPC> nameTargets = Collections.emptyList();

	// Var sniffer: what changed in the last few seconds, dumped around slayer messages.
	private final java.util.ArrayDeque<String> recentVars = new java.util.ArrayDeque<>();
	private final Map<Integer, Integer> countCandidates = new LinkedHashMap<>();
	private final Map<Integer, Integer> varbitCandidates = new LinkedHashMap<>();
	private static final int VARBIT_SCAN_MAX = 20000;
	private int discoveredCountVarp = -1;
	private int discoveredCountVarbit = -1;
	/** True when the standard kill-count value matched the game's own count at assignment: use it directly. */
	private boolean countFromStandardVarp;

	@Getter
	private String taskName;
	@Getter
	private String taskLocation;
	@Getter
	private int remaining;
	@Getter
	private int initial;
	@Getter
	private TaskRecord record;
	@Getter
	private LocationRecord currentLocation;
	@Getter
	private long taskSeenAt;
	@Getter
	private int killsThisSession;
	@Getter
	private long lastKillAt;
	@Getter
	private long lastSuperiorAt;
	/** Last time you attacked a task monster or one attacked you. */
	@Getter
	private long lastTargetContactAt;
	/** Current Slayer reward points and task streak, read from the client each tick. */
	@Getter
	private int slayerPoints;
	@Getter
	private int taskStreak;
	private int pointsAtTaskStart = -1;
	/** Spend detection: the balance last tick, and a drop waiting to be explained by a task change. */
	private int prevPoints = -1;
	private int prevStreak = -1;
	private int pendingSpend;
	private int lastDrop;
	private long pendingSpendAt;
	private static final long SPEND_MATCH_MS = 10_000L;
	@Getter
	private boolean storageUnlocked;
	/** Points when this session first read them, for "+N this session". */
	@Getter
	private int pointsAtSessionStart = -1;
	/** From "You've completed N Mortimer tasks"; -1 until seen. */
	@Getter
	private int mortimerTasks = -1;
	private int lastPointsAwarded;
	private long lastPointsAwardedAt;
	/** Our own completion detection, independent of the core plugin clearing the task. */
	private int lastCount = -1;
	private long loggedInAt;
	private static final long SETTLE_MS = 5_000L;
	private long pendingFinishAt;
	private String pendingFinishName;
	private String finishedName;
	private long finishedAt;
	/** Last task name we held, kept after it clears so a late finish message can still be attributed. */
	private String lastTaskName;

	private final Deque<Long> killTimes = new ArrayDeque<>();
	/** Kills per hour from stored history, used for the ETA until this session has its own data. */
	@Getter
	@Setter
	private double priorKph;
	private long completionMessageAt;
	private int regionCheckTick;

	@Inject
	public TaskTracker(Client client, SlayerPluginService slayerService, SlayerDataset dataset, EventBus eventBus, ConfigManager configManager, GameTaskTable table)
	{
		this.table = table;
		this.client = client;
		this.slayerService = slayerService;
		this.dataset = dataset;
		this.eventBus = eventBus;
		this.configManager = configManager;
		String v = configManager.getConfiguration(SlayerSuiteConfig.GROUP, "discoveredCountVarp");
		String b = configManager.getConfiguration(SlayerSuiteConfig.GROUP, "discoveredCountVarbit");
		try
		{
			discoveredCountVarp = v == null ? -1 : Integer.parseInt(v);
			discoveredCountVarbit = b == null ? -1 : Integer.parseInt(b);
		}
		catch (NumberFormatException ignored)
		{
			// none discovered yet
		}
	}

	/** Live targets: the core plugin's list when it knows the same task, else NPCs whose names match the task. */
	public List<NPC> getTargets()
	{
		if (fromChat || (fromGame && !nameTargets.isEmpty()))
		{
			return nameTargets;
		}
		List<NPC> t = slayerService.getTargets();
		return t == null ? Collections.emptyList() : t;
	}

	private volatile double cachedRollChance = -1;

	/** Share of the current master's rolls that land on this task, 0..1, or -1. Safe from any thread (cached each tick). */
	public double rollChance()
	{
		return cachedRollChance;
	}

	private void refreshRollChance()
	{
		GameTaskTable.GameTask gt = gameTask;
		cachedRollChance = gt == null ? -1 : table.rollChance(gt.getMasterId(), gt.getTaskId());
	}

	private int savedInitial(String name)
	{
		try
		{
			String v = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, "initial." + com.slayersuite.data.ItemNames.key(name));
			return v == null ? 0 : Integer.parseInt(v);
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private void saveInitial(String name, int initial)
	{
		if (configManager.getRSProfileKey() != null)
		{
			configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, "initial." + com.slayersuite.data.ItemNames.key(name), Integer.toString(initial));
		}
	}

	public void reset()
	{
		taskName = null;
		taskLocation = null;
		remaining = 0;
		initial = 0;
		record = null;
		currentLocation = null;
		killTimes.clear();
		killsThisSession = 0;
		lastKillAt = 0;
		lastTargetContactAt = 0;
		chatName = null;
		fromChat = false;
		nameTargets = Collections.emptyList();
	}

	public boolean hasTask()
	{
		return taskName != null && !taskName.isEmpty();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			killTimes.clear();
			// the client zeroes every varp here: that is not a task reaching zero
			pendingFinishAt = 0;
			pendingFinishName = null;
			lastCount = -1;
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			loggedInAt = System.currentTimeMillis();
		}
	}

	/** True once the game has had a few seconds after login to send the real task values. */
	private boolean settled()
	{
		return client.getGameState() == GameState.LOGGED_IN && loggedInAt > 0 && System.currentTimeMillis() - loggedInAt > SETTLE_MS;
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			slayerPoints = client.getVarbitValue(VarbitID.SLAYER_POINTS);
			prevStreak = taskStreak;
			taskStreak = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
			storageUnlocked = client.getVarbitValue(VarbitID.SLAYER_UNLOCK_STORAGE) > 0;
			if (pointsAtSessionStart < 0 && slayerPoints > 0)
			{
				pointsAtSessionStart = slayerPoints;
			}
			if (prevPoints > 0 && slayerPoints < prevPoints)
			{
				// points left the balance: a skip or block shows up as a task change within seconds, anything else is the shop
				lastDrop = prevPoints - slayerPoints;
				pendingSpend += lastDrop;
				pendingSpendAt = System.currentTimeMillis();
			}
			prevPoints = slayerPoints;
			if (pendingSpend > 0 && System.currentTimeMillis() - pendingSpendAt > SPEND_MATCH_MS)
			{
				eventBus.post(new PointsSpent(PointsSpent.Kind.OTHER, pendingSpend, null, slayerPoints));
				pendingSpend = 0;
			}
		}
		String name = slayerService.getTask();
		if (name != null && name.isEmpty())
		{
			name = null;
		}
		int rem = slayerService.getRemainingAmount();
		int ini = slayerService.getInitialAmount();
		String loc = slayerService.getTaskLocation();

		// 1. The game's own tables: exact name, count and area, every master, no gem check.
		if (regionCheckTick % 2 == 0 || gameTask == null)
		{
			gameTask = table.currentTask();
			if (regionCheckTick % 20 == 0 || cachedRollChance < 0)
			{
				refreshRollChance();
			}
		}
		if (gameTask != null)
		{
			fromGame = true;
			name = gameTask.getName();
			rem = gameTask.getRemaining();
			if (gameTask.getArea() != null && !gameTask.getArea().isEmpty())
			{
				loc = gameTask.getArea();
			}
			int saved = savedInitial(name);
			ini = Math.max(rem, Math.max(saved, chatName != null && sameTask(chatName, name) ? chatInitial : 0));
			if (ini > saved)
			{
				saveInitial(name, ini);
			}
			chatName = null;
			fromChat = false;
			if (regionCheckTick % 2 == 0)
			{
				List<NPC> coreTargets = slayerService.getTargets();
				nameTargets = coreTargets != null && !coreTargets.isEmpty() && name.equalsIgnoreCase(slayerService.getTask()) ? Collections.emptyList() : findTargetsByName();
			}
		}
		else
		{
			fromGame = false;
		}
		if (gameTask == null && chatName == null && client.getGameState() == GameState.LOGGED_IN && regionCheckTick % 10 == 0)
		{
			restoreChatTask();
		}
		// 2. The game's own words win over the core plugin when they disagree.
		if (gameTask == null && chatName != null)
		{
			boolean coreAgrees = name != null && sameTask(name, chatName) && (rem == chatRemaining || Math.abs(rem - chatRemaining) <= 1);
			if (coreAgrees && System.currentTimeMillis() - chatSince > 3_000L)
			{
				chatName = null; // the core plugin caught up; back to it
				fromChat = false;
				log.debug("Slayer Suite: core plugin now agrees ({} x{}), using it", name, rem);
			}
			else
			{
				fromChat = true;
				name = chatName;
				rem = chatRemaining;
				ini = Math.max(chatInitial, chatRemaining);
				if (regionCheckTick % 2 == 0)
				{
					nameTargets = findTargetsByName();
				}
			}
		}
		else
		{
			fromChat = false;
		}

		// kill count varp: the task is done the moment it reaches zero, whatever the core plugin does next
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			int count = fromGame ? client.getVarpValue(VarPlayerID.SLAYER_COUNT)
				: fromChat && countFromStandardVarp ? client.getVarpValue(VarPlayerID.SLAYER_COUNT)
				: fromChat && discoveredCountVarp >= 0 ? client.getVarpValue(discoveredCountVarp)
				: fromChat && discoveredCountVarbit >= 0 ? client.getVarbitValue(discoveredCountVarbit)
				: fromChat ? -1 : client.getVarpValue(VarPlayerID.SLAYER_COUNT);
			if (settled() && count >= 0 && taskName != null && lastCount > 0 && count == 0 && pendingFinishAt == 0 && !recentlyFinished(taskName))
			{
				pendingFinishAt = System.currentTimeMillis(); // give the points message two ticks to arrive
				pendingFinishName = taskName;
			}
			if (count >= 0)
			{
				lastCount = count;
			}
			if (fromChat && count >= 0 && count < chatRemaining)
			{
				int delta = chatRemaining - count;
				chatRemaining = count; // the game's own counter is counting for us
				long now = System.currentTimeMillis();
				if (lastKillAt > 0 && now - lastKillAt > BREAK_GAP_MS)
				{
					killTimes.clear();
				}
				killTimes.addLast(now);
				lastKillAt = now;
				lastTargetContactAt = now;
				killsThisSession += delta;
				remaining = count;
				eventBus.post(new KillCounted(count, delta));
				saveChatTask();
			}
		}
		if (pendingFinishAt > 0 && System.currentTimeMillis() - pendingFinishAt >= 1300L)
		{
			pendingFinishAt = 0;
			fireFinished(pendingFinishName != null ? pendingFinishName : (taskName != null ? taskName : lastTaskName));
			pendingFinishName = null;
			chatName = null;
			fromChat = false;
			saveChatTask();
		}

		boolean nameChanged = !equalsIgnoreCase(name, taskName);
		if (nameChanged)
		{
			String old = taskName;
			taskName = name;
			taskLocation = loc;
			record = name == null ? null : dataset.find(name);
			if (name != null && fromGame)
			{
				int seen = savedInitial(name);
				if (old != null)
				{
					// a fresh assignment straight after another: its size is the count we see now
					saveInitial(name, rem);
					ini = rem;
					eventBus.post(new AssignmentStarted(name, rem));
				}
				else
				{
					// first sight this session (login mid-task): keep the largest size we know
					ini = Math.max(seen, rem);
					if (seen == 0)
					{
						eventBus.post(new AssignmentStarted(name, rem));
					}
					if (ini > seen)
					{
						saveInitial(name, ini);
					}
				}
			}
			killTimes.clear();
			killsThisSession = 0;
			taskSeenAt = System.currentTimeMillis();
			currentLocation = null;
			boolean countZero = client.getGameState() == GameState.LOGGED_IN && client.getVarpValue(VarPlayerID.SLAYER_COUNT) == 0;
			boolean finishedNow = settled() && name == null && old != null && (remaining <= 0 || countZero || System.currentTimeMillis() - completionMessageAt < 10_000L);
			if (old != null && !finishedNow && pendingSpend > 0 && System.currentTimeMillis() - pendingSpendAt <= SPEND_MATCH_MS)
			{
				// the task went away right after points were taken: the last drop is the skip (30) or block (100);
				// anything taken before it in the same visit was the shop
				PointsSpent.Kind kind = lastDrop == 100 ? PointsSpent.Kind.BLOCK : lastDrop == 30 ? PointsSpent.Kind.SKIP : PointsSpent.Kind.OTHER;
				log.debug("Slayer Suite: {} {} for {} points", kind, old, lastDrop);
				if (pendingSpend - lastDrop > 0)
				{
					eventBus.post(new PointsSpent(PointsSpent.Kind.OTHER, pendingSpend - lastDrop, null, slayerPoints));
				}
				eventBus.post(new PointsSpent(kind, lastDrop, kind == PointsSpent.Kind.OTHER ? null : old, slayerPoints));
				pendingSpend = 0;
			}
			else if (old != null && name == null && !finishedNow && remaining > 0 && storageUnlocked && prevStreak >= 0 && taskStreak == prevStreak)
			{
				// gone with kills left, nothing paid, streak intact: into task storage
				log.debug("Slayer Suite: task {} stored with {} left", old, remaining);
				eventBus.post(new TaskStored(old, remaining));
			}
			if (finishedNow)
			{
				fireFinished(old);
			}
			if (name != null)
			{
				lastTaskName = name;
			}
			pendingFinishAt = 0;
			pendingFinishName = null;
			pointsAtTaskStart = name == null ? -1 : slayerPoints;
			log.debug("Slayer Suite: task {} -> {}", old, name);
			remaining = rem;
			initial = ini;
			eventBus.post(new TaskChanged(old, name));
			if (name != null && record == null)
			{
				log.debug("Slayer Suite: no dataset record for task '{}'", name);
			}
		}
		else if (name != null)
		{
			if (rem < remaining && remaining > 0)
			{
				int delta = remaining - rem;
				long now = System.currentTimeMillis();
				if (lastKillAt > 0 && now - lastKillAt > BREAK_GAP_MS)
				{
					killTimes.clear();
				}
				killTimes.addLast(now);
				lastKillAt = now;
				lastTargetContactAt = now;
				killsThisSession += delta;
				remaining = rem;
				eventBus.post(new KillCounted(rem, delta));
			}
			else
			{
				if (rem > remaining && fromGame)
				{
					// the count went up on the same task: a new assignment of the same monster
					saveInitial(name, rem);
					ini = rem;
					killTimes.clear();
					killsThisSession = 0;
					taskSeenAt = System.currentTimeMillis();
					eventBus.post(new AssignmentStarted(name, rem));
				}
				remaining = rem;
			}
			if (ini != initial)
			{
				initial = ini;
			}
			taskLocation = loc;
		}

		trimKills(System.currentTimeMillis());

		if (++regionCheckTick % 5 == 0)
		{
			currentLocation = locateFromRegions();
		}
		if (name != null && regionCheckTick % 2 == 0)
		{
			noteTargetContact();
		}
	}

	private void noteTargetContact()
	{
		net.runelite.api.Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		List<NPC> targets = getTargets();
		if (targets.isEmpty())
		{
			return;
		}
		net.runelite.api.Actor mine = me.getInteracting();
		if (mine instanceof NPC && targets.contains(mine))
		{
			lastTargetContactAt = System.currentTimeMillis();
			return;
		}
		for (NPC npc : targets)
		{
			if (npc != null && npc.getInteracting() == me)
			{
				lastTargetContactAt = System.currentTimeMillis();
				return;
			}
		}
	}

	private static boolean sameTask(String a, String b)
	{
		String x = com.slayersuite.data.ItemNames.key(a);
		String y = com.slayersuite.data.ItemNames.key(b);
		if (x.equals(y))
		{
			return true;
		}
		String xs = x.endsWith("s") ? x.substring(0, x.length() - 1) : x;
		String ys = y.endsWith("s") ? y.substring(0, y.length() - 1) : y;
		return xs.equals(ys);
	}

	/** NPCs around you whose name matches the task's monsters when the core plugin's list cannot be trusted. */
	private List<NPC> findTargetsByName()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null || taskName == null)
		{
			return Collections.emptyList();
		}
		List<NPC> out = new ArrayList<>();
		for (NPC npc : wv.npcs())
		{
			if (npc == null)
			{
				continue;
			}
			NPCComposition comp = npc.getTransformedComposition();
			String n = comp == null ? npc.getName() : comp.getName();
			if (n == null)
			{
				continue;
			}
			if (record != null)
			{
				if (record.monsterNamed(n) != null)
				{
					out.add(npc);
					continue;
				}
				for (String c : record.getCoreTargetNames())
				{
					if (n.equalsIgnoreCase(c))
					{
						out.add(npc);
						break;
					}
				}
			}
			else if (sameTask(n, taskName))
			{
				out.add(npc);
			}
		}
		return out;
	}

	private void onAssignmentChat(String name, int count, String loc)
	{
		if (gameTask != null && sameTask(gameTask.getName(), name))
		{
			if (count > savedInitial(name))
			{
				saveInitial(name, count);
			}
			log.debug("Slayer Suite: game tables already name the task ({} x{}); chat agrees", gameTask.getName(), gameTask.getRemaining());
			return;
		}
		String core = slayerService.getTask();
		int coreRem = slayerService.getRemainingAmount();
		boolean agrees = core != null && !core.isEmpty() && sameTask(core, name) && Math.abs(coreRem - count) <= 1;
		log.debug("Slayer Suite: game says task {} x{}{} ; core plugin says {} x{}", name, count, loc == null ? "" : " in " + loc, core, coreRem);
		dumpRecentVars("assignment " + name + " x" + count);
		noteCountCandidates(count);
		if (agrees)
		{
			chatName = null;
			return;
		}
		boolean newTask = chatName == null || !sameTask(chatName, name);
		chatName = name;
		chatRemaining = count;
		if (newTask)
		{
			String savedName = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskName");
			String savedInitial = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskInitial");
			// same task as before a relaunch: keep its original size instead of restarting the bar at the current count
			if (savedName != null && sameTask(savedName, name) && savedInitial != null)
			{
				try
				{
					chatInitial = Math.max(count, Integer.parseInt(savedInitial));
				}
				catch (NumberFormatException ignored)
				{
					chatInitial = count;
				}
			}
			else
			{
				chatInitial = count;
			}
			chatSince = System.currentTimeMillis();
		}
		countFromStandardVarp = client.getGameState() == GameState.LOGGED_IN && client.getVarpValue(VarPlayerID.SLAYER_COUNT) == count;
		if (countFromStandardVarp)
		{
			log.debug("Slayer Suite: the standard kill-count value matches ({}); counting from it", count);
		}
		if (loc != null && !loc.isEmpty())
		{
			taskLocation = loc;
		}
		saveChatTask();
	}

	private void saveChatTask()
	{
		if (configManager.getRSProfileKey() == null)
		{
			return;
		}
		try
		{
			if (chatName == null)
			{
				configManager.unsetRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskName");
				configManager.unsetRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskInitial");
				configManager.unsetRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskRemaining");
			}
			else
			{
				configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskName", chatName);
				configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskInitial", Integer.toString(chatInitial));
				configManager.setRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskRemaining", Integer.toString(chatRemaining));
			}
		}
		catch (RuntimeException ignored)
		{
			// config unavailable
		}
	}

	/** After a relaunch: if the kill-count value is still above zero and we saved a task, pick it up again without a gem check. */
	private void restoreChatTask()
	{
		String savedName = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskName");
		if (savedName == null || chatName != null)
		{
			return;
		}
		int count = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (count <= 0)
		{
			return;
		}
		String core = slayerService.getTask();
		if (core != null && !core.isEmpty())
		{
			return; // the core plugin knows the task; nothing to restore
		}
		try
		{
			chatName = savedName;
			chatRemaining = count;
			String savedInitial = configManager.getRSProfileConfiguration(SlayerSuiteConfig.GROUP, "chatTaskInitial");
			chatInitial = savedInitial == null ? count : Math.max(count, Integer.parseInt(savedInitial));
			chatSince = System.currentTimeMillis();
			countFromStandardVarp = true;
			log.debug("Slayer Suite: restored task {} x{} from the last session", chatName, count);
		}
		catch (RuntimeException ignored)
		{
			chatName = null;
		}
	}

	/** Slayer xp drops stand in for kill counting while the count var is unknown. */
	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		if (e.getSkill() != Skill.SLAYER)
		{
			return;
		}
		int xp = e.getXp();
		int delta = lastSlayerXp >= 0 ? xp - lastSlayerXp : 0;
		lastSlayerXp = xp;
		if (!fromChat || delta <= 0 || countFromStandardVarp || discoveredCountVarp >= 0 || discoveredCountVarbit >= 0 || chatRemaining <= 0)
		{
			return;
		}
		double perKill = 0;
		if (record != null)
		{
			MonsterRecord m = record.primaryMonster();
			if (m != null && m.getSlayerXp() != null && m.getSlayerXp() > 0)
			{
				perKill = m.getSlayerXp();
			}
		}
		int kills = perKill > 0 ? (int) Math.max(1, Math.round(delta / perKill)) : 1;
		chatRemaining = Math.max(0, chatRemaining - kills);
		remaining = chatRemaining;
		long now = System.currentTimeMillis();
		if (lastKillAt > 0 && now - lastKillAt > BREAK_GAP_MS)
		{
			killTimes.clear();
		}
		killTimes.addLast(now);
		lastKillAt = now;
		lastTargetContactAt = now;
		killsThisSession += kills;
		eventBus.post(new KillCounted(chatRemaining, kills));
		if (chatRemaining == 0 && pendingFinishAt == 0 && !recentlyFinished(taskName))
		{
			pendingFinishAt = now;
		}
	}

	private void noteCountCandidates(int count)
	{
		countCandidates.clear();
		int[] varps = client.getVarps();
		if (varps != null)
		{
			for (int i = 0; i < varps.length; i++)
			{
				if (varps[i] == count && i != VarPlayerID.SLAYER_COUNT)
				{
					countCandidates.put(i, count);
				}
			}
		}
		if (!countCandidates.isEmpty())
		{
			log.debug("Slayer Suite: varps currently equal to the task count {}: {}", count, countCandidates.keySet());
		}
		// Varbits are packed inside varps; scan them once per assignment message.
		varbitCandidates.clear();
		for (int id = 0; id < VARBIT_SCAN_MAX; id++)
		{
			try
			{
				if (client.getVarbitValue(id) == count)
				{
					varbitCandidates.put(id, count);
				}
			}
			catch (RuntimeException ignored)
			{
				// not a varbit id
			}
		}
		if (!varbitCandidates.isEmpty())
		{
			log.debug("Slayer Suite: varbits currently equal to the task count {}: {}", count, varbitCandidates.keySet());
		}
		try
		{
			log.debug("Slayer Suite: core-watched slayer vars: varp394={} varp395={} vb4067={} vb15776={} vb15777={} vb15778={}",
				client.getVarpValue(394), client.getVarpValue(395), client.getVarbitValue(4067), client.getVarbitValue(15776), client.getVarbitValue(15777), client.getVarbitValue(15778));
		}
		catch (RuntimeException ignored)
		{
			// older client without those ids
		}
	}

	private void dumpRecentVars(String why)
	{
		if (!recentVars.isEmpty())
		{
			log.debug("Slayer Suite: var changes in the last seconds before [{}]: {}", why, String.join(" | ", recentVars));
		}
	}

	/** For ::ss vars. */
	public String varReport()
	{
		return "task from " + (fromGame ? "game tables (" + gameTask + ")" : fromChat ? "chat" : "core plugin") + "; discovered count varp " + discoveredCountVarp + ", varbit " + discoveredCountVarbit
			+ "; varp candidates " + countCandidates.keySet() + "; varbit candidates " + varbitCandidates.keySet() + "; recent " + String.join(" | ", recentVars);
	}

	private boolean recentlyFinished(String name)
	{
		return finishedName != null && finishedName.equalsIgnoreCase(name) && System.currentTimeMillis() - finishedAt < 60_000L;
	}

	/** Posts TaskFinished once per task, with the points from the chat message or the points delta. */
	private void fireFinished(String name)
	{
		if (name == null || recentlyFinished(name))
		{
			return;
		}
		finishedName = name;
		finishedAt = System.currentTimeMillis();
		int awarded = finishedAt - lastPointsAwardedAt < 15_000L ? lastPointsAwarded
			: (pointsAtTaskStart >= 0 && slayerPoints > pointsAtTaskStart ? slayerPoints - pointsAtTaskStart : 0);
		log.info("Slayer Suite: task finished {} (+{} points, streak {})", name, awarded, taskStreak);
		eventBus.post(new TaskFinished(name, awarded, taskStreak));
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		long now = System.currentTimeMillis();
		recentVars.addLast((now / 1000 % 1000) + "s varp" + e.getVarpId() + (e.getVarbitId() >= 0 ? "/vb" + e.getVarbitId() : "") + "=" + e.getValue());
		while (recentVars.size() > 40)
		{
			recentVars.pollFirst();
		}
		if (settled() && e.getVarpId() == VarPlayerID.SLAYER_COUNT && !fromChat && taskName != null && lastCount > 0 && e.getValue() == 0 && pendingFinishAt == 0 && !recentlyFinished(taskName))
		{
			pendingFinishAt = now;
		}
		// A candidate varbit that steps down by one while you fight the task is the real kill counter.
		if (fromChat && discoveredCountVarbit < 0 && discoveredCountVarp < 0 && e.getVarbitId() >= 0 && varbitCandidates.containsKey(e.getVarbitId()))
		{
			int before = varbitCandidates.get(e.getVarbitId());
			if (e.getValue() == before - 1 && now - lastTargetContactAt < 10_000L)
			{
				discoveredCountVarbit = e.getVarbitId();
				configManager.setConfiguration(SlayerSuiteConfig.GROUP, "discoveredCountVarbit", Integer.toString(discoveredCountVarbit));
				log.debug("Slayer Suite: kill counter found: varbit {}", discoveredCountVarbit);
			}
			else
			{
				varbitCandidates.put(e.getVarbitId(), e.getValue());
			}
		}
		// A candidate varp that steps down by one while you fight the task is the real kill counter.
		if (fromChat && discoveredCountVarp < 0 && e.getVarbitId() < 0 && countCandidates.containsKey(e.getVarpId()))
		{
			int before = countCandidates.get(e.getVarpId());
			if (e.getValue() == before - 1 && now - lastTargetContactAt < 10_000L)
			{
				discoveredCountVarp = e.getVarpId();
				configManager.setConfiguration(SlayerSuiteConfig.GROUP, "discoveredCountVarp", Integer.toString(discoveredCountVarp));
				log.debug("Slayer Suite: kill counter found: varp {}", discoveredCountVarp);
			}
			else
			{
				countCandidates.put(e.getVarpId(), e.getValue());
			}
		}
	}

	private LocationRecord locateFromRegions()
	{
		if (record == null)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		int[] regions = wv == null ? null : wv.getMapRegions();
		return dataset.locationFor(record, regions == null ? new int[0] : regions, wv == null ? -1 : wv.getPlane());
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = e.getMessage();
		if (msg == null)
		{
			return;
		}
		String plain = msg.replaceAll("<[^>]*>", "").trim();
		Matcher mort = MORTIMER_TASKS.matcher(plain);
		if (mort.find())
		{
			try
			{
				mortimerTasks = Integer.parseInt(mort.group(1).replace(",", ""));
			}
			catch (NumberFormatException ignored)
			{
				// leave as is
			}
		}
		for (Pattern a : ASSIGNED)
		{
			Matcher m = a.matcher(plain);
			if (m.find())
			{
				try
				{
					String loc = null;
					try
					{
						loc = m.group("loc");
					}
					catch (IllegalArgumentException ignored)
					{
						// pattern without a location group
					}
					onAssignmentChat(m.group("name").trim(), Integer.parseInt(m.group("n").replace(",", "")), loc);
				}
				catch (RuntimeException ignored)
				{
					// odd message shape; ignore
				}
				return;
			}
		}
		if (plain.startsWith(SUPERIOR_MESSAGE))
		{
			lastSuperiorAt = System.currentTimeMillis();
			eventBus.post(new SuperiorSpawned(taskName));
		}
		else if (TASK_COMPLETE.matcher(plain).find())
		{
			completionMessageAt = System.currentTimeMillis();
			Matcher pm = POINTS_AWARDED.matcher(plain);
			if (pm.find())
			{
				try
				{
					lastPointsAwarded = Integer.parseInt(pm.group(1).replace(",", ""));
					lastPointsAwardedAt = completionMessageAt;
				}
				catch (NumberFormatException ignored)
				{
					// leave the delta fallback to it
				}
			}
			dumpRecentVars("completion");
			pendingFinishAt = 0;
			pendingFinishName = null;
			fireFinished(taskName != null ? taskName : lastTaskName);
			chatName = null;
			fromChat = false;
			saveChatTask();
		}
	}

	private void trimKills(long now)
	{
		while (!killTimes.isEmpty() && now - killTimes.peekFirst() > RATE_WINDOW_MS)
		{
			killTimes.pollFirst();
		}
	}

	/** Kills per hour over the recent window; 0 when there is not enough data. */
	public double killsPerHour()
	{
		if (killTimes.size() < 2)
		{
			return 0;
		}
		long first = killTimes.peekFirst();
		long last = killTimes.peekLast();
		long span = Math.max(last - first, 1);
		// Count the gap since the last kill too, so the rate decays when you stop killing.
		long sinceLast = System.currentTimeMillis() - last;
		long total = span + Math.min(sinceLast, BREAK_GAP_MS);
		return (killTimes.size() - 1) * 3_600_000.0 / total;
	}

	/** Milliseconds until the task is done at the current rate; -1 when unknown. */
	public long etaMillis()
	{
		double kph = effectiveKph();
		if (kph <= 0 || remaining <= 0)
		{
			return -1;
		}
		return (long) (remaining / kph * 3_600_000.0);
	}

	/** This session's rate, or the historical one while this session has too few kills. */
	public double effectiveKph()
	{
		double kph = killsPerHour();
		return kph > 0 ? kph : priorKph;
	}

	public boolean isEtaFromHistory()
	{
		return killsPerHour() <= 0 && priorKph > 0;
	}

	public long timeOnTaskMillis()
	{
		return taskSeenAt == 0 ? 0 : System.currentTimeMillis() - taskSeenAt;
	}

	/** Progress 0..1 of the current assignment (kills done / initial). */
	public double progress()
	{
		if (initial <= 0)
		{
			return 0;
		}
		return Math.max(0, Math.min(1.0, (initial - remaining) / (double) initial));
	}

	public boolean isInTaskArea()
	{
		return currentLocation != null;
	}

	private static boolean equalsIgnoreCase(String a, String b)
	{
		return a == null ? b == null : a.equalsIgnoreCase(b);
	}

	public static String formatDuration(long ms)
	{
		if (ms < 0)
		{
			return "--";
		}
		long s = ms / 1000;
		long h = s / 3600;
		long m = (s % 3600) / 60;
		if (h > 0)
		{
			return h + "h " + m + "m";
		}
		if (m > 0)
		{
			return m + "m";
		}
		return (s % 60) + "s";
	}
}
