package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.task.TaskChanged;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.TripMath;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.eventbus.EventBus;
import java.util.HashMap;
import java.util.Map;
import net.runelite.client.ui.overlay.worldmap.WorldMapPoint;
import net.runelite.client.ui.overlay.worldmap.WorldMapPointManager;
import net.runelite.client.util.ImageUtil;

/**
 * Ranks the task's locations for this account, keeps the chosen destination,
 * pins it on the world map and feeds the on-screen direction hint. It shows
 * the way; it never walks it.
 */
@Slf4j
@Singleton
public class Advisor
{
	private final Client client;
	private final SlayerSuiteConfig config;
	private final TaskTracker tracker;
	private final AccountState account;
	private final TravelData travel;
	private final ReadinessChecker readiness;
	private final WorldMapPointManager mapPoints;
	private final LocationScorer scorer;
	private final EventBus eventBus;

	private volatile List<ScoredLocation> ranked = Collections.emptyList();
	@Getter
	private volatile LocationRecord destination;
	@Getter
	private volatile boolean arrived;
	private WorldMapPoint pin;
	private BufferedImage pinImage;
	private long lastBankRefresh;
	private int tick;
	@Setter
	private Runnable listener;

	// Values the side panel reads from the Swing thread; refreshed on the client thread each tick.
	private volatile int cachedDistance = -1;
	private volatile String cachedDirection = "";
	private volatile List<TravelOption> cachedTravel = Collections.emptyList();
	private volatile LocationRecord cachedTravelFor;
	/** The one thing to do next to get to the pin, in words; refreshed each tick on the client thread. */
	@Getter
	private volatile String nextStep = "";

	@Inject
	public Advisor(Client client, SlayerSuiteConfig config, TaskTracker tracker, AccountState account, TravelData travel, ReadinessChecker readiness, WorldMapPointManager mapPoints, EventBus eventBus)
	{
		this.eventBus = eventBus;
		this.client = client;
		this.config = config;
		this.tracker = tracker;
		this.account = account;
		this.travel = travel;
		this.readiness = readiness;
		this.mapPoints = mapPoints;
		this.scorer = new LocationScorer(travel);
	}

	public void reset()
	{
		ranked = Collections.emptyList();
		clearDestination();
	}

	public List<ScoredLocation> ranked()
	{
		return ranked;
	}

	/** Krystilia's tasks live in the Wilderness by definition: never penalise or hide them for it. */
	public boolean wildernessTask()
	{
		com.slayersuite.task.GameTaskTable.GameTask gt = tracker.getGameTask();
		return gt != null && gt.getMasterId() == 7;
	}

	private LocationScorer.Options options()
	{
		LocationScorer.Options o = new LocationScorer.Options();
		o.avoidWilderness = config.avoidWilderness() && !wildernessTask();
		o.preferCannon = config.preferCannon();
		o.preferMulti = true;
		return o;
	}

	/** The ranked list after the panel's filters. */
	public List<ScoredLocation> filtered()
	{
		List<ScoredLocation> out = new ArrayList<>();
		for (ScoredLocation s : ranked)
		{
			LocationRecord l = s.getLocation();
			if (config.filterCannon() && !Boolean.TRUE.equals(l.getCannon()))
			{
				continue;
			}
			if (config.filterMulti() && !Boolean.TRUE.equals(l.getMulticombat()))
			{
				continue;
			}
			if (config.filterSafespot() && !Boolean.TRUE.equals(l.getSafespot()))
			{
				continue;
			}
			if (config.filterNoWilderness() && s.isWilderness() && !wildernessTask())
			{
				continue;
			}
			if (config.hideLocked() && s.isLocked())
			{
				continue;
			}
			out.add(s);
		}
		return out;
	}

	public ScoredLocation best()
	{
		for (ScoredLocation s : filtered())
		{
			if (!s.isLocked())
			{
				return s;
			}
		}
		for (ScoredLocation s : ranked)
		{
			if (!s.isLocked())
			{
				return s;
			}
		}
		return null;
	}

	/** Rank any task's locations for this account (used by the task choice screen). */
	public List<ScoredLocation> rankFor(TaskRecord rec)
	{
		return rec == null ? Collections.emptyList() : scorer.rank(rec, account, options());
	}

