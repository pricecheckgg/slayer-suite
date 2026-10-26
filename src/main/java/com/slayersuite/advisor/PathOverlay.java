package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.task.SlayerMode;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Arrows on the floor from you toward the pinned spot, through the loaded map's walls and
 * obstacles. Doors on the way are crossed and marked with the action that opens them. When
 * the spot is on another floor or underground, the nearest staircase, ladder or cave
 * entrance that gets you there becomes the target and is marked too. The Shortest Path
 * plugin, when on, draws its own full route alongside.
 */
public class PathOverlay extends Overlay
{
	private static final Color ARROW_EDGE = new Color(0, 0, 0, 110);
	/** Milliseconds for a lit pulse to move one tile along the route. */
	private static final long PULSE_MS = 110L;
	/** Tiles between one pulse and the next. */
	private static final int PULSE_EVERY = 12;
	/** Half-size of a chevron in local units; a tile is 128 across. */
	private static final double CHEVRON = 46;
	private static final Color END = new Color(255, 215, 80, 200);
	private static final Color END_FILL = new Color(255, 215, 80, 70);
	private static final Color BLOCKED = new Color(255, 90, 90, 200);
	private static final Color BLOCKED_FILL = new Color(255, 90, 90, 70);
	private static final Color DOOR = new Color(255, 215, 80, 220);
	private static final Color DOOR_FILL = new Color(255, 215, 80, 50);
	private static final Color ENTRANCE = new Color(80, 220, 255, 230);
	private static final Color ENTRANCE_FILL = new Color(80, 220, 255, 50);

	/** Something on the route you have to click: a door to open, a tapestry to walk through, a cave to enter. */
	private static final class Obstacle
	{
		final TileObject object;
		final String action;
		final String name;
		final WorldPoint tile;

		Obstacle(TileObject object, String action, String name, WorldPoint tile)
		{
			this.object = object;
			this.action = action;
			this.name = name;
			this.tile = tile;
		}

		String label()
		{
			return action + " " + name;
		}
	}

	/** An entrance further than this from the spot (dungeons folded onto the ground above them) leads somewhere else. */
	private static final int ENTRANCE_REACH = 150;

	private static final String[] DOOR_ACTIONS = {"Open", "Walk-through", "Pass", "Go-through", "Pass-through", "Squeeze-through", "Crawl-through", "Jump-over", "Climb-over"};
	private static final String[] DOWN_ACTIONS = {"Climb-down", "Descend", "Enter", "Jump-in", "Crawl-through", "Squeeze-through", "Walk-down", "Climb"};
	private static final String[] UP_ACTIONS = {"Climb-up", "Ascend", "Climb", "Walk-up", "Exit", "Leave", "Climb-out"};

	private final Client client;
	private final Advisor advisor;
	private final SlayerSuiteConfig config;
	private final SlayerMode mode;
	private final Map<Integer, ObjectComposition> defs = new HashMap<>();

	private volatile List<WorldPoint> path = Collections.emptyList();
	private volatile List<Obstacle> obstacles = Collections.emptyList();
	private volatile Obstacle entrance;
	/** True when the line reaches a spawn point; false when it stops short at a shortcut, stairs or the edge. */
	@lombok.Getter
	private volatile boolean reachesTarget;
	private int lastSx = -1;
	private int lastSy = -1;
	private WorldPoint lastDest;

