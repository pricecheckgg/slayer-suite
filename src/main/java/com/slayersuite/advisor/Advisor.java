package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.route.Packed;
import com.slayersuite.route.Planner;
import com.slayersuite.route.RouteService;
import com.slayersuite.route.Router;
import com.slayersuite.route.Transport;
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
	private final RouteService routes;

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
	public Advisor(Client client, SlayerSuiteConfig config, TaskTracker tracker, AccountState account, TravelData travel, ReadinessChecker readiness, WorldMapPointManager mapPoints, EventBus eventBus, RouteService routes)
	{
		this.eventBus = eventBus;
		this.routes = routes;
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

	/** Supplies your per-spot rates for a task key; set by the plugin at startup. */
	private volatile java.util.function.Function<String, java.util.Map<String, double[]>> ownRatesSource;

	public void setOwnRatesSource(java.util.function.Function<String, java.util.Map<String, double[]>> source)
	{
		ownRatesSource = source;
	}

	private LocationScorer.Options options()
	{
		LocationScorer.Options o = new LocationScorer.Options();
		o.avoidWilderness = config.avoidWilderness() && !wildernessTask();
		TaskRecord rec = tracker.getRecord();
		java.util.function.Function<String, java.util.Map<String, double[]>> src = ownRatesSource;
		if (rec != null && src != null)
		{
			o.ownRates = src.apply(rec.getKey());
		}
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
		// a spot with no floor recorded may lie on any floor (some basements are floor 3 in the game)
		if (loc.getPlane() != null && wp.getPlane() != loc.getPlane())
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

	/** Within this many ground tiles of a spot counts as having come to it: a dungeon's door can be this far from its spawns. */
	static final int NEARBY_TILES = 120;
	/** The pinned spot must be at least this far away before a nearer spot takes the pin. */
	static final int FAR_TILES = 200;

	/**
	 * Tiles from the point to the spot's nearest pin or its centre, with dungeons folded onto the
	 * ground above them, so a cave mouth is close to the cave below it; -1 without coordinates.
	 */
	static int foldedDistance(LocationRecord loc, WorldPoint wp)
	{
		if (loc == null || wp == null)
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
				int d = (int) Math.round(Math.hypot(pin.get(0) - wp.getX(), surfaceY(pin.get(1)) - surfaceY(wp.getY())));
				if (best < 0 || d < best)
				{
					best = d;
				}
			}
		}
		if (best < 0 && loc.hasCoordinates())
		{
			best = (int) Math.round(Math.hypot(loc.getX() - wp.getX(), surfaceY(loc.getY()) - surfaceY(wp.getY())));
		}
		return best;
	}

	/**
	 * Another spot of the task you have come to (its door, or the ground above it) while the pinned
	 * one is far away: you chose to go there, so the pin follows. Null when the pin should stay.
	 * Client thread.
	 */
	private LocationRecord spotAtHand(LocationRecord pinned)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		WorldPoint wp = me.getWorldLocation();
		LocationRecord best = null;
		int bestD = Integer.MAX_VALUE;
		for (ScoredLocation s : ranked)
		{
			LocationRecord loc = s.getLocation();
			if (loc == null || s.isLocked() || Boolean.TRUE.equals(loc.getBoss()) || sameLocation(loc, pinned))
			{
				continue;
			}
			int d = foldedDistance(loc, wp);
			if (d >= 0 && d <= NEARBY_TILES && d < bestD)
			{
				bestD = d;
				best = loc;
			}
		}
		if (best == null)
		{
			return null;
		}
		int pinnedD = foldedDistance(pinned, wp);
		// the pinned spot stays while it is near too, or not clearly further than the one you are at
		if (pinnedD >= 0 && pinnedD <= Math.max(FAR_TILES, 3 * bestD))
		{
			return null;
		}
		return best;
	}

	public void setDestination(LocationRecord loc)
	{
		destination = loc;
		arrived = loc != null && nearSpawn(loc);
		updatePin();
		updateRoute();
		notifyListener();
	}

	public void clearDestination()
	{
		destination = null;
		arrived = false;
		updatePin();
		updateRoute();
		notifyListener();
	}

	/** Gives the built-in router the pinned spot and the teleports that could start the trip, or nothing once you are there. */
	private void updateRoute()
	{
		WorldPoint wp = arrived ? null : destinationPoint();
		WorldPoint cannon = wp == null ? null : cannonPoint();
		routes.setTarget(cannon != null ? cannon : wp, wp == null ? Collections.emptyList() : landings(destination));
	}

	/** Usable teleports for the spot as landings with a tick cost: a cast or click, more when a bank or spellbook swap comes first. */
	private List<Planner.Landing> landings(LocationRecord loc)
	{
		List<Planner.Landing> out = new ArrayList<>();
		for (TravelOption o : travelOptions(loc))
		{
			if (!o.isUsable() || o.getLanding() == null)
			{
				continue;
			}
			String st = o.getStatus() == null ? "" : o.getStatus();
			int ticks = 10;
			if (st.startsWith("in bank") || st.startsWith("seen in your bank"))
			{
				ticks = 90;
			}
			else if (st.startsWith("needs the"))
			{
				ticks = 120;
			}
			else if (st.startsWith("in your house"))
			{
				ticks = st.contains("no quick way") ? 60 : 18;
			}
			out.add(new Planner.Landing(o.getLabel(), st, Packed.of(o.getLanding()[0], o.getLanding()[1], 0), ticks));
		}
		// A fairy ring or spirit tree in your house (or the combined tree) reaches every stop of its
		// network: the few stops nearest the spot become landings, home teleport and ride included.
		if (loc != null && loc.hasCoordinates() && account.houseScanned())
		{
			int goal = Packed.of(loc.getX(), loc.getY(), loc.getPlane() == null ? 0 : loc.getPlane());
			int home = account.canTeleportHome() ? 18 : 60;
			if (account.hasHouseFacility("fairy ring") && account.fairyRingsUsable())
			{
				out.addAll(houseLandings("House fairy ring", Transport.Kind.FAIRY_RING, goal, home + 5));
			}
			if (account.hasHouseFacility("spirit tree") && account.questFinished("TREE_GNOME_VILLAGE"))
			{
				out.addAll(houseLandings("House spirit tree", Transport.Kind.SPIRIT_TREE, goal, home + 6));
			}
		}
		return out;
	}

	private List<Planner.Landing> houseLandings(String label, Transport.Kind kind, int goal, int ticks)
	{
		List<Transport> stops = new ArrayList<>(routes.stops(kind));
		stops.sort(java.util.Comparator.comparingInt(s -> Math.max(Math.abs(Packed.x(s.origin) - Packed.x(goal)), Math.abs(surfaceY(Packed.y(s.origin)) - surfaceY(Packed.y(goal))))));
		List<Planner.Landing> out = new ArrayList<>();
		for (int i = 0; i < stops.size() && i < 6; i++)
		{
			String where = stops.get(i).display.replace(" ", "");
			out.add(new Planner.Landing(label + (where.isEmpty() ? "" : " to " + where), "in your house", stops.get(i).origin, ticks));
		}
		return out;
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
		WorldPoint dp = guidePoint();
		if (me0 != null && dp != null && dist >= 0 && dist <= 60 && underground(dp.getY()) != underground(me0.getWorldLocation().getY()))
		{
			return underground(dp.getY()) ? "Go down into " + dest.getName() + "; the entrance is within " + Math.max(dist, 5) + " tiles " + dir
				: "Come up to the surface; " + dest.getName() + " is " + Math.max(dist, 5) + " tiles " + dir + " from here";
		}
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
		// The built-in plan knows the real walk, doors, stairs and caves included, and whether a teleport beats it.
		Planner.Plan plan = routes.plan();
		if (plan != null && !plan.reached() && detached() && dist >= 0 && dist <= 60)
		{
			return "The way in from here is not in the map yet: use the entrance once and it is learned";
		}
		if (plan != null && plan.reached())
		{
			int walk = routes.remainingTiles();
			List<Router.Step> steps = routes.remainingSteps();
			String rest;
			if (!steps.isEmpty())
			{
				Router.Step s = steps.get(0);
				int to = Math.max(0, s.index - routes.from());
				int after = walk - to;
				rest = (to <= 1 ? "" : "walk " + to + " tiles, then ") + s.transport.label() + (after > 1 ? ", then " + TripMath.formatK(after) + " more" : "");
			}
			else
			{
				rest = walk <= 1 ? "you are there" : "walk " + TripMath.formatK(walk) + " tiles";
				if (cannonPoint() != null)
				{
					rest += walk <= 1 ? ": set the cannon here" : " and set the cannon at the marked tile";
				}
			}
			String time = plan.ticks > 0 ? " (about " + TaskTracker.formatDuration(plan.ticks * 600L) + ")" : "";
			if (plan.teleport != null && !routes.onRoute())
			{
				String how = plan.teleport.label.replace("House: ", "house ");
				String where = plan.teleport.status.startsWith("in bank") || plan.teleport.status.startsWith("seen") ? " (it is in your bank)"
					: plan.teleport.status.startsWith("needs") ? " (" + plan.teleport.status + ")" : "";
				return "Use " + how + where + ", then " + rest + time;
			}
			return Character.toUpperCase(rest.charAt(0)) + rest.substring(1) + (steps.isEmpty() && walk > 1 ? "; follow the chevrons" : "") + time;
		}
		if (dist <= config.floorPathMaxDistance() || nearLanding)
		{
			return "Walk " + TripMath.formatK(dist) + " tiles " + dir + (dist <= config.floorPathMaxDistance() ? "; follow the arrow" : "");
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

	/** Beyond this, a spot's coordinates lie in a detached map area and its travel landing guides instead. */
	private static final int DETACHED_TILES = 400;

	/**
	 * Where the compass and the floor line point: the spot itself, or its travel landing when the
	 * spot's coordinates sit in a detached map area (some dungeons are stored far from the ground
	 * they are entered from). The router always gets the spot itself.
	 */
	public WorldPoint guidePoint()
	{
		WorldPoint dest = destinationPoint();
		LocationRecord loc = destination;
		if (dest == null || loc == null)
		{
			return dest;
		}
		int[] anchor = travel.anchor(loc.getName());
		if (anchor == null)
		{
			return dest;
		}
		double away = Math.hypot(anchor[0] - dest.getX(), surfaceY(anchor[1]) - surfaceY(dest.getY()));
		return away > DETACHED_TILES ? new WorldPoint(anchor[0], anchor[1], 0) : dest;
	}

	/**
	 * Where to set the cannon at the pinned spot: the tile covering the most spawns within its
	 * reach, when you own one, prefer cannon spots, the spot allows a cannon and the monsters are
	 * not immune. Null otherwise.
	 */
	public WorldPoint cannonPoint()
	{
		LocationRecord loc = destination;
		com.slayersuite.data.TaskRecord rec = tracker.getRecord();
		if (loc == null || !config.preferCannon() || !account.ownsCannon() || !Boolean.TRUE.equals(loc.getCannon()) || (rec != null && rec.cannonImmune()))
		{
			return null;
		}
		int[] tile = loc.cannonTile();
		return tile == null ? null : new WorldPoint(tile[0], tile[1], loc.getPlane() == null ? 0 : loc.getPlane());
	}

	/** True when the spot is guided by its travel landing rather than its own coordinates. */
	public boolean detached()
	{
		WorldPoint dest = destinationPoint();
		WorldPoint guide = guidePoint();
		return dest != null && guide != null && !guide.equals(dest);
	}

	/** Distance in tiles from the player to the destination, ignoring plane; -1 when unknown. Client thread only. */
	public int distanceFromPlayer()
	{
		WorldPoint dest = guidePoint();
		Player me = client.getLocalPlayer();
		if (dest == null || me == null)
		{
			return -1;
		}
		WorldPoint wp = me.getWorldLocation();
		return (int) Math.round(Math.hypot(dest.getX() - wp.getX(), surfaceY(dest.getY()) - surfaceY(wp.getY())));
	}

	/** Dungeons are stored 6400 tiles north of the ground above them; fold that away for distances and headings. */
	static int surfaceY(int y)
	{
		return y >= 6400 ? y - 6400 : y;
	}

	private static boolean underground(int y)
	{
		return y >= 6400;
	}

	/** Eight-point compass direction from the player to the destination. */
	public String directionFromPlayer()
	{
		WorldPoint dest = guidePoint();
		Player me = client.getLocalPlayer();
		if (dest == null || me == null)
		{
			return "";
		}
		WorldPoint wp = me.getWorldLocation();
		return compass(dest.getX() - wp.getX(), surfaceY(dest.getY()) - surfaceY(wp.getY()));
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
			else if (t.getItem() != null && t.getItem().equalsIgnoreCase("Spirit tree"))
			{
				boolean trees = account.questFinished("TREE_GNOME_VILLAGE");
				boolean thisTree = t.getQuest() == null || account.questStarted(t.getQuest());
				usable = trees && thisTree;
				status = !trees ? "needs Tree Gnome Village" : !thisTree ? "needs " + LocationScorer.pretty(t.getQuest()) + " started" : "spirit tree";
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
			out.add(new TravelOption(t.label(), hops, usable, status, TravelData.landing(t, e)));
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
		routes.setFairyRingsUsable(account.fairyRingsUsable());
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
		LocationRecord here = tracker.getCurrentLocation();
		if (here != null && nearSpawn(here) && (destination == null || !sameLocation(here, destination)))
		{
			// you reached one of the task's spots, pinned or not: that is the destination now
			destination = here;
			arrived = true;
			updatePin();
			updateRoute();
			notifyListener();
		}
		LocationRecord pinned = destination;
		if (pinned != null && !arrived)
		{
			LocationRecord atHand = spotAtHand(pinned);
			if (atHand != null)
			{
				// standing at the way into another of the task's spots: that is where you are going
				destination = atHand;
				arrived = nearSpawn(atHand);
				updatePin();
				updateRoute();
				notifyListener();
			}
		}
		LocationRecord d = destination;
		if (d != null)
		{
			boolean near = nearSpawn(d);
			if (near && !arrived)
			{
				arrived = true;
				updatePin();
				updateRoute();
				notifyListener();
			}
			else if (!near && arrived && tilesToNearestSpawn(d) > ARRIVE_TILES * 3)
			{
				// wandered well away from the spawns again: guide back
				arrived = false;
				updatePin();
				updateRoute();
				notifyListener();
			}
		}
	}
}