	public void refresh()
	{
		TaskRecord rec = tracker.getRecord();
		List<ScoredLocation> list = rec == null ? Collections.emptyList() : scorer.rank(rec, account, options());
		// Konar assigns a place with the monster: only spots in that area count, when the dataset knows any of them.
		String area = tracker.getTaskLocation();
		if (area != null && !area.isEmpty() && !list.isEmpty())
		{
			List<ScoredLocation> inArea = new ArrayList<>();
			for (ScoredLocation s : list)
			{
				if (samePlace(area, s.getLocation().getName()))
				{
					inArea.add(s);
				}
			}
			if (!inArea.isEmpty())
			{
				list = inArea;
			}
		}
		ranked = Collections.unmodifiableList(list);
		notifyListener();
	}

	/** Loose place-name match: equal, one inside the other, or the same first two words ("Fremennik Slayer Dungeon" and "... Cave"). */
	public static boolean samePlace(String a, String b)
	{
		String na = a.toLowerCase().replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
		String nb = b.toLowerCase().replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ").trim();
		if (na.isEmpty() || nb.isEmpty())
		{
			return false;
		}
		if (na.equals(nb) || (na.length() > 6 && (nb.contains(na) || na.contains(nb))))
		{
			return true;
		}
		String[] wa = na.split(" ");
		String[] wb = nb.split(" ");
		return wa.length >= 2 && wb.length >= 2 && wa[0].equals(wb[0]) && wa[1].equals(wb[1]);
	}

	private void notifyListener()
	{
		Runnable l = listener;
		if (l != null)
		{
			l.run();
		}
	}

	// ---------------------------------------------------------------- destination

	/** Within this many tiles of a spawn pin counts as having arrived. */
	private static final int ARRIVE_TILES = 10;

	/** Distance in tiles from the player to the nearest spawn pin of the location (or its centre), or -1. Client thread. */
	public int tilesToNearestSpawn(LocationRecord loc)
	{
		Player me = client.getLocalPlayer();
		if (loc == null || me == null)
		{
			return -1;
		}
		WorldPoint wp = me.getWorldLocation();
		int plane = loc.getPlane() == null ? 0 : loc.getPlane();
		if (wp.getPlane() != plane)
		{
			return -1;
		}
		int best = -1;
		if (loc.getPins() != null)
		{
			for (List<Integer> pin : loc.getPins())
			{
				if (pin == null || pin.size() < 2)
				{
					continue;
				}
				int d = (int) Math.round(Math.hypot(pin.get(0) - wp.getX(), pin.get(1) - wp.getY()));
				if (best < 0 || d < best)
				{
					best = d;
				}
			}
		}
		if (best < 0 && loc.hasCoordinates())
		{
			best = (int) Math.round(Math.hypot(loc.getX() - wp.getX(), loc.getY() - wp.getY()));
		}
		return best;
	}

	private boolean nearSpawn(LocationRecord loc)
	{
		int d = tilesToNearestSpawn(loc);
		return d >= 0 && d <= ARRIVE_TILES;
	}

	public void setDestination(LocationRecord loc)
	{
		destination = loc;
		arrived = loc != null && nearSpawn(loc);
		updatePin();
		handToShortestPath();
		notifyListener();
	}

	public void clearDestination()
	{
		destination = null;
		arrived = false;
		updatePin();
		handToShortestPath();
		notifyListener();
	}

	/**
	 * The Shortest Path plugin (Hub) owns a full world map with doors, shortcuts and transports.
	 * When it is installed, it draws the whole route for the pin; without it nothing happens.
	 */
	private void handToShortestPath()
	{
		if (!config.handToShortestPath())
		{
			return;
		}
		try
		{
			WorldPoint wp = arrived ? null : destinationPoint();
			if (wp == null)
			{
				eventBus.post(new PluginMessage("shortestpath", "clear"));
			}
			else
			{
				Map<String, Object> data = new HashMap<>();
				data.put("target", wp);
				eventBus.post(new PluginMessage("shortestpath", "path", data));
			}
		}
		catch (RuntimeException e)
		{
			log.debug("shortest path hand-off failed", e);
		}
	}

