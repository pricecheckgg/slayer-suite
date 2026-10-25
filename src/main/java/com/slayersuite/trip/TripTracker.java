package com.slayersuite.trip;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.history.TaskHistory;
import java.util.ArrayList;
import java.util.List;
import com.slayersuite.history.TaskStats;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.task.KillCounted;
import com.slayersuite.task.TaskChanged;
import com.slayersuite.task.TaskTracker;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Value;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Measures food eaten, potion doses drunk and prayer points spent per kill from
 * inventory and stat changes, then projects how many kills the current supplies
 * last. Read-only: it never eats or drinks for you.
 */
@Singleton
public class TripTracker
{
	private static final Pattern DOSE = Pattern.compile("\\((\\d)\\)$");
	private static final int MIN_KILLS_FOR_SESSION_RATE = 5;
	private static final long AFTER_BANK_GRACE_MS = 4_000L;

	@Value
	public static class Projection
	{
		long killsUntilDry;
		long shortfall;
		String limitingResource;
		boolean fromHistory;
		/** One human line per resource, for chat and logs. */
		List<String> lines;
		/** The same per resource, structured for the panel's rows. */
		List<Resource> resources;
	}

	/** One consumable on the supplies card: what you carry, how fast it goes, how many kills it lasts (-1 unknown). */
	@Value
	public static class Resource
	{
		String name;
		String have;
		String rate;
		long lastsKills;
	}

	/** How much prayer one dose gives at a prayer level: potions restore 7 + level/4, super restores 8 + level/4, brews 2 + level/10. */
	public static int prayerPerDose(String normalizedName, int prayerLevel)
	{
		if (normalizedName.startsWith("prayer potion"))
		{
			return 7 + prayerLevel / 4;
		}
		if (normalizedName.startsWith("ancient brew") || normalizedName.startsWith("forgotten brew"))
		{
			return 2 + prayerLevel / 10;
		}
		return 8 + prayerLevel / 4;
	}

	/** Minutes of dragonfire protection one dose gives. */
	public static int antifireMinutesPerDose(String normalizedName)
	{
		if (normalizedName.startsWith("extended super antifire"))
		{
			return 6;
		}
		if (normalizedName.startsWith("extended antifire"))
		{
			return 12;
		}
		if (normalizedName.startsWith("super antifire"))
		{
			return 3;
		}
		return 6;
	}

	private final Client client;
	private final ItemManager itemManager;
	private final com.slayersuite.data.SlayerDataset dataset;
	private final ReadinessChecker readiness;
	private final TaskTracker tracker;
	private final TaskHistory history;
	private final SlayerSuiteConfig config;
	private final EventBus eventBus;

	private final Map<Integer, int[]> itemInfo = new HashMap<>(); // id -> {edible 0/1, doses}

	private int foodCount = -1;
	private int doseCount = -1;
	/** Prayer points stored in restore doses and minutes of antifire carried, from the last inventory scan. */
	private int prayerInDoses;
	private int prayerDoses;
	private int antifireMinutes;
	private int antifireDoses;
	private final Map<Integer, String> nameCache = new HashMap<>();
	private int prevPrayer = -1;
	private long bankClosedAt;
	private boolean alertedThisTrip;
	private int tick;

	@Getter
	private double foodEaten;
	@Getter
	private double dosesUsed;
	@Getter
	private double prayerSpent;
	@Getter
	private long sessionKills;
	@Getter
	private Projection projection;

	private double priorFoodPerKill;
	private double priorDosesPerKill;

	@Inject
	public TripTracker(Client client, ItemManager itemManager, com.slayersuite.data.SlayerDataset dataset, ReadinessChecker readiness, TaskTracker tracker, TaskHistory history, SlayerSuiteConfig config, EventBus eventBus)
	{
		this.dataset = dataset;
		this.client = client;
		this.itemManager = itemManager;
		this.readiness = readiness;
		this.tracker = tracker;
		this.history = history;
		this.config = config;
		this.eventBus = eventBus;
	}

