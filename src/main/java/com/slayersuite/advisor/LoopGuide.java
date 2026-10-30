package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.TravelPoints;
import com.slayersuite.route.RouteService;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.TripTracker;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;

/**
 * Keeps the route going once an assignment ends: to the nearest bank you can use when food is low
 * or the inventory is nearly full, then back to the Slayer master you took the task from. The
 * advisor asks it every tick without a task and pins whatever it answers.
 */
@Slf4j
@Singleton
public class LoopGuide
{
	/** The loop runs this long after a task ends, or until the next one. */
	public static final long LOOP_MS = 20 * 60_000L;
	/** Standing at the bank this long without opening it: move on to the master. */
	private static final long BANK_WAIT_MS = 180_000L;
	/** Give the router this long before deciding a bank cannot be reached from here. */
	private static final long PLAN_SETTLE_MS = 4_000L;
	/** The master you spoke to in the last few hours is the one whose task this was. */
	private static final long MASTER_MEMORY_MS = 6 * 3_600_000L;
	private static final int CANDIDATES = 4;
	private static final int FOOD_SHORT = 3;
	private static final int SLOTS_SHORT = 3;

	public enum Leg
	{
		NONE, BANK, MASTER
	}

	private final Client client;
	private final SlayerSuiteConfig config;
	private final TaskTracker tracker;
	private final TravelPoints points;
	private final AccountState account;
	private final Provider<TripTracker> trip;
	private final RouteService routes;

	/** The task end this loop belongs to; a new end starts a new loop. */
	private long startedFor;
	@Getter
	private volatile Leg leg = Leg.NONE;
	private volatile LocationRecord target;
	private final List<LocationRecord> candidates = new ArrayList<>();
	private int candidate;
	private long candidateAt;
	private boolean bankOpen;
	private boolean banked;
	private long arrivedAt;
	private LocationRecord masterLoc;
	@Getter
	private volatile String masterName;
	@Getter
	private volatile String bankName;

	@Inject
	public LoopGuide(Client client, SlayerSuiteConfig config, TaskTracker tracker, TravelPoints points, AccountState account, Provider<TripTracker> trip, RouteService routes)
	{
		this.client = client;
		this.config = config;
		this.tracker = tracker;
		this.points = points;
		this.account = account;
		this.trip = trip;
		this.routes = routes;
	}

	public boolean active()
	{
		return leg != Leg.NONE;
	}

	/** True for a spot this guide handed out. */
	public boolean owns(LocationRecord loc)
	{
		return loc != null && (loc == target || loc == masterLoc || candidates.contains(loc));
	}

	/** "Bank first: Edgeville bank, then Konar quo Maten" or "Back to Krystilia". */
	public String describe()
	{
		switch (leg)
		{
			case BANK:
				return "Bank first: " + bankName + (masterName != null ? ", then " + masterName : "");
			case MASTER:
				return "Back to " + masterName;
			default:
				return "";
		}
	}

	/** Called each tick without a task, on the client thread: the spot to guide to now, or null. */
	LocationRecord tick(boolean arrivedAtTarget)
	{
		long now = System.currentTimeMillis();
		long fin = tracker.getFinishedAt();
		if (config.loopGuide() == LoopMode.OFF || fin <= 0 || now - fin > LOOP_MS || tracker.hasTask() || client.getGameState() != GameState.LOGGED_IN)
		{
			if (leg != Leg.NONE || startedFor != 0)
			{
				reset();
			}
			return null;
		}
		if (startedFor != fin)
		{
			begin(fin, now);
		}
		if (leg == Leg.BANK)
		{
			if (arrivedAtTarget && arrivedAt == 0)
			{
				arrivedAt = now;
			}
			boolean done = banked && !bankOpen;
			boolean gaveUp = arrivedAt > 0 && now - arrivedAt > BANK_WAIT_MS;
			if (done || gaveUp)
			{
				toMaster();
			}
			else if (!arrivedAtTarget && now - candidateAt > PLAN_SETTLE_MS && routes.plan() != null && !routes.reached())
			{
				// the router cannot get to this bank from here: the next nearest, then the master
				if (candidate + 1 < candidates.size())
				{
					candidate++;
					target = candidates.get(candidate);
					candidateAt = now;
					bankName = target.getName();
				}
				else
				{
					toMaster();
				}
			}
		}
		if (leg == Leg.MASTER && target == null)
		{
			leg = Leg.NONE;
		}
		return leg == Leg.NONE ? null : target;
	}

