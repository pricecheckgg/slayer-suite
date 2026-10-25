package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.task.SlayerMode;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Tiles on the floor from you toward the pinned spot, through the loaded map's
 * walls and obstacles. Far away it leads to the edge of the loaded area in the
 * right direction; the teleport step and the minimap arrow cover the rest.
 */
public class PathOverlay extends Overlay
{
	private static final Color FILL = new Color(80, 220, 255, 60);
	private static final Color EDGE = new Color(80, 220, 255, 160);
	private static final Color END = new Color(255, 215, 80, 200);
	private static final Color BLOCKED = new Color(255, 90, 90, 200);

	private final Client client;
	private final Advisor advisor;
	private final SlayerSuiteConfig config;
	private final SlayerMode mode;

	private volatile List<WorldPoint> path = Collections.emptyList();
	/** True when the line reaches a spawn point; false when it stops short at a door, shortcut, stairs or the edge. */
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
		return !path.isEmpty() && !reachesTarget && advisor.getDestination() != null && !advisor.isArrived();
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (!config.floorPath() || client.getGameState() != GameState.LOGGED_IN)
		{
			path = Collections.emptyList();
			return;
		}
		WorldPoint dest = advisor.destinationPoint();
		Player me = client.getLocalPlayer();
		WorldView wv = client.getTopLevelWorldView();
		if (dest == null || advisor.isArrived() || me == null || wv == null || !mode.active())
		{
			path = Collections.emptyList();
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
		path = compute(wv, me.getWorldLocation(), sx, sy, dest);
	}

	private List<WorldPoint> compute(WorldView wv, WorldPoint here, int sx, int sy, WorldPoint dest)
	{
		CollisionData[] maps = wv.getCollisionMaps();
		int plane = wv.getPlane();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null)
		{
			return Collections.emptyList();
		}
		int[][] raw = maps[plane].getFlags();
		if (raw == null || raw.length == 0)
		{
			return Collections.emptyList();
		}
		int size = raw.length;
		// Tiles that do not exist (the empty part of a house or dungeon instance) read as open; treat them as walls.
		int[][] flags = new int[size][];
		Scene scene = wv.getScene();
		Tile[][][] sceneTiles = scene == null ? null : scene.getTiles();
		for (int x = 0; x < size; x++)
		{
			flags[x] = raw[x].clone();
			if (sceneTiles != null && plane < sceneTiles.length && sceneTiles[plane] != null && x < sceneTiles[plane].length)
			{
				for (int y = 0; y < flags[x].length; y++)
				{
					if (y >= sceneTiles[plane][x].length || sceneTiles[plane][x][y] == null)
					{
						flags[x][y] |= CollisionDataFlag.BLOCK_MOVEMENT_FULL;
					}
				}
			}
		}
		int baseX = wv.getBaseX();
		int baseY = wv.getBaseY();
		int tx = dest.getX() - baseX;
		int ty = dest.getY() - baseY;
		boolean inScene = tx >= 0 && ty >= 0 && tx < size && ty < size && dest.getPlane() == plane;
		// Prefer the nearest reachable spawn pin over the middle of the spawn cloud.
		com.slayersuite.data.LocationRecord loc = advisor.getDestination();
		if (loc != null && loc.getPins() != null && !loc.getPins().isEmpty() && dest.getPlane() == plane)
		{
			int[][] dist = ScenePath.distances(flags, sx, sy);
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
			// Far away, or inside an instance (your house, a dungeon): the teleport step and the arrow are the route.
			double dx = dest.getX() - here.getX();
			double dy = dest.getY() - here.getY();
			double len = Math.hypot(dx, dy);
			if (len < 1 || len > config.floorPathMaxDistance() || wv.isInstance())
			{
				return Collections.emptyList();
			}
			int reach = Math.min((int) len, 48);
			tx = clamp(sx + (int) Math.round(dx / len * reach), 1, size - 2);
			ty = clamp(sy + (int) Math.round(dy / len * reach), 1, size - 2);
		}
		List<int[]> tiles = ScenePath.find(flags, sx, sy, tx, ty, config.floorPathTiles());
		reachesTarget = inScene && !tiles.isEmpty() && tiles.get(tiles.size() - 1)[0] == tx && tiles.get(tiles.size() - 1)[1] == ty;
		List<WorldPoint> out = new ArrayList<>(tiles.size());
		for (int[] tile : tiles)
		{
			out.add(new WorldPoint(tile[0] + baseX, tile[1] + baseY, plane));
		}
		return out;
	}

	private static int clamp(int v, int lo, int hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		List<WorldPoint> p = path;
		if (p.size() < 2 || !config.floorPath())
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		g.setStroke(new BasicStroke(1f));
		for (int i = 1; i < p.size(); i++)
		{
			LocalPoint lp = LocalPoint.fromWorld(wv, p.get(i));
			if (lp == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly == null)
			{
				continue;
			}
			boolean last = i == p.size() - 1;
			Color endColor = reachesTarget ? END : BLOCKED;
			g.setColor(last ? endColor : FILL);
			g.fillPolygon(poly);
			g.setColor(last ? endColor : EDGE);
			g.drawPolygon(poly);
		}
		return null;
	}
}
