package com.slayersuite.alerts;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.guard.AttackGuard;
import com.slayersuite.readiness.Missing;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.sounds.SoundPlayer;
import com.slayersuite.task.KillCounted;
import com.slayersuite.task.SuperiorSpawned;
import com.slayersuite.task.TaskChanged;
import com.slayersuite.task.TaskFinished;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.SuppliesLow;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
import net.runelite.api.events.InteractingChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.Notifier;
import net.runelite.client.config.Notification;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.NotificationFired;
import net.runelite.client.util.HotkeyListener;

/**
 * One alert engine: evaluates every source each game tick, keeps one active
 * alert per kind, de-duplicates, re-notifies unresolved sticky alerts, and
 * fans out to the banner, the RuneLite notifier, the chat and the sounds.
 */
@Slf4j
@Singleton
public class AlertEngine
{
	private static final int VENOM_THRESHOLD = 1_000_000;
	private static final Skill[] BOOST_SKILLS = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC};

	private final Client client;
	private final SlayerSuiteConfig config;
	private final TaskTracker tracker;
	private final ReadinessChecker readiness;
	private final AttackGuard guard;
	private final Notifier notifier;
	private final SoundPlayer sounds;
	private final javax.inject.Provider<com.slayersuite.task.SlayerMode> mode;
	private final javax.inject.Provider<com.slayersuite.history.TaskHistory> history;
	/** The vanish time of the floor drop last warned about, so each is called once. */
	private long floorWarnedFor;

	private final Map<AlertKind, Alert> active = new EnumMap<>(AlertKind.class);
	private final Map<Skill, Integer> peakBoost = new EnumMap<>(Skill.class);
	private final Map<Skill, Integer> realLevels = new EnumMap<>(Skill.class);

	@Getter
	private boolean paused;
	@Getter
	private long pausedUntil;
	private long lastCombatAt;
	private long lastStreakWarnAt;
	private long lastMovedAt;
	private WorldPoint lastTile;
	private boolean wasInArea;
	private boolean nearlyDoneFired;
	private long lastOffTaskAt;

	@Getter
	private final HotkeyListener pauseHotkey;

	@Inject
	public AlertEngine(Client client, SlayerSuiteConfig config, TaskTracker tracker, ReadinessChecker readiness, AttackGuard guard, Notifier notifier, SoundPlayer sounds, javax.inject.Provider<com.slayersuite.task.SlayerMode> mode, javax.inject.Provider<com.slayersuite.history.TaskHistory> history)
	{
		this.mode = mode;
		this.history = history;
		this.client = client;
		this.config = config;
		this.tracker = tracker;
		this.readiness = readiness;
		this.guard = guard;
		this.notifier = notifier;
		this.sounds = sounds;
		this.pauseHotkey = new HotkeyListener(config::pauseHotkey)
		{
			@Override
			public void hotkeyPressed()
			{
				togglePause();
			}
		};
	}

	public void reset()
	{
		active.clear();
		peakBoost.clear();
		paused = false;
		pausedUntil = 0;
		lastCombatAt = System.currentTimeMillis();
		nearlyDoneFired = false;
	}

	public void togglePause()
	{
		if (paused)
		{
			paused = false;
			pausedUntil = 0;
		}
		else
		{
			paused = true;
			pausedUntil = System.currentTimeMillis() + config.pauseMinutes() * 60_000L;
			active.clear();
		}
	}

	/** The alert that owns the banner right now, or null. */
	public Alert top()
	{
		Alert best = null;
		for (Alert a : active.values())
		{
			if (best == null || a.getKind().getPriority() > best.getKind().getPriority())
			{
				best = a;
			}
		}
		return best;
	}

	public int activeCount()
	{
		return active.size();
	}

	public List<Alert> activeAlerts()
	{
		List<Alert> out = new ArrayList<>(active.values());
		out.sort((a, b) -> Integer.compare(b.getKind().getPriority(), a.getKind().getPriority()));
		return out;
	}

	// ---------------------------------------------------------------- raise / clear

	/** A marked drop of yours has half a minute left on the floor. */
	private void checkFloor(long now)
	{
		if (!config.alertFloorLoot() || !config.floorLoot())
		{
			return;
		}
		com.slayersuite.history.TaskHistory h = history.get();
		long value = h.getFloorValue();
		long soonest = h.getFloorSoonestAt();
		if (value <= 0 || soonest <= 0)
		{
			return;
		}
		long left = soonest - now;
		if (left > 0 && left <= com.slayersuite.history.FloorLootMath.URGENT_MS && floorWarnedFor != soonest)
		{
			floorWarnedFor = soonest;
			raise(AlertKind.FLOOR_LOOT, com.slayersuite.trip.TripMath.formatK(value) + " gp of yours on the floor vanishes in " + (left / 1000) + "s");
		}
	}

	private void raise(AlertKind kind, String message)
	{
		raise(kind, message, kind.getSeverity(), true);
	}

	private void raise(AlertKind kind, String message, Severity severity, boolean notify)
	{
		if (!config.alertsEnabled() || paused)
		{
			return;
		}
		long now = System.currentTimeMillis();
		Alert existing = active.get(kind);
		if (existing != null && !kind.isSticky())
		{
			// an event (task done, superior, cannon...) happening again is a new event: deliver it again
			active.remove(kind);
			existing = null;
		}
		if (existing != null)
		{
			existing.setMessage(message);
			if (severity.ordinal() > existing.getSeverity().ordinal())
			{
				existing.setSeverity(severity);
				deliver(existing, notify);
				existing.setLastNotifiedAt(now);
			}
			return;
		}
		Alert alert = new Alert(kind, message, severity, now);
		active.put(kind, alert);
		deliver(alert, notify);
	}

	private void clear(AlertKind kind)
	{
		active.remove(kind);
	}

	private void deliver(Alert alert, boolean notify)
	{
		if (notify)
		{
			Notification n;
			switch (alert.getSeverity())
			{
				case CRITICAL:
					n = config.notifyCritical();
					break;
				case WARNING:
					n = config.notifyWarning();
					break;
				default:
					n = config.notifyInfo();
			}
			try
			{
				notifier.notify(n, "Slayer: " + alert.getMessage());
			}
			catch (RuntimeException e)
			{
				log.debug("notify failed", e);
			}
		}
		if (config.alertChat() && client.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ff5050>Slayer Suite:</col> " + alert.getMessage(), null);
		}
		int vol = config.soundVolume();
		if (alert.getKind() == AlertKind.TASK_DONE)
		{
			if (config.soundTaskDone())
			{
				sounds.play(SoundPlayer.Sound.TASK_DONE, vol);
			}
		}
		else if (alert.getKind() == AlertKind.SUPERIOR)
		{
			if (config.soundSuperior())
			{
				sounds.play(SoundPlayer.Sound.SUPERIOR, vol);
			}
		}
		else if (alert.getSeverity() == Severity.CRITICAL && config.soundCritical())
		{
			sounds.play(SoundPlayer.Sound.ALERT, vol);
		}
	}

	// ---------------------------------------------------------------- per-tick evaluation

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			active.clear();
			realLevels.clear();
			lastCombatAt = System.currentTimeMillis();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		long now = System.currentTimeMillis();
		if (!config.alertsEnabled())
		{
			active.clear();
			return;
		}
		if (paused && now > pausedUntil)
		{
			paused = false;
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}

		// expire transients
		active.values().removeIf(a -> a.expired(now));

		if (mode.get().active())
		{
			checkHitpoints();
			checkPrayer();
			checkPoison();
			checkBoosts();
			checkCombat(me, now);
		}
		else
		{
			clear(AlertKind.LOW_HP);
			clear(AlertKind.LOW_PRAYER);
			clear(AlertKind.POISON);
			clear(AlertKind.VENOM);
			clear(AlertKind.OUT_OF_COMBAT);
		}
		checkBank();
		checkFloor(now);

		// re-notify unresolved sticky alerts
		for (Alert a : active.values())
		{
			if (a.getKind().isSticky() && now - a.getLastNotifiedAt() >= config.renotifySeconds() * 1000L)
			{
				a.setLastNotifiedAt(now);
				deliver(a, true);
			}
		}
	}

	private void checkHitpoints()
	{
		int cur = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int max = client.getRealSkillLevel(Skill.HITPOINTS);
		if (max > 0 && cur * 100 <= config.hpPercent() * max)
		{
			raise(AlertKind.LOW_HP, "Hitpoints " + cur + "/" + max);
		}
		else
		{
			clear(AlertKind.LOW_HP);
		}
	}

	private void checkPrayer()
	{
		int cur = client.getBoostedSkillLevel(Skill.PRAYER);
		boolean relevant = readiness.hasGroup("prayer_restore") || cur < client.getRealSkillLevel(Skill.PRAYER);
		if (relevant && cur <= config.prayerPoints())
		{
			raise(AlertKind.LOW_PRAYER, "Prayer " + cur);
		}
		else
		{
			clear(AlertKind.LOW_PRAYER);
		}
	}

	private void checkPoison()
	{
		int v = client.getVarpValue(VarPlayerID.POISON);
		if (v >= VENOM_THRESHOLD)
		{
			clear(AlertKind.POISON);
			raise(AlertKind.VENOM, readiness.hasGroup("antivenom") ? "Venomed: drink anti-venom" : "Venomed and no anti-venom!");
		}
		else if (v > 0)
		{
			clear(AlertKind.VENOM);
			boolean cure = readiness.hasGroup("antipoison");
			raise(AlertKind.POISON, cure ? "Poisoned: drink antipoison" : "Poisoned and no antipoison!", cure ? Severity.WARNING : Severity.CRITICAL, true);
		}
		else
		{
			clear(AlertKind.POISON);
			clear(AlertKind.VENOM);
		}
	}

	private void checkBoosts()
	{
		for (Skill s : BOOST_SKILLS)
		{
			int boost = client.getBoostedSkillLevel(s) - client.getRealSkillLevel(s);
			int peak = peakBoost.getOrDefault(s, 0);
			if (boost >= 5)
			{
				if (boost > peak)
				{
					peakBoost.put(s, boost);
				}
			}
			else if (peak >= 5 && boost <= config.boostDropAt())
			{
				peakBoost.put(s, 0);
				raise(AlertKind.BOOST_DROPPED, pretty(s.getName()) + " boost has worn off");
			}
		}
	}

	private void checkCombat(Player me, long now)
	{
		boolean inArea = tracker.hasTask() && tracker.isInTaskArea();
		if (inArea && !wasInArea)
		{
			lastCombatAt = now;
		}
		wasInArea = inArea;
		if (me.getInteracting() != null)
		{
			lastCombatAt = now;
		}
		else
		{
			WorldView wv = client.getTopLevelWorldView();
			if (wv != null)
			{
				for (NPC npc : wv.npcs())
				{
					if (npc != null && npc.getInteracting() == me)
					{
						lastCombatAt = now;
						break;
					}
				}
			}
		}
		WorldPoint tile = me.getWorldLocation();
		if (tile != null && (lastTile == null || !tile.equals(lastTile)))
		{
			lastTile = tile;
			lastMovedAt = now;
		}
		long quietSince = Math.max(lastCombatAt, lastMovedAt);
		if (inArea && !readiness.isBankOpen() && now - quietSince >= config.idleSeconds() * 1000L)
		{
			raise(AlertKind.OUT_OF_COMBAT, "Standing idle for " + ((now - quietSince) / 1000) + "s");
		}
		else
		{
			clear(AlertKind.OUT_OF_COMBAT);
		}
	}

	private void checkBank()
	{
		if (readiness.isBankOpen() && tracker.hasTask())
		{
			List<Missing> req = readiness.requiredMissing();
			if (!req.isEmpty())
			{
				raise(AlertKind.MISSING_ITEMS, "Missing: " + joinLabels(req, 3), Severity.WARNING, false);
				return;
			}
		}
		clear(AlertKind.MISSING_ITEMS);
	}

	// ---------------------------------------------------------------- event sources

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		Integer prev = realLevels.put(e.getSkill(), e.getLevel());
		if (prev != null && e.getLevel() > prev && config.soundLevelUp() && client.getGameState() == GameState.LOGGED_IN)
		{
			sounds.play(SoundPlayer.Sound.LEVEL_UP, config.soundVolume());
		}
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (e.getActor() == client.getLocalPlayer())
		{
			lastCombatAt = System.currentTimeMillis();
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged e)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		Actor target = e.getTarget();
		if (e.getSource() == me && target instanceof NPC)
		{
			String who = ((NPC) target).getName();
			if (who != null && (who.equals("Turael") || who.equals("Aya") || who.equals("Spria")) && tracker.hasTask() && tracker.getTaskStreak() >= 5)
			{
				long now = System.currentTimeMillis();
				if (now - lastStreakWarnAt > 60_000L)
				{
					lastStreakWarnAt = now;
					raise(AlertKind.STREAK_RESET, "A new task from " + who + " resets your streak of " + tracker.getTaskStreak());
				}
				return;
			}
			lastCombatAt = System.currentTimeMillis();
			if (config.alertOffTask() && tracker.hasTask())
			{
				NPC npc = (NPC) target;
				if (attackable(npc) && !guard.isTaskTarget(npc))
				{
					long now = System.currentTimeMillis();
					if (now - lastOffTaskAt > 5_000L)
					{
						lastOffTaskAt = now;
						raise(AlertKind.OFF_TASK, npcName(npc) + " is not your task");
					}
				}
			}
		}
		else if (target == me && e.getSource() instanceof NPC)
		{
			lastCombatAt = System.currentTimeMillis();
		}
	}

	private static boolean attackable(NPC npc)
	{
		NPCComposition comp = npc.getTransformedComposition();
		if (comp == null)
		{
			return false;
		}
		String[] actions = comp.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String a : actions)
		{
			if ("Attack".equalsIgnoreCase(a))
			{
				return true;
			}
		}
		return false;
	}

	private static String npcName(NPC npc)
	{
		NPCComposition comp = npc.getTransformedComposition();
		String n = comp == null ? npc.getName() : comp.getName();
		return n == null ? "That NPC" : n;
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (!config.alertCannon() || !mode.get().active())
		{
			return;
		}
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = e.getMessage() == null ? "" : e.getMessage().replaceAll("<[^>]*>", "").toLowerCase(Locale.ROOT);
		if (!msg.contains("cannon"))
		{
			return;
		}
		if (msg.contains("out of ammo"))
		{
			raise(AlertKind.CANNON, "Cannon is out of ammo");
		}
		else if (msg.contains("broken"))
		{
			raise(AlertKind.CANNON, "Cannon has broken");
		}
		else if (msg.contains("decay"))
		{
			raise(AlertKind.CANNON, "Cannon is about to decay");
		}
	}

	@Subscribe
	public void onNotificationFired(NotificationFired e)
	{
		if (!config.alertAggression() || e.getMessage() == null || !mode.get().active())
		{
			return;
		}
		String m = e.getMessage().toLowerCase(Locale.ROOT);
		if (m.contains("aggress"))
		{
			// The source plugin already notified; banner only.
			raise(AlertKind.AGGRESSION, e.getMessage(), Severity.WARNING, false);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() != InterfaceID.BANKMAIN || !config.alertLeftBank() || !tracker.hasTask())
		{
			return;
		}
		List<Missing> req = readiness.requiredMissing();
		if (!req.isEmpty())
		{
			raise(AlertKind.LEFT_BANK, "Left the bank without: " + joinLabels(req, 3));
		}
	}

	@Subscribe
	public void onTaskChanged(TaskChanged e)
	{
		nearlyDoneFired = false;
		peakBoost.clear();
		lastCombatAt = System.currentTimeMillis();
		clear(AlertKind.OUT_OF_COMBAT);
		clear(AlertKind.MISSING_ITEMS);
	}

	@Subscribe
	public void onKillCounted(KillCounted e)
	{
		lastCombatAt = System.currentTimeMillis();
		if (!nearlyDoneFired && e.getRemaining() > 0 && e.getRemaining() <= config.nearlyDoneAt())
		{
			nearlyDoneFired = true;
			raise(AlertKind.NEARLY_DONE, e.getRemaining() + " kills left");
		}
	}

	@Subscribe
	public void onSuperiorSpawned(SuperiorSpawned e)
	{
		TaskRecord rec = tracker.getRecord();
		String name = rec != null && !rec.getSuperiors().isEmpty() ? rec.getSuperiors().get(0) : "Superior";
		raise(AlertKind.SUPERIOR, name + " has spawned");
	}

	@Subscribe
	public void onSuppliesLow(SuppliesLow e)
	{
		if (!mode.get().active())
		{
			return;
		}
		raise(AlertKind.SUPPLIES_LOW, "Supplies run out " + e.getShortfall() + " kills early (" + e.getResource() + "), bank after this trip");
	}

	@Subscribe
	public void onTaskFinished(TaskFinished e)
	{
		raise(AlertKind.TASK_DONE, "Task done: " + pretty(e.getName()));
	}

	/** For the ::ss alert test command: a critical alert that expires on its own. */
	public void testAlert()
	{
		boolean wasPaused = paused;
		paused = false;
		raise(AlertKind.LEFT_BANK, "Test alert: this is what a critical alert looks like", Severity.CRITICAL, true);
		paused = wasPaused;
	}

	// ---------------------------------------------------------------- helpers

	private static String joinLabels(List<Missing> list, int max)
	{
		StringBuilder sb = new StringBuilder();
		int n = 0;
		for (Missing m : list)
		{
			if (n++ == max)
			{
				sb.append(", +").append(list.size() - max);
				break;
			}
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(m.getLabel());
		}
		return sb.toString();
	}

	public static String pretty(String s)
	{
		if (s == null || s.isEmpty())
		{
			return "";
		}
		return Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