	private void begin(long fin, long now)
	{
		startedFor = fin;
		candidates.clear();
		candidate = 0;
		candidateAt = now;
		arrivedAt = 0;
		banked = false;
		target = null;
		masterLoc = masterSpot();
		masterName = masterLoc == null ? null : masterLoc.getName();
		if (config.loopGuide() == LoopMode.BANK_THEN_MASTER && suppliesShort())
		{
			candidates.addAll(bankCandidates());
		}
		if (!candidates.isEmpty())
		{
			leg = Leg.BANK;
			target = candidates.get(0);
			bankName = target.getName();
		}
		else
		{
			toMaster();
		}
		log.debug("Slayer Suite loop: {} ({} bank candidates, master {})", leg, candidates.size(), masterName);
	}

	private void toMaster()
	{
		bankName = null;
		target = masterLoc;
		leg = masterLoc == null ? Leg.NONE : Leg.MASTER;
	}

	private void reset()
	{
		startedFor = 0;
		leg = Leg.NONE;
		target = null;
		masterLoc = null;
		masterName = null;
		bankName = null;
		candidates.clear();
		candidate = 0;
		arrivedAt = 0;
		banked = false;
	}

	/** The master you spoke to most recently, else the one whose list the finished task came from. */
	private LocationRecord masterSpot()
	{
		TravelPoints.Point p = null;
		String who = tracker.getLastMasterName();
		if (who != null && System.currentTimeMillis() - tracker.getLastMasterAt() < MASTER_MEMORY_MS)
		{
			p = points.master(who);
		}
		if (p == null)
		{
			p = points.masterFor(tracker.getLastMasterId());
		}
		if (p == null)
		{
			return null;
		}
		String place = p.getPlace() == null || p.getPlace().isEmpty() ? "" : " (" + p.getPlace() + ")";
		return spot(p.getName() + place, p);
	}

	private List<LocationRecord> bankCandidates()
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return Collections.emptyList();
		}
		WorldPoint wp = me.getWorldLocation();
		List<TravelPoints.Point> usable = new ArrayList<>();
		for (TravelPoints.Point b : points.banks())
		{
			if (meets(TravelPoints.requirement(b.getRequirement())))
			{
				usable.add(b);
			}
		}
		List<LocationRecord> out = new ArrayList<>();
		for (TravelPoints.Point b : TravelPoints.nearest(usable, wp.getX(), wp.getY(), CANDIDATES))
		{
			out.add(spot(b.getName(), b));
		}
		return out;
	}

	private boolean meets(TravelPoints.Requirement r)
	{
		switch (r.kind)
		{
			case NONE:
				return true;
			case QUEST:
				return account.questFinished(r.key);
			case SKILL:
				return account.skillLevel(r.key) >= r.level;
			case DIARY:
				return account.diaryComplete(r.key);
			default:
				return false;
		}
	}

	/** Fewer than three food, or three or fewer free inventory slots. */
	boolean suppliesShort()
	{
		TripTracker t = trip.get();
		int food = t == null ? Integer.MAX_VALUE : t.getFoodCount();
		return food < FOOD_SHORT || freeSlots() <= SLOTS_SHORT;
	}

	private int freeSlots()
	{
		ItemContainer inv = client.getItemContainer(InventoryID.INV);
		if (inv == null)
		{
			return 28;
		}
		int used = 0;
		for (Item it : inv.getItems())
		{
			if (it != null && it.getId() > 0)
			{
				used++;
			}
		}
		return Math.max(0, 28 - used);
	}

	private static LocationRecord spot(String name, TravelPoints.Point p)
	{
		LocationRecord r = new LocationRecord();
		r.setName(name);
		r.setX(p.getX());
		r.setY(p.getY());
		r.setPlane(p.getPlane());
		List<Integer> regions = new ArrayList<>();
		regions.add(((p.getX() >> 6) << 8) | (p.getY() >> 6));
		r.setRegions(regions);
		return r;
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = true;
			if (leg == Leg.BANK)
			{
				banked = true;
			}
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.BANKMAIN)
		{
			bankOpen = false;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			reset();
		}
	}
}