	public void reset()
	{
		foodCount = doseCount = -1;
		prevPrayer = -1;
		foodEaten = dosesUsed = prayerSpent = 0;
		sessionKills = 0;
		projection = null;
		alertedThisTrip = false;
		priorFoodPerKill = priorDosesPerKill = 0;
	}

	public double foodPerKill()
	{
		return TripMath.perKill(foodEaten, sessionKills);
	}

	public double dosesPerKill()
	{
		return TripMath.perKill(dosesUsed, sessionKills);
	}

	public double prayerPerKill()
	{
		return TripMath.perKill(prayerSpent, sessionKills);
	}

	public int getFoodCount()
	{
		return Math.max(0, foodCount);
	}

	public int getDoseCount()
	{
		return Math.max(0, doseCount);
	}

	// ---------------------------------------------------------------- inventory

	private int[] info(int id)
	{
		int[] v = itemInfo.get(id);
		if (v == null)
		{
			ItemComposition comp = itemManager.getItemComposition(id);
			int edible = 0;
			int doses = 0;
			String[] actions = comp.getInventoryActions();
			if (actions != null)
			{
				for (String a : actions)
				{
					if ("Eat".equalsIgnoreCase(a))
					{
						edible = 1;
					}
					else if ("Drink".equalsIgnoreCase(a))
					{
						Matcher m = DOSE.matcher(comp.getName() == null ? "" : comp.getName());
						doses = m.find() ? Integer.parseInt(m.group(1)) : 1;
					}
				}
			}
			v = new int[]{edible, doses};
			itemInfo.put(id, v);
		}
		return v;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() != InventoryID.INV)
		{
			return;
		}
		ItemContainer inv = e.getItemContainer();
		int food = 0;
		int doses = 0;
		int prayerPts = 0;
		int prayerD = 0;
		int fireMin = 0;
		int fireD = 0;
		int prayerLevel = client.getGameState() == GameState.LOGGED_IN ? client.getRealSkillLevel(Skill.PRAYER) : 1;
		com.slayersuite.data.ItemGroups groups = dataset.getItems();
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it == null || it.getId() <= 0)
				{
					continue;
				}
				int[] v = info(it.getId());
				int qty = Math.max(1, it.getQuantity());
				food += v[0] * qty;
				doses += v[1] * qty;
				if (v[1] > 0)
				{
					String n = nameCache.get(it.getId());
					if (n == null)
					{
						n = com.slayersuite.data.ItemNames.normalize(itemManager.getItemComposition(it.getId()).getName());
						nameCache.put(it.getId(), n);
					}
					java.util.Set<String> one = java.util.Collections.singleton(n);
					if (groups.satisfied("prayer_restore", one))
					{
						prayerD += v[1] * qty;
						prayerPts += v[1] * qty * prayerPerDose(n, prayerLevel);
					}
					else if (groups.satisfied("antifire_potion", one))
					{
						fireD += v[1] * qty;
						fireMin += v[1] * qty * antifireMinutesPerDose(n);
					}
				}
			}
		}
		prayerInDoses = prayerPts;
		prayerDoses = prayerD;
		antifireMinutes = fireMin;
		antifireDoses = fireD;
		long now = System.currentTimeMillis();
		boolean settling = readiness.isBankOpen() || now - bankClosedAt < AFTER_BANK_GRACE_MS;
		if (!settling && tracker.hasTask() && foodCount >= 0 && doseCount >= 0)
		{
			int dFood = foodCount - food;
			int dDoses = doseCount - doses;
			// one bite or one sip at a time; bigger jumps are deposits, drops or death
			if (dFood > 0 && dFood <= 2)
			{
				foodEaten += dFood;
			}
			if (dDoses > 0 && dDoses <= 2)
			{
				dosesUsed += dDoses;
			}
		}
		if (food > foodCount || doses > doseCount)
		{
			alertedThisTrip = false; // restocked
		}
		foodCount = food;
		doseCount = doses;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN || e.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			bankClosedAt = System.currentTimeMillis();
			alertedThisTrip = false;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN || e.getGroupId() == InterfaceID.BANK_DEPOSITBOX)
		{
			bankClosedAt = System.currentTimeMillis();
		}
	}

	// ---------------------------------------------------------------- task flow

	@Subscribe
	public void onTaskChanged(TaskChanged e)
	{
		foodEaten = dosesUsed = prayerSpent = 0;
		sessionKills = 0;
		projection = null;
		alertedThisTrip = false;
		TaskStats stats = history.getCurrent();
		priorFoodPerKill = stats == null ? 0 : stats.getFoodPerKill();
		priorDosesPerKill = stats == null ? 0 : stats.getDosesPerKill();
	}

	@Subscribe
	public void onKillCounted(KillCounted e)
	{
		sessionKills += e.getDelta();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() != GameState.LOGGED_IN)
		{
			prevPrayer = -1;
			foodCount = doseCount = -1;
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		TaskStats current = history.getCurrent();
		readiness.setPrayerPerKill(current == null ? 0 : current.getPrayerPerKill());
		if (foodCount < 0 || doseCount < 0)
		{
			ItemContainer inv = client.getItemContainer(InventoryID.INV);
			if (inv != null)
			{
				onItemContainerChanged(new ItemContainerChanged(InventoryID.INV, inv));
			}
		}
		int prayer = client.getBoostedSkillLevel(Skill.PRAYER);
		if (prevPrayer >= 0 && prayer < prevPrayer && tracker.hasTask())
		{
			prayerSpent += prevPrayer - prayer;
		}
		prevPrayer = prayer;

		if (++tick % 10 == 0)
		{
			projection = project();
			if (projection != null && projection.getKillsUntilDry() >= 0 && config.suppliesAlert() && !alertedThisTrip && !readiness.isBankOpen()
				&& projection.getShortfall() > config.suppliesMargin() && sessionKills >= 1)
			{
				alertedThisTrip = true;
				eventBus.post(new SuppliesLow(projection.getKillsUntilDry(), projection.getShortfall(), projection.getLimitingResource()));
			}
		}
	}

	/** True when the task's own protection rules name dragonfire protection; a stray antifire dose at spectres is not a supply. */
	boolean taskNeedsAntifire()
	{
		com.slayersuite.data.TaskRecord rec = tracker.getRecord();
		if (rec == null)
		{
			return false;
		}
		for (com.slayersuite.data.ItemRule rule : rec.getProtection())
		{
			for (String group : rule.getAnyOf())
			{
				if (group.contains("antifire"))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Kills the current supplies last, against kills remaining; null when nothing measurable is being consumed. */
	Projection project()
	{
		if (!tracker.hasTask() || tracker.getRemaining() <= 0 || foodCount < 0 || doseCount < 0)
		{
			return null;
		}
		boolean fromSession = sessionKills >= MIN_KILLS_FOR_SESSION_RATE;
		TaskStats stats = history.getCurrent();
		double fpk = fromSession ? foodPerKill() : priorFoodPerKill;
		double dpk = fromSession ? dosesPerKill() : priorDosesPerKill;
		double ppk = fromSession ? prayerPerKill() : (stats == null ? 0 : stats.getPrayerPerKill());
		int remaining = tracker.getRemaining();
		List<String> lines = new ArrayList<>();
		List<Resource> rows = new ArrayList<>();

		long foodKills = TripMath.killsUntilDry(getFoodCount(), fpk);
		String pieces = getFoodCount() + (getFoodCount() == 1 ? " piece" : " pieces");
		if (foodKills >= 0)
		{
			lines.add("Food: " + pieces + ", " + String.format("%.2f", fpk) + " per kill, lasts ~" + foodKills + " kills");
			rows.add(new Resource("Food", pieces, String.format("%.2f/kill", fpk), foodKills));
		}
		else if (getFoodCount() > 0)
		{
			lines.add("Food: " + pieces + ", none eaten yet");
			rows.add(new Resource("Food", pieces, "none eaten yet", -1));
		}

		// Prayer: points you have now plus what the restores hold, against points per kill.
		long prayerKills = -1;
		if (ppk > 0)
		{
			int now = client.getGameState() == GameState.LOGGED_IN ? client.getBoostedSkillLevel(Skill.PRAYER) : 0;
			long available = now + prayerInDoses;
			prayerKills = (long) Math.floor(available / ppk);
			lines.add("Prayer: " + now + " points + " + prayerDoses + " doses (" + prayerInDoses + " points), " + String.format("%.1f", ppk) + " per kill, lasts ~" + prayerKills + " kills");
			rows.add(new Resource("Prayer", now + " pts + " + prayerDoses + (prayerDoses == 1 ? " dose" : " doses") + " (" + prayerInDoses + " pts)", String.format("%.1f/kill", ppk), prayerKills));
		}
		else if (prayerDoses > 0)
		{
			lines.add("Prayer: " + prayerDoses + " restore doses, no prayer spent yet");
			rows.add(new Resource("Prayer", prayerDoses + " restore " + (prayerDoses == 1 ? "dose" : "doses"), "none spent yet", -1));
		}

		// Antifire: minutes carried against the time the task still needs.
		long antifireKills = -1;
		if (antifireDoses > 0 && taskNeedsAntifire())
		{
			double kph = tracker.effectiveKph();
			if (kph > 0)
			{
				antifireKills = (long) Math.floor(antifireMinutes / 60.0 * kph);
				long etaMin = Math.round(remaining / kph * 60);
				lines.add("Antifire: " + antifireDoses + (antifireDoses == 1 ? " dose = " : " doses = ") + antifireMinutes + " min, task needs ~" + etaMin + " min at " + Math.round(kph) + " kills/h");
				rows.add(new Resource("Antifire", antifireDoses + (antifireDoses == 1 ? " dose = " : " doses = ") + antifireMinutes + " min", "task needs ~" + etaMin + " min", antifireKills));
			}
			else
			{
				lines.add("Antifire: " + antifireDoses + (antifireDoses == 1 ? " dose = " : " doses = ") + antifireMinutes + " min");
				rows.add(new Resource("Antifire", antifireDoses + (antifireDoses == 1 ? " dose = " : " doses = ") + antifireMinutes + " min", "no rate yet", -1));
			}
		}

		// Other potions, by dose, as before.
		int otherDoses = Math.max(0, getDoseCount() - prayerDoses - antifireDoses);
		long doseKills = TripMath.killsUntilDry(otherDoses, dpk);
		if (doseKills >= 0 && otherDoses > 0)
		{
			lines.add("Other potions: " + otherDoses + " doses, " + String.format("%.2f", dpk) + " per kill, lasts ~" + doseKills + " kills");
			rows.add(new Resource("Potions", otherDoses + (otherDoses == 1 ? " dose" : " doses"), String.format("%.2f/kill", dpk), doseKills));
		}

		long kills = TripMath.minPositive(foodKills, prayerKills, antifireKills, doseKills);
		if (kills < 0)
		{
			return lines.isEmpty() ? null : new Projection(-1, 0, "", !fromSession, lines, rows);
		}
		String limiting = kills == prayerKills && prayerKills >= 0 ? "prayer" : kills == antifireKills && antifireKills >= 0 ? "antifire"
			: kills == foodKills && foodKills >= 0 ? "food" : "potions";
		long shortfall = remaining - kills;
		return new Projection(kills, Math.max(0, shortfall), limiting, !fromSession, lines, rows);
	}
}
