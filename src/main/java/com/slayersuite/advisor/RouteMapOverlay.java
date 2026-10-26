package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.route.Packed;
import com.slayersuite.route.Planner;
import com.slayersuite.route.RouteService;
import com.slayersuite.route.Router;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.worldmap.WorldMapOverlay;

/** The whole plan on the world map: a line along the route, and a ring where the teleport lands. */
public class RouteMapOverlay extends Overlay
{
	private static final Color SHADOW = new Color(0, 0, 0, 140);
	private static final Color LANDING = new Color(255, 215, 80, 230);

	private final Client client;
	private final RouteService routes;
	private final SlayerSuiteConfig config;
	private final WorldMapOverlay worldMap;

	@Inject
	public RouteMapOverlay(Client client, RouteService routes, SlayerSuiteConfig config, WorldMapOverlay worldMap)
	{
		this.client = client;
		this.routes = routes;
		this.config = config;
		this.worldMap = worldMap;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.MANUAL);
		drawAfterLayer(InterfaceID.Worldmap.MAP_CONTAINER);
		setPriority(PRIORITY_LOW);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.routeWorldMap())
		{
			return null;
		}
		Widget map = client.getWidget(InterfaceID.Worldmap.MAP_CONTAINER);
		Router.Route r = routes.current();
		if (map == null || map.isHidden() || r == null)
		{
			return null;
		}
		Rectangle bounds = map.getBounds();
		Shape clip = g.getClip();
		g.setClip(bounds);
		Color base = config.chevronColour().resolve(config.customChevronColour());
		Color line = new Color(base.getRed(), base.getGreen(), base.getBlue(), 220);
		int from = routes.onRoute() ? routes.from() : 0;
		Point prev = null;
		for (int i = from; i < r.tiles.size(); i += 2)
		{
			Point p = worldMap.mapWorldPointToGraphicsPoint(Packed.toWorld(r.tiles.get(i)));
			if (p != null && prev != null)
			{
				g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(SHADOW);
				g.drawLine(prev.getX(), prev.getY(), p.getX(), p.getY());
				g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.setColor(line);
				g.drawLine(prev.getX(), prev.getY(), p.getX(), p.getY());
			}
			prev = p == null ? prev : p;
		}
		Planner.Plan plan = routes.plan();
		if (plan != null && plan.teleport != null && !routes.onRoute())
		{
			WorldPoint wp = Packed.toWorld(plan.teleport.tile);
			Point p = worldMap.mapWorldPointToGraphicsPoint(wp);
			if (p != null)
			{
				g.setStroke(new BasicStroke(2f));
				g.setColor(LANDING);
				g.drawOval(p.getX() - 6, p.getY() - 6, 12, 12);
				g.setFont(FontManager.getRunescapeSmallFont());
				String label = plan.teleport.label;
				g.setColor(SHADOW);
				g.drawString(label, p.getX() + 9, p.getY() + 5);
				g.setColor(LANDING);
				g.drawString(label, p.getX() + 8, p.getY() + 4);
			}
		}
		g.setClip(clip);
		return null;
	}
}