	/** Words for the next move: use a teleport you own, or walk. Client thread. */
	private String computeNextStep(LocationRecord dest)
	{
		if (dest == null)
		{
			return "";
		}
		if (arrived)
		{
			return "You are there";
		}
		Player me0 = client.getLocalPlayer();
		if (me0 != null && dest.getPlane() != null && me0.getWorldLocation().getPlane() != dest.getPlane() && inRegionOf(dest))
		{
			// right place, wrong floor: the floor path and the tile distance cannot say this
			int up = dest.getPlane() - me0.getWorldLocation().getPlane();
			return (up > 0 ? "Go up " : "Go down ") + Math.abs(up) + (Math.abs(up) == 1 ? " floor" : " floors") + " to " + floorName(dest.getPlane()) + "; the spawns are there";
		}
		int dist = distanceFromPlayer();
		String dir = directionFromPlayer();
		int toSpawn = tilesToNearestSpawn(dest);
		if (toSpawn >= 0 && toSpawn < dist)
		{
			dist = toSpawn;
		}
		if (dist < 0)
		{
			return "";
		}
		Player me = client.getLocalPlayer();
		WorldPoint here = me == null ? null : me.getWorldLocation();
		TravelOption best = null;
		for (TravelOption o : cachedTravel)
		{
			if (o.isUsable())
			{
				best = o;
				break;
			}
		}
		// Already close to where the best teleport lands, or close to the spot itself: walk.
		boolean nearLanding = best != null && best.getLanding() != null && here != null
			&& Math.hypot(best.getLanding()[0] - here.getX(), best.getLanding()[1] - here.getY()) < 40;
		if (dist <= config.floorPathMaxDistance() || nearLanding)
		{
			return "Walk " + TripMath.formatK(dist) + " tiles " + dir + (dist <= config.floorPathMaxDistance() ? " (follow the tiles)" : "");
		}
		if (best != null)
		{
			String how = best.getLabel().replace("House: ", "house ");
			String where = best.getStatus().equals("in bank") ? " (it is in your bank)" : best.getStatus().startsWith("needs") ? " (" + best.getStatus() + ")" : "";
			return "Use " + how + where + ", then " + (best.getHops() <= 1 ? "you are there" : best.getHops() == 2 ? "a short run" : "a long run");
		}
		return "Head " + dir + ", " + TripMath.formatK(dist) + " tiles; no teleport you own";
	}

	/** True when one of the loaded map squares belongs to the location. */
	private boolean inRegionOf(LocationRecord loc)
	{
		net.runelite.api.WorldView wv = client.getTopLevelWorldView();
		int[] regions = wv == null ? null : wv.getMapRegions();
		if (regions == null)
		{
			return false;
		}
		for (int r : regions)
		{
			if (loc.coversRegion(r))
			{
				return true;
			}
		}
		return false;
	}

	private static String floorName(int plane)
	{
		switch (plane)
		{
			case 0: return "the ground floor";
			case 1: return "the first floor";
			case 2: return "the second floor";
			default: return "floor " + plane;
		}
	}

	private void updatePin()
	{
		if (pin != null)
		{
			mapPoints.remove(pin);
			pin = null;
		}
		LocationRecord loc = destination;
		if (loc == null || !loc.hasCoordinates() || arrived)
		{
			return;
		}
		if (pinImage == null)
		{
			try
			{
				BufferedImage raw = ImageUtil.loadImageResource(Advisor.class, "/com/slayersuite/icon.png");
				pinImage = raw == null ? null : ImageUtil.resizeImage(raw, 20, 20);
			}
			catch (RuntimeException e)
			{
				pinImage = null;
			}
		}
		WorldMapPoint.WorldMapPointBuilder<?, ?> b = WorldMapPoint.builder()
			.worldPoint(destinationPoint())
			.name(loc.getName())
			.tooltip("Slayer Suite: " + loc.getName())
			.snapToEdge(true)
			.jumpOnClick(true);
		if (pinImage != null)
		{
			b.image(pinImage);
		}
		pin = b.build();
		mapPoints.add(pin);
	}

	public WorldPoint destinationPoint()
	{
		LocationRecord loc = destination;
		if (loc == null || !loc.hasCoordinates())
		{
			return null;
		}
		int plane = loc.getPlane() == null ? 0 : loc.getPlane();
		return new WorldPoint(loc.getX(), loc.getY(), plane);
	}

	public static boolean sameLocation(LocationRecord a, LocationRecord b)
	{
		return a != null && b != null && a.getName() != null && a.getName().equals(b.getName());
	}