	@Inject
	public PathOverlay(Client client, Advisor advisor, SlayerSuiteConfig config, SlayerMode mode)
	{
		this.client = client;
		this.advisor = advisor;
		this.config = config;
		this.mode = mode;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_LOW);
	}

	/** Non-empty while the line stops short of the spot. */
	public boolean blocked()
	{
		return !path.isEmpty() && !reachesTarget && entrance == null && advisor.getDestination() != null && !advisor.isArrived();
	}

	/** "Climb-up Staircase" when the way to the spot goes through something you must click first; null otherwise. */
	public String entranceLabel()
	{
		Obstacle e = entrance;
		return e == null ? null : e.label();
	}

	/** Tiles from you to the entrance or the spot; -1 when there is no line. */
	public int lineTiles()
	{
		List<WorldPoint> p = path;
		return p.isEmpty() ? -1 : p.size() - 1;
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (!config.floorPath() || client.getGameState() != GameState.LOGGED_IN)
		{
			clear();
			return;
		}
		WorldPoint dest = advisor.destinationPoint();
		Player me = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (dest == null || advisor.isArrived() || me == null || wv == null || !mode.active())
		{
			clear();
			lastDest = null;
			return;
		}
		LocalPoint lp = me.getLocalLocation();
		if (lp == null)
		{
			return;
		}
		int sx = lp.getSceneX();
		int sy = lp.getSceneY();
		if (sx == lastSx && sy == lastSy && dest.equals(lastDest) && !path.isEmpty())
		{
			return;
		}
		lastSx = sx;
		lastSy = sy;
		lastDest = dest;
		compute(wv, me.getWorldLocation(), sx, sy, dest);
	}

	private void clear()
	{
		path = Collections.emptyList();
		obstacles = Collections.emptyList();
		entrance = null;
	}

	private ObjectComposition def(int id)
	{
		ObjectComposition c = defs.get(id);
		if (c == null)
		{
			c = client.getObjectDefinition(id);
			if (c != null && c.getImpostorIds() != null)
			{
				try
				{
					c = c.getImpostor();
				}
				catch (RuntimeException ignored)
				{
					// keep the base definition
				}
			}
			if (c != null)
			{
				defs.put(id, c);
			}
		}
		return c;
	}

	private static String firstAction(ObjectComposition c, String[] wanted)
	{
		String[] actions = c.getActions();
		if (actions == null)
		{
			return null;
		}
		for (String w : wanted)
		{
			for (String a : actions)
			{
				if (a != null && a.equalsIgnoreCase(w))
				{
					return a;
				}
			}
		}
		return null;
	}

	private static final String[] ENTRANCE_WORDS = {"cave", "entrance", "trapdoor", "ladder", "stair", "hole", "crevice", "tunnel", "passage", "well", "rope", "crack", "hatch", "chasm", "pit", "opening", "grate", "vine", "cavern", "shaft", "dungeon", "tomb", "crypt", "ramp", "lift", "elevator", "whirlpool", "manhole", "mine", "climb", "doorway", "steps"};

	/** Named like a way down or up, not a portal, a domain or a random thing with an Enter option. */
	private static boolean looksLikeEntrance(String name)
	{
		String n = name.toLowerCase(Locale.ROOT);
		for (String w : ENTRANCE_WORDS)
		{
			if (n.contains(w))
			{
				return true;
			}
		}
		return false;
	}

	private static String firstNonNull(String[] actions)
	{
		if (actions == null)
		{
			return null;
		}
		for (String a : actions)
		{
			if (a != null && !a.isEmpty())
			{
				return a;
			}
		}
		return null;
	}

	private static boolean looksLikeDoor(String name)
	{
		String n = name.toLowerCase(Locale.ROOT);
		return n.contains("door") || n.contains("gate") || n.contains("tapestry") || n.contains("curtain") || n.contains("barrier")
			|| n.contains("fence") || n.contains("bars") || n.contains("hatch") || n.contains("portcullis") || n.contains("passage");
	}

	private void compute(WorldView wv, WorldPoint here, int sx, int sy, WorldPoint dest)
	{
		CollisionData[] maps = wv.getCollisionMaps();
		int plane = wv.getPlane();
		Scene scene = wv.getScene();
		Tile[][][] sceneTiles = scene == null ? null : scene.getTiles();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null || sceneTiles == null || plane >= sceneTiles.length || sceneTiles[plane] == null)
		{
			clear();
			return;
		}
		int[][] raw = maps[plane].getFlags();
		if (raw == null || raw.length == 0)
		{
			clear();
			return;
		}
		int size = raw.length;
		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		// Tiles that do not exist (the empty part of a house or dungeon instance) read as open; treat them as walls.
		// Doors, gates and walk-through walls are crossable and remembered so they can be marked.
		int[][] flags = new int[size][];
		boolean[][] doors = new boolean[size][size];
		Map<Integer, Obstacle> doorAt = new HashMap<>();
		List<Obstacle> ups = new ArrayList<>();
		List<Obstacle> downs = new ArrayList<>();
		List<Obstacle> known = new ArrayList<>();
		com.slayersuite.data.LocationRecord loc = advisor.getDestination();
		String[] knownEntrance = loc == null ? null : Entrances.forLocation(loc.getName());
		for (int x = 0; x < size; x++)
		{
			flags[x] = raw[x].clone();
			if (x >= sceneTiles[plane].length)
			{
				continue;
			}
			for (int y = 0; y < flags[x].length; y++)
			{
				Tile tile = y < sceneTiles[plane][x].length ? sceneTiles[plane][x][y] : null;
				if (tile == null)
				{
					flags[x][y] |= CollisionDataFlag.BLOCK_MOVEMENT_FULL;
					continue;
				}
				WorldPoint wp = new WorldPoint(x + baseX, y + baseY, plane);
				WallObject wall = tile.getWallObject();
				if (wall != null)
				{
					ObjectComposition c = def(wall.getId());
					String a = c == null ? null : firstAction(c, DOOR_ACTIONS);
					if (a != null)
					{
						doors[x][y] = true;
						doorAt.put(x * size + y, new Obstacle(wall, a, c.getName(), wp));
					}
				}
				DecorativeObject deco = tile.getDecorativeObject();
				if (deco != null)
				{
					ObjectComposition c = def(deco.getId());
					String a = c == null ? null : firstAction(c, DOOR_ACTIONS);
					if (a != null && looksLikeDoor(c.getName()))
					{
						doors[x][y] = true;
						doorAt.put(x * size + y, new Obstacle(deco, a, c.getName(), wp));
					}
				}
				GameObject[] objs = tile.getGameObjects();
				if (objs == null)
				{
					continue;
				}
				for (GameObject o : objs)
				{
					if (o == null)
					{
						continue;
					}
					ObjectComposition c = def(o.getId());
					if (c == null || c.getName() == null || c.getName().equals("null"))
					{
						continue;
					}
					String door = firstAction(c, DOOR_ACTIONS);
					if (door != null && looksLikeDoor(c.getName()))
					{
						doors[x][y] = true;
						doorAt.put(x * size + y, new Obstacle(o, door, c.getName(), wp));
						// the tile under a large door is solid in the flags; let the path step onto it
						flags[x][y] &= ~(CollisionDataFlag.BLOCK_MOVEMENT_OBJECT | CollisionDataFlag.BLOCK_MOVEMENT_FULL);
					}
					if (knownEntrance != null && c.getName().equalsIgnoreCase(knownEntrance[0]))
					{
						String want = knownEntrance[1] == null ? firstNonNull(c.getActions()) : firstAction(c, new String[]{knownEntrance[1]});
						known.add(new Obstacle(o, want == null ? "Use" : want, c.getName(), wp));
						continue;
					}
					if (!looksLikeEntrance(c.getName()))
					{
						continue;
					}
					String up = firstAction(c, UP_ACTIONS);
					if (up != null)
					{
						ups.add(new Obstacle(o, up, c.getName(), wp));
					}
					String down = firstAction(c, DOWN_ACTIONS);
					if (down != null)
					{
						downs.add(new Obstacle(o, down, c.getName(), wp));
					}
				}
			}
		}

		int tx = dest.getX() - baseX;
		int ty = dest.getY() - baseY;
		boolean inScene = tx >= 0 && ty >= 0 && tx < size && ty < size && dest.getPlane() == plane;
		int[][] dist = ScenePath.distances(flags, doors, sx, sy);
		Obstacle way = null;
		// Prefer the nearest reachable spawn pin over the middle of the spawn cloud.
		if (loc != null && loc.getPins() != null && !loc.getPins().isEmpty() && dest.getPlane() == plane)
		{
			int bestD = Integer.MAX_VALUE;
			int bx = -1;
			int by = -1;
			for (List<Integer> pin : loc.getPins())
			{
				if (pin == null || pin.size() < 2)
				{
					continue;
				}
				int px = pin.get(0) - baseX;
				int py = pin.get(1) - baseY;
				if (px < 0 || py < 0 || px >= size || py >= size || dist[px][py] < 0)
				{
					continue;
				}
				if (dist[px][py] < bestD)
				{
					bestD = dist[px][py];
					bx = px;
					by = py;
				}
			}
			if (bx >= 0)
			{
				tx = bx;
				ty = by;
				inScene = true;
			}
		}
		if (!inScene)
		{
			// Another floor, or underground: the way there is the nearest staircase, ladder or entrance you can walk to.
			boolean destUnder = dest.getY() >= 6400;
			boolean hereUnder = here.getY() >= 6400;
			List<Obstacle> candidates;
			if (!known.isEmpty())
			{
				// the spot's own way in, whatever its option is called (the Catacombs' statue says Investigate)
				candidates = known;
			}
			else if (destUnder != hereUnder)
			{
				candidates = destUnder ? downs : ups;
			}
			else if (dest.getPlane() != plane)
			{
				candidates = dest.getPlane() > plane ? ups : downs;
			}
			else
			{
				candidates = Collections.emptyList();
			}
			int bestD = Integer.MAX_VALUE;
			for (Obstacle o : candidates)
			{
				double far = Math.hypot(o.tile.getX() - dest.getX(), Advisor.surfaceY(o.tile.getY()) - Advisor.surfaceY(dest.getY()));
				if (far > ENTRANCE_REACH)
				{
					continue;
				}
				int ox = o.tile.getX() - baseX;
				int oy = o.tile.getY() - baseY;
				int d = reachNear(dist, ox, oy, size);
				if (d >= 0 && d < bestD)
				{
					bestD = d;
					way = o;
				}
			}
			if (way != null)
			{
				int[] t = nearestReachable(dist, way.tile.getX() - baseX, way.tile.getY() - baseY, size);
				tx = t[0];
				ty = t[1];
				inScene = true;
			}
		}
		if (!inScene)
		{
			// Far away on the same level: head toward it to the edge of the loaded area.
			double dx = dest.getX() - here.getX();
			double dy = Advisor.surfaceY(dest.getY()) - Advisor.surfaceY(here.getY());
			double len = Math.hypot(dx, dy);
			if (len < 1 || len > config.floorPathMaxDistance() || wv.isInstance())
			{
				clear();
				return;
			}
			int reach = Math.min((int) len, 48);
			tx = clamp(sx + (int) Math.round(dx / len * reach), 1, size - 2);
			ty = clamp(sy + (int) Math.round(dy / len * reach), 1, size - 2);
		}
		List<int[]> tiles = ScenePath.find(flags, doors, sx, sy, tx, ty, config.floorPathTiles());
		reachesTarget = inScene && !tiles.isEmpty() && tiles.get(tiles.size() - 1)[0] == tx && tiles.get(tiles.size() - 1)[1] == ty;
		List<WorldPoint> out = new ArrayList<>(tiles.size());
		List<Obstacle> onWay = new ArrayList<>();
		for (int[] tile : tiles)
		{
			out.add(new WorldPoint(tile[0] + baseX, tile[1] + baseY, plane));
			Obstacle d = doorAt.get(tile[0] * size + tile[1]);
			if (d != null && !onWay.contains(d))
			{
				onWay.add(d);
			}
		}
		path = out;
		obstacles = onWay;
		entrance = way;
	}

	/** BFS distance to the tile or any tile next to it (the object itself is solid). */
	private static int reachNear(int[][] dist, int x, int y, int size)
	{
		int best = -1;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int nx = x + dx;
				int ny = y + dy;
				if (nx < 0 || ny < 0 || nx >= size || ny >= size || dist[nx][ny] < 0)
				{
					continue;
				}
				if (best < 0 || dist[nx][ny] < best)
				{
					best = dist[nx][ny];
				}
			}
		}
		return best;
	}

	private static int[] nearestReachable(int[][] dist, int x, int y, int size)
	{
		int[] best = {x, y};
		int bestD = Integer.MAX_VALUE;
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				int nx = x + dx;
				int ny = y + dy;
				if (nx < 0 || ny < 0 || nx >= size || ny >= size || dist[nx][ny] < 0)
				{
					continue;
				}
				if (dist[nx][ny] < bestD)
				{
					bestD = dist[nx][ny];
					best = new int[]{nx, ny};
				}
			}
		}
		return best;
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.floorPath())
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		List<WorldPoint> p = path;
		if (p.size() >= 2)
		{
			drawArrows(g, wv, p);
		}
		g.setStroke(new BasicStroke(2f));
		for (Obstacle o : obstacles)
		{
			mark(g, wv, o, DOOR, DOOR_FILL);
		}
		Obstacle e = entrance;
		if (e != null)
		{
			mark(g, wv, e, ENTRANCE, ENTRANCE_FILL);
		}
		return null;
	}

	/**
	 * A chevron lying flat on each tile, pointing at the next one, like the boost pads of a kart
	 * track: a soft pulse runs along them toward the spot, and the last tile is outlined.
	 */
	private void drawArrows(Graphics2D g, WorldView wv, List<WorldPoint> p)
	{
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Stroke line = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
		g.setStroke(line);
		int n = p.size();
		// Pulses run toward the spot every PULSE_EVERY tiles. They are timed from the far end of the
		// route, so a tile keeps its place in the rhythm as you walk and the route shortens behind you.
		double phase = (System.currentTimeMillis() / (double) PULSE_MS) % PULSE_EVERY;
		for (int i = 1; i < n - 1; i++)
		{
			WorldPoint a = p.get(i);
			WorldPoint b = p.get(i + 1);
			LocalPoint c = LocalPoint.fromWorld(wv, a);
			int dx = Integer.signum(b.getX() - a.getX());
			int dy = Integer.signum(b.getY() - a.getY());
			if (c == null || (dx == 0 && dy == 0))
			{
				continue;
			}
			Polygon chevron = chevron(wv, c, a.getPlane(), dx, dy);
			if (chevron == null)
			{
				continue;
			}
			double behind = ((n - 1 - i) + phase) % PULSE_EVERY;
			float lit = behind < 3 ? (float) (1 - behind / 3) : 0f;
			g.setColor(new Color(80, 220, 255, 60 + Math.round(lit * 120)));
			g.fill(chevron);
			g.setColor(ARROW_EDGE);
			g.draw(chevron);
		}
		LocalPoint last = LocalPoint.fromWorld(wv, p.get(n - 1));
		Polygon poly = last == null ? null : Perspective.getCanvasTilePoly(client, last);
		if (poly != null)
		{
			boolean good = reachesTarget || entrance != null;
			g.setColor(good ? END_FILL : BLOCKED_FILL);
			g.fillPolygon(poly);
			g.setColor(good ? END : BLOCKED);
			g.drawPolygon(poly);
		}
		if (aa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
	}

	/** The six corners of a chevron on the ground around tile centre c, pointing (dx, dy) in world tiles. */
	private Polygon chevron(WorldView wv, LocalPoint c, int plane, int dx, int dy)
	{
		double len = Math.hypot(dx, dy);
		double ux = dx / len;
		double uy = dy / len;
		// (forward, sideways) as fractions of CHEVRON: outer wing, tip, outer wing, inner wing, notch, inner wing
		double[][] corners = {{-0.3, 0.9}, {0.9, 0}, {-0.3, -0.9}, {-0.9, -0.9}, {0.3, 0}, {-0.9, 0.9}};
		Polygon poly = new Polygon();
		for (double[] k : corners)
		{
			double f = k[0] * CHEVRON;
			double s = k[1] * CHEVRON;
			int lx = (int) Math.round(c.getX() + ux * f - uy * s);
			int ly = (int) Math.round(c.getY() + uy * f + ux * s);
			Point sp = Perspective.localToCanvas(client, new LocalPoint(lx, ly, wv), plane);
			if (sp == null)
			{
				return null;
			}
			poly.addPoint(sp.getX(), sp.getY());
		}
		return poly;
	}

	private void mark(Graphics2D g, WorldView wv, Obstacle o, Color edge, Color fill)
	{
		Shape box = null;
		try
		{
			box = o.object.getClickbox();
		}
		catch (RuntimeException ignored)
		{
			// object gone
		}
		if (box == null)
		{
			LocalPoint lp = LocalPoint.fromWorld(wv, o.tile);
			box = lp == null ? null : Perspective.getCanvasTilePoly(client, lp);
		}
		if (box == null)
		{
			return;
		}
		g.setColor(fill);
		g.fill(box);
		g.setColor(edge);
		g.draw(box);
		LocalPoint lp = LocalPoint.fromWorld(wv, o.tile);
		if (lp == null)
		{
			return;
		}
		Point text = Perspective.getCanvasTextLocation(client, g, lp, o.label(), 80);
		if (text == null)
		{
			return;
		}
		g.setFont(FontManager.getRunescapeBoldFont());
		int w = g.getFontMetrics().stringWidth(o.label());
		g.setColor(Color.BLACK);
		g.drawString(o.label(), text.getX() - w / 2 + 1, text.getY() + 1);
		g.setColor(edge);
		g.drawString(o.label(), text.getX() - w / 2, text.getY());
	}
}
