package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.trip.TripMath;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** An arrow on the minimap edge pointing at the chosen spot, with the distance. Display only. */
public class GpsOverlay extends Overlay
{
	private static final Color ARROW = new Color(80, 220, 255);
	private static final Color OUTLINE = new Color(0, 0, 0, 180);

	private final Client client;
	private final Advisor advisor;
	private final SlayerSuiteConfig config;
	private final com.slayersuite.task.SlayerMode mode;

	@Inject
	public GpsOverlay(Client client, Advisor advisor, SlayerSuiteConfig config, com.slayersuite.task.SlayerMode mode)
	{
		this.mode = mode;
		this.client = client;
		this.advisor = advisor;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_LOW);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.gpsArrow() || advisor.getDestination() == null || advisor.isArrived() || !mode.active())
		{
			return null;
		}
		WorldPoint dest = advisor.guidePoint();
		Player me = client.getLocalPlayer();
		if (dest == null || me == null)
		{
			return null;
		}
		WorldPoint wp = me.getWorldLocation();
		int dx = dest.getX() - wp.getX();
		int dy = dest.getY() - wp.getY();
		double len = Math.hypot(dx, dy);
		if (len < 6)
		{
			return null;
		}
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return null;
		}
		// a point ten tiles toward the destination is always in the loaded scene
		int px = wp.getX() + (int) Math.round(dx / len * 10);
		int py = wp.getY() + (int) Math.round(dy / len * 10);
		LocalPoint toward = LocalPoint.fromWorld(wv, px, py);
		LocalPoint here = me.getLocalLocation();
		if (toward == null || here == null)
		{
			return null;
		}
		Point tip = Perspective.localToMinimap(client, toward);
		Point centre = Perspective.localToMinimap(client, here);
		if (tip == null || centre == null)
		{
			return null;
		}
		double ang = Math.atan2(tip.getY() - centre.getY(), tip.getX() - centre.getX());
		int size = 9;
		Polygon tri = new Polygon();
		tri.addPoint((int) Math.round(tip.getX() + Math.cos(ang) * size), (int) Math.round(tip.getY() + Math.sin(ang) * size));
		tri.addPoint((int) Math.round(tip.getX() + Math.cos(ang + 2.5) * size), (int) Math.round(tip.getY() + Math.sin(ang + 2.5) * size));
		tri.addPoint((int) Math.round(tip.getX() + Math.cos(ang - 2.5) * size), (int) Math.round(tip.getY() + Math.sin(ang - 2.5) * size));
		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(ARROW);
		g.fillPolygon(tri);
		g.setColor(OUTLINE);
		g.setStroke(new BasicStroke(1.5f));
		g.drawPolygon(tri);
		g.setFont(FontManager.getRunescapeSmallFont());
		String label = TripMath.formatK(Math.round(len));
		int tx = (int) Math.round(tip.getX() + Math.cos(ang) * (size + 10)) - g.getFontMetrics().stringWidth(label) / 2;
		int ty = (int) Math.round(tip.getY() + Math.sin(ang) * (size + 10)) + 4;
		g.setColor(Color.BLACK);
		g.drawString(label, tx + 1, ty + 1);
		g.setColor(ARROW);
		g.drawString(label, tx, ty);
		if (aa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
		return null;
	}
}