	/** Last distance computed on the client thread; safe to read from Swing. */
	public int cachedDistance()
	{
		return cachedDistance;
	}

	public String cachedDirection()
	{
		return cachedDirection;
	}

	/** Travel options for the pinned (or best) spot, computed on the client thread; safe to read from Swing. */
	public List<TravelOption> cachedTravelOptions(LocationRecord loc)
	{
		LocationRecord f = cachedTravelFor;
		return f != null && loc != null && sameLocation(f, loc) ? cachedTravel : Collections.emptyList();
	}

	/** Distance in tiles from the player to the destination, ignoring plane; -1 when unknown. Client thread only. */
	public int distanceFromPlayer()
	{
		WorldPoint dest = destinationPoint();
		Player me = client.getLocalPlayer();
		if (dest == null || me == null)
		{
			return -1;
		}
		WorldPoint wp = me.getWorldLocation();
		return (int) Math.round(Math.hypot(dest.getX() - wp.getX(), dest.getY() - wp.getY()));
	}

	/** Eight-point compass direction from the player to the destination. */
	public String directionFromPlayer()
	{
		WorldPoint dest = destinationPoint();
		Player me = client.getLocalPlayer();
		if (dest == null || me == null)
		{
			return "";
		}
		WorldPoint wp = me.getWorldLocation();
		return compass(dest.getX() - wp.getX(), dest.getY() - wp.getY());
	}

	static String compass(int dx, int dy)
	{
		if (dx == 0 && dy == 0)
		{
			return "here";
		}
		double ang = Math.toDegrees(Math.atan2(dx, dy)); // 0 = north, clockwise
		if (ang < 0)
		{
			ang += 360;
		}
		String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		return names[(int) Math.round(ang / 45.0) % 8];
	}

	/** The ranked entry for the destination, if any. */
	public ScoredLocation destinationScore()
	{
		LocationRecord d = destination;
		if (d == null)
		{
			return null;
		}
		for (ScoredLocation s : ranked)
		{
			if (sameLocation(s.getLocation(), d))
			{
				return s;
			}
		}
		return null;
	}

	/** Every known way to a location, with whether the account can use it right now. */
	public List<TravelOption> travelOptions(LocationRecord loc)
	{
		List<TravelOption> out = new ArrayList<>();
		TravelData.Entry e = loc == null ? null : travel.find(loc.getName());
		if (e == null)
		{
			return out;
		}
		for (TravelData.Teleport t : e.getTeleports())
		{
			boolean usable;
			String status;
			int hops = t.getHops();
			if (t.getPoh() != null)
			{
				boolean jewellery = t.getItem() != null && t.getPoh().equalsIgnoreCase("Jewellery Box");
				usable = jewellery ? account.jewelleryBoxCovers(t.getItem()) : account.hasHouseFacility(t.getPoh());
				if (!account.houseScanned())
				{
					status = "house not scanned yet";
				}
				else if (!usable)
				{
					status = "not in your house";
				}
				else if (!account.canTeleportHome())
				{
					hops += 1;
					status = "in your house (no quick way home)";
				}
				else
				{
					status = "in your house";
				}
			}
			else if (t.getItem() != null)
			{
				String n = com.slayersuite.data.ItemNames.normalize(t.getItem());
				if (readiness.heldNames().contains(n) || startsWithAny(readiness.heldNames(), n))
				{
					usable = true;
					status = "carried";
				}
				else if (readiness.bankNames().contains(n) || startsWithAny(readiness.bankNames(), n))
				{
					usable = true;
					status = "in bank";
				}
				else if (readiness.owns(t.getItem()))
				{
					usable = true;
					status = "seen in your bank before";
				}
				else
				{
					usable = false;
					status = readiness.anythingRemembered() ? "not owned" : "not seen yet (open your bank)";
				}
			}
			else if (t.getFairy() != null)
			{
				usable = account.fairyRingsUsable();
				status = usable ? "fairy rings" : "fairy rings locked (Fairytale II + staff)";
			}
			else if (t.getSpell() != null)
			{
				int magic = account.skillLevel("MAGIC");
				if (t.getQuest() != null && !account.questFinished(t.getQuest()))
				{
					usable = false;
					status = "needs " + LocationScorer.pretty(t.getQuest());
				}
				else if (t.getLevel() > 0 && magic >= 0 && magic < t.getLevel())
				{
					usable = false;
					status = "needs " + t.getLevel() + " Magic (you have " + magic + ")";
				}
				else
				{
					int book = LocationScorer.bookIndex(t.getBook());
					int have = account.spellbook();
					usable = true;
					if (book >= 0 && have >= 0 && have != book)
					{
						hops += 1;
						status = "needs the " + t.getBook() + " spellbook";
					}
					else
					{
						status = "spell";
					}
				}
			}
			else
			{
				continue;
			}
			out.add(new TravelOption(t.label(), hops, usable, status, TravelData.landing(t)));
		}
		out.sort((a, b) ->
		{
			if (a.isUsable() != b.isUsable())
			{
				return a.isUsable() ? -1 : 1;
			}
			return Integer.compare(a.getHops(), b.getHops());
		});
		return out;
	}

