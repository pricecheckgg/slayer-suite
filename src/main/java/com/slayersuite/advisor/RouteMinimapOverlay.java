package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.route.Packed;
import com.slayersuite.route.RouteService;
import com.slayersuite.route.Router;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** The next stretch of the route as dots on the minimap. */
public class RouteMinimapOverlay extends Overlay
{
	private static final int TILES = 120;

	private final Client client;
	private final RouteService routes;
	private final SlayerSuiteConfig config;

	@Inject
	public RouteMinimapOverlay(Client client, RouteService routes, SlayerSuiteConfig config)
	{
		this.client = client;
		this.routes = routes;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_LOW);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.routeMinimap() || !routes.onRoute())
		{
			return null;
		}
		Router.Route r = routes.current();
		WorldView wv = client.getTopLevelWorldView();
		if (r == null || wv == null)
		{
			return null;
		}
		int from = routes.from();
		int max = Math.min(r.tiles.size(), from + TILES);
		Color base = config.chevronColour().resolve(config.customChevronColour());
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 200));
		for (int i = from + 1; i < max; i++)
		{
			WorldPoint wp = Packed.toWorld(r.tiles.get(i));
			LocalPoint lp = LocalPoint.fromWorld(wv, wp);
			if (lp == null)
			{
				continue;
			}
			Point p = Perspective.localToMinimap(client, lp);
			if (p != null)
			{
				g.fillRect(p.getX() - 1, p.getY() - 1, 3, 3);
			}
		}
		return null;
	}
}
