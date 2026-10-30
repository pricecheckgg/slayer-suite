package com.slayersuite.route;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.inject.Inject;
import javax.inject.Singleton;
import com.slayersuite.SlayerSuiteConfig;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;

/**
 * Keeps a plan from you to the pinned spot: walk, or teleport first and walk from the landing.
 * The map and the transport tables load once on a background thread, searches run there too,
 * and each tick the route is trimmed to where you stand or the plan is remade when you have
 * left it. Which transports you may use is decided on the client thread from your levels,
 * quests and var values, and refreshed every minute.
 */
@Slf4j
@Singleton
public class RouteService
{
	private static final int MAX_EXPANSIONS = 1_500_000;
	private static final long SEARCH_BUDGET_NANOS = 4_000_000_000L;
	private static final long REQUEST_GAP_MS = 1_200L;
	private static final int OFF_ROUTE_TILES = 2;
	private static final int SNAPSHOT_EVERY_TICKS = 100;

	private final Client client;
	private final SlayerSuiteConfig config;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "slayer-suite-route");
		t.setDaemon(true);
		return t;
	});

	private volatile WorldMap map;
	private volatile TransportTable table;
	/** Transports learned before the tables were ready, applied once they are. */
	private final List<Transport> extra = Collections.synchronizedList(new ArrayList<>());
	private volatile boolean loadFailed;
	private volatile Set<Transport> usable = Collections.emptySet();
	private volatile Planner.Plan plan;
	private volatile Router.Route route;
	private volatile int from;
	private volatile boolean onRoute;
	/** True while the chevrons follow the plain walk although the plan names a quicker teleport. */
	private volatile boolean showingWalk;
	private volatile WorldPoint target;
	private volatile List<Planner.Landing> landings = Collections.emptyList();
	private Future<?> pending;
	private Future<?> estimating;
	private long lastRequestMs;
	private int tick;
	private int snapshotTick = -1;
	private volatile boolean fairyRings;
	private int lastTile = Integer.MIN_VALUE;
	/** The tick you logged in; the first snapshot after login is retaken once the game has sent your quest and var state. */
	private int loginTick = -1;

	@Inject
	public RouteService(Client client, SlayerSuiteConfig config)
	{
		this.client = client;
		this.config = config;
	}

	/** Starts loading the map; cheap to call again. */
	public void start()
	{
		if (map == null && !loadFailed)
		{
			executor.submit(() ->
			{
				try
				{
					long t0 = System.currentTimeMillis();
					WorldMap m = WorldMap.load();
					TransportTable t = TransportTable.load();
					synchronized (extra)
					{
						if (!extra.isEmpty())
						{
							t = t.withAdded(new ArrayList<>(extra));
						}
					}
					table = t;
					map = m;
					log.debug("Slayer Suite: routing data ready in {} ms ({} regions, {} transports)", System.currentTimeMillis() - t0, m.regionCount(), t.size());
				}
				catch (RuntimeException e)
				{
					loadFailed = true;
					log.warn("Slayer Suite: routing data could not be loaded", e);
				}
			});
		}
	}

	public void stop()
	{
		executor.shutdownNow();
		plan = null;
		route = null;
		target = null;
	}

	public boolean ready()
	{
		return map != null && table != null;
	}

	public WorldMap map()
	{
		return map;
	}

	public TransportTable table()
	{
		return table;
	}

	/** The stops of a network you may use, or none until the tables are loaded. */
	public List<Transport> stops(Transport.Kind kind)
	{
		TransportTable t = table;
		if (t == null)
		{
			return Collections.emptyList();
		}
		List<Transport> out = new ArrayList<>();
		for (Transport s : t.stops(kind))
		{
			if (usable.contains(s))
			{
				out.add(s);
			}
		}
		return out;
	}

	/** Whether you can use fairy rings (Fairytale II started with a staff, or the Lumbridge elite diary). Client thread. */
	public void setFairyRingsUsable(boolean usable)
	{
		if (usable != fairyRings)
		{
			fairyRings = usable;
			snapshotTick = -1;
		}
	}

	/** Adds transports found by using them; they need nothing and are usable at once. Client thread. */
	public void addTransports(List<Transport> found)
	{
		TransportTable t = table;
		if (t == null)
		{
			extra.addAll(found);
			return;
		}
		table = t.withAdded(found);
		Set<Transport> u = new HashSet<>(usable);
		u.addAll(found);
		usable = u;
		plan = null;
		route = null;
	}

	/** The spot to route to, or null for none, with the teleports that could start the trip. Client thread. */
	public void setTarget(WorldPoint wp, List<Planner.Landing> teleports)
	{
		WorldPoint old = target;
		List<Planner.Landing> l = teleports == null ? Collections.emptyList() : teleports;
		boolean sameSpot = wp == null ? old == null : wp.equals(old);
		boolean sameTeleports = l.size() == landings.size();
		for (int i = 0; sameTeleports && i < l.size(); i++)
		{
			sameTeleports = l.get(i).tile == landings.get(i).tile && l.get(i).ticks == landings.get(i).ticks;
		}
		if (sameSpot && sameTeleports)
		{
			return;
		}
		target = wp;
		landings = l;
		plan = null;
		route = null;
		from = 0;
		onRoute = false;
		showingWalk = false;
		if (wp != null)
		{
			request(true);
		}
	}

	public void setTarget(WorldPoint wp)
	{
		setTarget(wp, Collections.emptyList());
	}

	/** A spot to cost from where you stand: a key to report it under, its tile, and the teleports that could start the trip. */
	public static final class Target
	{
		public final String key;
		public final int tile;
		public final List<Planner.Landing> landings;

		public Target(String key, int tile, List<Planner.Landing> landings)
		{
			this.key = key;
			this.tile = tile;
			this.landings = landings == null ? Collections.emptyList() : landings;
		}
	}

	private static final int ESTIMATE_EXPANSIONS = 300_000;
	private static final long ESTIMATE_BUDGET_NANOS = 800_000_000L;
	/** The whole batch of estimates shares this much of the executor; the pin's own route must not wait behind them. */
	private static final long ESTIMATE_BATCH_NANOS = 1_500_000_000L;

	/**
	 * Costs each target from where you stand on the background thread, teleports and rides weighed
	 * like a real plan, and hands back ticks per key, -1 where nothing reaches. Client thread.
	 */
	public void estimate(List<Target> targets, java.util.function.Consumer<Map<String, Integer>> done)
	{
		if (!client.isClientThread())
		{
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null || targets.isEmpty() || !ready() || client.getGameState() != GameState.LOGGED_IN || inInstance())
		{
			return;
		}
		if (snapshotTick < 0 || tick - snapshotTick >= SNAPSHOT_EVERY_TICKS)
		{
			usable = computeUsable();
			snapshotTick = tick;
		}
		if (estimating != null && !estimating.isDone())
		{
			estimating.cancel(true);
		}
		final int start = Packed.of(me.getWorldLocation());
		final Set<Transport> allowed = usable;
		final WorldMap m = map;
		final TransportTable tt = table;
		final boolean keepOut = config.avoidWilderness();
		final List<Target> list = new ArrayList<>(targets);
		final long deadline = System.nanoTime() + ESTIMATE_BATCH_NANOS;
		estimating = executor.submit(() ->
		{
			Map<String, Integer> out = new HashMap<>();
			for (Target t : list)
			{
				if (Thread.currentThread().isInterrupted())
				{
					return;
				}
				long left = deadline - System.nanoTime();
				if (left <= 0)
				{
					out.put(t.key, -1);
					continue;
				}
				try
				{
					int goal = Router.walkableNear(m, t.tile);
					Planner.Plan made = Planner.plan(m, tt, allowed, start, goal, t.landings, keepOut, ESTIMATE_EXPANSIONS, Math.min(ESTIMATE_BUDGET_NANOS, left), 0);
					out.put(t.key, made != null && made.reached() ? made.ticks : -1);
				}
				catch (RuntimeException e)
				{
					out.put(t.key, -1);
				}
			}
			if (!Thread.currentThread().isInterrupted())
			{
				done.accept(out);
			}
		});
	}

	/** The current plan, or null while none is known. */
	public Planner.Plan plan()
	{
		return plan;
	}

	/** True while you stand on (or beside) the route; false before a teleport the plan asks for. */
	public boolean onRoute()
	{
		return onRoute;
	}

	/** True while the chevrons show the plain walk and the plan's teleport is only offered as quicker. */
	public boolean showingWalk()
	{
		return showingWalk;
	}

	public WorldPoint getTarget()
	{
		return target;
	}

	/** The current route, or null while none is known. */
	public Router.Route current()
	{
		return route;
	}

	/** Index of your tile on the route. */
	public int from()
	{
		return from;
	}

	/** Tiles still to walk; 0 when there is no route. */
	public int remainingTiles()
	{
		Router.Route r = route;
		return r == null ? 0 : Math.max(0, r.tiles.size() - 1 - from);
	}

	/** The transports still ahead, with their index on the route. */
	public List<Router.Step> remainingSteps()
	{
		Router.Route r = route;
		if (r == null)
		{
			return Collections.emptyList();
		}
		int f = from;
		List<Router.Step> out = new ArrayList<>();
		for (Router.Step s : r.steps)
		{
			if (s.index >= f)
			{
				out.add(s);
			}
		}
		return out;
	}

	/** The fairy ring code the route wants next: the first ring ride ahead, else a house ring landing's code; null when none. */
	public String nextFairyCode()
	{
		for (Router.Step s : remainingSteps())
		{
			if (s.transport.kind == Transport.Kind.FAIRY_RING && s.transport.display != null && s.transport.display.matches("[A-D][IJKL][PQRS]"))
			{
				return s.transport.display;
			}
		}
		Planner.Plan p = plan;
		if (p != null && p.teleport != null && p.teleport.label != null && !onRoute)
		{
			java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)fairy ring to ([A-D][IJKL][PQRS])\\b").matcher(p.teleport.label);
			if (m.find())
			{
				return m.group(1).toUpperCase(java.util.Locale.ROOT);
			}
		}
		return null;
	}

	/** True when the route gets all the way to the target. */
	public boolean reached()
	{
		Router.Route r = route;
		return r != null && r.reached;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGIN_SCREEN || e.getGameState() == GameState.HOPPING)
		{
			// the pin stays; the way there is searched again once you are back in the world
			plan = null;
			route = null;
			onRoute = false;
			lastTile = Integer.MIN_VALUE;
		}
		else if (e.getGameState() == GameState.LOGGED_IN)
		{
			loginTick = tick;
		}
	}

	private boolean inInstance()
	{
		WorldView wv = client.getTopLevelWorldView();
		return wv != null && wv.isInstance();
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		tick++;
		if (target == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!ready())
		{
			if (tick % 5 == 0)
			{
				start();
			}
			return;
		}
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		if (loginTick >= 0 && tick - loginTick == 8)
		{
			// quests and vars arrive a few ticks after login: take the snapshot again and plan afresh
			snapshotTick = -1;
			request(true);
			return;
		}
		if (inInstance())
		{
			// the map has no instances; the search over the loaded area stands in until you leave
			plan = null;
			route = null;
			onRoute = false;
			return;
		}
		int here = Packed.of(me.getWorldLocation());
		boolean jumped = lastTile != Integer.MIN_VALUE && (Packed.plane(here) != Packed.plane(lastTile) || Packed.chebyshev(here, lastTile) > 25);
		lastTile = here;
		Planner.Plan p = plan;
		Router.Route r = route;
		if (jumped && (r == null || r.indexOf(here) < 0))
		{
			// you teleported, rode or climbed somewhere the route did not expect: plan again from here now
			request(true);
			return;
		}
		if (p == null || r == null)
		{
			request(false);
			return;
		}
		int idx = r.indexOf(here);
		if (idx >= 0)
		{
			from = idx;
			onRoute = true;
			return;
		}
		// beside the route is still on it (a ride lands you next to its stop); further away means a new plan
		int nearest = Integer.MAX_VALUE;
		int nearestAt = -1;
		int plane = Packed.plane(here);
		for (int i = 0; i < r.tiles.size(); i++)
		{
			int tile = r.tiles.get(i);
			if (Packed.plane(tile) != plane)
			{
				continue;
			}
			int d = Packed.chebyshev(tile, here);
			if (d < nearest || (d == nearest && i > from))
			{
				nearest = d;
				nearestAt = i;
			}
		}
		if (nearest <= OFF_ROUTE_TILES)
		{
			from = nearestAt;
			onRoute = true;
			return;
		}
		onRoute = false;
		if (p.teleport != null && Packed.chebyshev(here, p.start) <= 25 && Packed.plane(here) == Packed.plane(p.start))
		{
			// still where the plan was made, waiting to teleport
			return;
		}
		request(false);
	}

	/** Searches from where you stand to the target on the background thread. Client thread. */
	private void request(boolean force)
	{
		WorldPoint to = target;
		Player me = client.getLocalPlayer();
		if (to == null || me == null || !ready())
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (!force && (now - lastRequestMs < REQUEST_GAP_MS || (pending != null && !pending.isDone())))
		{
			return;
		}
		if (pending != null && !pending.isDone())
		{
			pending.cancel(true);
		}
		lastRequestMs = now;
		if (snapshotTick < 0 || tick - snapshotTick >= SNAPSHOT_EVERY_TICKS)
		{
			usable = computeUsable();
			snapshotTick = tick;
		}
		if (inInstance())
		{
			return;
		}
		final int start = Packed.of(me.getWorldLocation());
		final int goal = Router.walkableNear(map, Packed.of(to));
		final Set<Transport> allowed = usable;
		final WorldMap m = map;
		final TransportTable tt = table;
		final boolean keepOut = config.avoidWilderness();
		final int walkUpTo = Math.max(100, config.floorPathMaxDistance());
		final List<Planner.Landing> l = landings;
		pending = executor.submit(() ->
		{
			try
			{
				Planner.Plan made = Planner.plan(m, tt, allowed, start, goal, l, keepOut, MAX_EXPANSIONS, SEARCH_BUDGET_NANOS, walkUpTo);
				if (to.equals(target))
				{
					from = 0;
					// whenever a walk exists it is drawn, even when a teleport is quicker: the chevrons
					// are what people follow, and the card still names the teleport
					boolean walkShown = made.teleport != null && made.walk != null && made.walk.reached;
					showingWalk = walkShown;
					onRoute = made.teleport == null || walkShown;
					route = walkShown ? made.walk : made.route;
					plan = made;
				}
			}
			catch (RuntimeException e)
			{
				log.debug("Slayer Suite: route search failed", e);
			}
		});
	}

	/** The transports whose levels, quests and var conditions you meet. Client thread. */
	private Set<Transport> computeUsable()
	{
		TransportTable tt = table;
		if (tt == null)
		{
			return Collections.emptySet();
		}
		Map<Quest, Boolean> done = new HashMap<>();
		Set<Transport> out = new HashSet<>(tt.size());
		for (Transport t : tt.all())
		{
			if (t.needsItems || !skillsMet(t) || !questsMet(t, done) || !varsMet(t))
			{
				continue;
			}
			if (t.kind == Transport.Kind.FAIRY_RING && !fairyRings)
			{
				continue;
			}
			out.add(t);
		}
		return out;
	}

	private boolean skillsMet(Transport t)
	{
		for (Map.Entry<Skill, Integer> e : t.skills.entrySet())
		{
			if (client.getRealSkillLevel(e.getKey()) < e.getValue())
			{
				return false;
			}
		}
		return true;
	}

	private boolean questsMet(Transport t, Map<Quest, Boolean> done)
	{
		for (Quest q : t.quests)
		{
			Boolean finished = done.get(q);
			if (finished == null)
			{
				try
				{
					finished = q.getState(client) == QuestState.FINISHED;
				}
				catch (RuntimeException e)
				{
					finished = false;
				}
				done.put(q, finished);
			}
			if (!finished)
			{
				return false;
			}
		}
		return true;
	}

	private boolean varsMet(Transport t)
	{
		for (Transport.VarCheck c : t.varbits)
		{
			if (!c.holds(client.getVarbitValue(c.id)))
			{
				return false;
			}
		}
		for (Transport.VarCheck c : t.varplayers)
		{
			if (!c.holds(client.getVarpValue(c.id)))
			{
				return false;
			}
		}
		return true;
	}
}