	private static boolean startsWithAny(java.util.Set<String> pool, String want)
	{
		for (String have : pool)
		{
			if (have.startsWith(want + " ") || have.startsWith(want + "("))
			{
				return !have.contains("(inert)") && !have.contains("(uncharged)") && !have.contains("(empty)");
			}
		}
		return false;
	}

	// ---------------------------------------------------------------- events

	@Subscribe
	public void onTaskChanged(TaskChanged e)
	{
		if (e.getNewName() == null)
		{
			ranked = Collections.emptyList();
			clearDestination();
			return;
		}
		refresh();
		ScoredLocation best = best();
		if (config.autoDestination())
		{
			setDestination(best == null ? null : best.getLocation());
		}
		else
		{
			clearDestination();
		}
		if (config.advisorChat() && best != null && client.getGameState() == GameState.LOGGED_IN)
		{
			String hint = "";
			if (!readiness.anythingRemembered() && !account.houseScanned())
			{
				hint = " Open your bank and stand in your house once so I know your teleports.";
			}
			else if (!readiness.anythingRemembered())
			{
				hint = " Open your bank once so I know your jewellery and tablets.";
			}
			else if (!account.houseScanned())
			{
				hint = " Stand in your house once so I know its portals.";
			}
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=00a0ff>Slayer Suite:</col> Best spot for you: " + best.getLocation().getName()
				+ (best.getReasons().isEmpty() ? "" : " (" + String.join(", ", best.getReasons()) + ")") + "." + hint, null);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() == InventoryID.BANK && tracker.hasTask())
		{
			long now = System.currentTimeMillis();
			if (now - lastBankRefresh > 5_000L)
			{
				lastBankRefresh = now;
				refresh();
			}
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!SlayerSuiteConfig.GROUP.equals(e.getGroup()) || e.getKey() == null)
		{
			return;
		}
		String k = e.getKey();
		if (k.startsWith("filter") || k.equals("hideLocked") || k.equals("avoidWilderness") || k.equals("preferCannon"))
		{
			refresh();
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		LocationRecord focus = destination;
		if (focus == null)
		{
			ScoredLocation b = best();
			focus = b == null ? null : b.getLocation();
		}
		if (focus != null)
		{
			if (destination != null)
			{
				cachedDistance = distanceFromPlayer();
				cachedDirection = directionFromPlayer();
			}
			else
			{
				cachedDistance = -1;
				cachedDirection = "";
			}
			if (cachedTravelFor == null || !sameLocation(cachedTravelFor, focus) || tick % 10 == 0)
			{
				cachedTravel = travelOptions(focus);
				cachedTravelFor = focus;
			}
			nextStep = destination != null ? computeNextStep(destination) : "";
		}
		else
		{
			cachedDistance = -1;
			cachedDirection = "";
			cachedTravel = Collections.emptyList();
			cachedTravelFor = null;
			nextStep = "";
		}
		if (++tick % 5 != 0)
		{
			return;
		}
		if (ranked.isEmpty() && tracker.getRecord() != null)
		{
			refresh();
		}
		LocationRecord d = destination;
		if (d != null)
		{
			boolean near = nearSpawn(d);
			if (near && !arrived)
			{
				arrived = true;
				updatePin();
				handToShortestPath();
				notifyListener();
			}
			else if (!near && arrived && tilesToNearestSpawn(d) > ARRIVE_TILES * 3)
			{
				// wandered well away from the spawns again: guide back
				arrived = false;
				updatePin();
				handToShortestPath();
				notifyListener();
			}
		}
	}
}
