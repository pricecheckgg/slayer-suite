package com.slayersuite.history;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.trip.TripMath;
import java.awt.Color;
import com.slayersuite.ui.Palette;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileItem;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

/**
 * Outlines the tiles where your valuable drops still lie, with the item, what it is worth and the
 * time until it vanishes. Only drops the loot log saw fall from a task monster, and only while the
 * item is really on that tile (or one beside it, where a big monster died).
 */
public class FloorLootOverlay extends Overlay
{
	private static final Color GOLD = Palette.GOLD;
	private static final Color GREEN = Palette.OK;
	private static final Color PLAIN = new Color(235, 235, 235);
	private static final Color URGENT = Palette.BAD;
	/** Tiles around the recorded death tile to look on; big monsters drop a tile or two away. */
	private static final int SEARCH = 2;

	private final Client client;
	private final TaskHistory history;
	private final SlayerSuiteConfig config;
	private final ItemManager itemManager;
	private final Map<Integer, String> names = new HashMap<>();

	@Inject
	public FloorLootOverlay(Client client, TaskHistory history, SlayerSuiteConfig config, ItemManager itemManager)
	{
		this.client = client;
		this.history = history;
		this.config = config;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
		setPriority(PRIORITY_LOW);
	}

	/** Everything of yours lying on one tile. */
	private static final class Pile
	{
		final WorldPoint tile;
		long value;
		int items;
		int bestId = -1;
		long bestValue = -1;
		long soonest = Long.MAX_VALUE;

		Pile(WorldPoint tile)
		{
			this.tile = tile;
		}

		void add(int id, long v, long vanishAt)
		{
			value += v;
			items++;
			if (v > bestValue)
			{
				bestValue = v;
				bestId = id;
			}
			soonest = Math.min(soonest, vanishAt);
		}
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.floorLoot())
		{
			return null;
		}
		List<long[]> drops = history.floorDrops();
		if (drops.isEmpty())
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		if (tiles == null)
		{
			return null;
		}
		long min = config.floorLootMin();
		long now = System.currentTimeMillis();
		Map<Long, Pile> piles = new LinkedHashMap<>();
		for (long[] d : drops)
		{
			if (d[5] < min)
			{
				continue;
			}
			WorldPoint at = locate(wv, tiles, (int) d[0], (int) d[1], (int) d[2], (int) d[3]);
			if (at == null)
			{
				continue;
			}
			long key = (((long) at.getX() << 16) | at.getY()) << 2 | at.getPlane();
			piles.computeIfAbsent(key, k -> new Pile(at)).add((int) d[3], d[5], d[6]);
		}
		for (Pile p : piles.values())
		{
			draw(g, wv, p, now);
		}
		return null;
	}

	/** The tile the item is really on: the death tile, then the rings around it; null when it is gone or out of view. */
	private static WorldPoint locate(WorldView wv, Tile[][][] tiles, int x, int y, int plane, int id)
	{
		if (plane < 0 || plane >= tiles.length)
		{
			return null;
		}
		for (int ring = 0; ring <= SEARCH; ring++)
		{
			for (int dx = -ring; dx <= ring; dx++)
			{
				for (int dy = -ring; dy <= ring; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != ring)
					{
						continue;
					}
					LocalPoint lp = LocalPoint.fromWorld(wv, x + dx, y + dy);
					if (lp == null)
					{
						continue;
					}
					int sx = lp.getSceneX();
					int sy = lp.getSceneY();
					if (sx < 0 || sy < 0 || sx >= tiles[plane].length || sy >= tiles[plane][sx].length)
					{
						continue;
					}
					Tile tile = tiles[plane][sx][sy];
					List<TileItem> ground = tile == null ? null : tile.getGroundItems();
					if (ground == null)
					{
						continue;
					}
					for (TileItem it : ground)
					{
						if (it != null && it.getId() == id)
						{
							return new WorldPoint(x + dx, y + dy, plane);
						}
					}
				}
			}
		}
		return null;
	}

	private void draw(Graphics2D g, WorldView wv, Pile p, long now)
	{
		LocalPoint lp = LocalPoint.fromWorld(wv, p.tile);
		if (lp == null)
		{
			return;
		}
		long left = p.soonest == Long.MAX_VALUE ? -1 : p.soonest - now;
		boolean urgent = left >= 0 && left <= FloorLootMath.URGENT_MS;
		Color c = urgent ? URGENT : colourFor(p.value);
		if (urgent && (now / 350) % 2 == 0)
		{
			c = new Color(c.getRed(), c.getGreen(), c.getBlue(), 120);
		}
		Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly != null)
		{
			OverlayUtil.renderPolygon(g, poly, c);
		}
		String label = name(p.bestId) + (p.items > 1 ? " +" + (p.items - 1) : "") + "  " + TripMath.formatK(p.value);
		Point tp = Perspective.getCanvasTextLocation(client, g, lp, label, 0);
		if (tp != null)
		{
			OverlayUtil.renderTextLocation(g, tp, label, c);
			if (left >= 0)
			{
				String time = countdown(left);
				int w = g.getFontMetrics().stringWidth(time);
				int lw = g.getFontMetrics().stringWidth(label);
				OverlayUtil.renderTextLocation(g, new Point(tp.getX() + (lw - w) / 2, tp.getY() + g.getFontMetrics().getHeight()), time, urgent ? URGENT : PLAIN);
			}
		}
	}

	private String name(int id)
	{
		if (id < 0)
		{
			return "Drop";
		}
		return names.computeIfAbsent(id, k ->
		{
			try
			{
				return itemManager.getItemComposition(k).getName();
			}
			catch (RuntimeException e)
			{
				return "Item " + k;
			}
		});
	}

	/** Gold from a million, green from a hundred thousand, plain below. */
	static Color colourFor(long value)
	{
		return value >= 1_000_000L ? GOLD : value >= 100_000L ? GREEN : PLAIN;
	}

	static String countdown(long ms)
	{
		return FloorLootMath.countdown(ms);
	}
}
