package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.route.RouteService;
import com.slayersuite.route.TransportTable;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * On the fairy ring's dial screen: the code the route wants, which dial to turn, which way and how
 * many clicks, then the teleport button once the dials read right. The way a clockwise click moves
 * the varbit is learned from the first click seen and remembered.
 */
public class FairyDialOverlay extends Overlay
{
	private static final int[] CLOCKWISE = {InterfaceID.Fairyrings._1_CLOCKWISE, InterfaceID.Fairyrings._2_CLOCKWISE, InterfaceID.Fairyrings._3_CLOCKWISE};
	private static final int[] ANTICLOCKWISE = {InterfaceID.Fairyrings._1_ANTICLOCKWISE, InterfaceID.Fairyrings._2_ANTICLOCKWISE, InterfaceID.Fairyrings._3_ANTICLOCKWISE};
	private static final int[] VARBITS = {VarbitID.FAIRYRING_1, VarbitID.FAIRYRING_2, VarbitID.FAIRYRING_3};
	static final String SENSE_KEY = "fairyDialSense";

	private final Client client;
	private final RouteService routes;
	private final SlayerSuiteConfig config;
	private final ConfigManager configManager;

	/** A rotate click waiting for its varbit change: dial, whether it was clockwise, the value before. */
	private int pendingDial = -1;
	private boolean pendingClockwise;
	private int pendingValue;

	@Inject
	public FairyDialOverlay(Client client, RouteService routes, SlayerSuiteConfig config, ConfigManager configManager)
	{
		this.client = client;
		this.routes = routes;
		this.config = config;
		this.configManager = configManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.fairyDialHelper())
		{
			return null;
		}
		Widget confirm = client.getWidget(InterfaceID.Fairyrings.CONFIRM);
		if (confirm == null || confirm.isHidden())
		{
			return null;
		}
		String code = routes.nextFairyCode();
		if (!FairyDial.isCode(code))
		{
			return null;
		}
		int[] now = new int[3];
		for (int i = 0; i < 3; i++)
		{
			now[i] = client.getVarbitValue(VARBITS[i]);
		}
		Color accent = config.chevronColour().resolve(config.customChevronColour());
		int sense = sense();
		boolean allSet = true;
		g.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics fm = g.getFontMetrics();
		for (int i = 0; i < 3; i++)
		{
			int target = FairyDial.index(i, code.charAt(i));
			int[] turn = FairyDial.turn(now[i], target, sense);
			if (turn[0] == 0)
			{
				continue;
			}
			allSet = false;
			Widget cw = client.getWidget(CLOCKWISE[i]);
			Widget acw = client.getWidget(ANTICLOCKWISE[i]);
			Widget button = turn[1] > 0 ? cw : acw;
			if (button == null || button.isHidden())
			{
				continue;
			}
			// the two rotate buttons are the halves of the dial: mark the whole dial once, above it,
			// with a curved arrow the way to turn and the number of clicks
			Rectangle b = button.getBounds();
			Rectangle other = (turn[1] > 0 ? acw : cw) == null ? b : (turn[1] > 0 ? acw : cw).getBounds();
			int left = Math.min(b.x, other.x);
			int right = Math.max(b.x + b.width, other.x + other.width);
			int cx = (left + right) / 2;
			int top = Math.min(b.y, other.y);
			String n = turn[0] + "x";
			int r = 11;
			int ax = cx - (r + 4 + fm.stringWidth(n)) / 2 + r;
			int ay = top - r - 6;
			arrow(g, ax, ay, r, turn[1] > 0, accent);
			shadowed(g, n, ax + r + 6, ay + fm.getAscent() / 2 - 1, accent);
			// the letter to reach, on the dial's own letter window
			String want = FairyDial.DIALS[i][target];
			shadowed(g, want, cx - fm.stringWidth(want) / 2, b.y + b.height + fm.getAscent() + 2, accent);
		}
		if (allSet)
		{
			Rectangle b = confirm.getBounds();
			outline(g, b, accent);
			String t = "Teleport";
			shadowed(g, t, b.x + (b.width - fm.stringWidth(t)) / 2, b.y - 6, accent);
		}
		// the code to set, above the dials
		Widget root = client.getWidget(InterfaceID.Fairyrings.ROOT_RECT0);
		Rectangle top = root != null && !root.isHidden() ? root.getBounds() : confirm.getBounds();
		String place = TransportTable.fairyPlace(code);
		String head = "Dial " + code + (place.isEmpty() ? "" : "  -  " + place);
		Font big = FontManager.getRunescapeBoldFont().deriveFont(18f);
		g.setFont(big);
		FontMetrics fb = g.getFontMetrics();
		int hx = top.x + (top.width - fb.stringWidth(head)) / 2;
		int hy = Math.max(fb.getAscent() + 4, top.y - 10);
		shadowed(g, head, hx, hy, accent);
		if (!allSet)
		{
			g.setFont(FontManager.getRunescapeFont());
			FontMetrics fs = g.getFontMetrics();
			String sub = "now " + FairyDial.letters(now);
			shadowed(g, sub, top.x + (top.width - fs.stringWidth(sub)) / 2, hy + fs.getHeight(), new Color(220, 220, 220));
		}
		return null;
	}

	/** A three-quarter circle with an arrowhead, clockwise or anticlockwise, centred on (cx, cy). */
	private static void arrow(Graphics2D g, int cx, int cy, int r, boolean clockwise, Color c)
	{
		Stroke old = g.getStroke();
		Object aa = g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
		g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		// arc from the bottom round to the right side; mirrored for anticlockwise
		int start = clockwise ? 240 : -60;
		int extent = clockwise ? -270 : 270;
		g.setColor(Color.BLACK);
		g.drawArc(cx - r + 1, cy - r + 1, 2 * r, 2 * r, start, extent);
		g.setColor(c);
		g.drawArc(cx - r, cy - r, 2 * r, 2 * r, start, extent);
		// arrowhead at the arc's end (top of the circle), pointing the way round
		int hx = cx;
		int hy = cy - r;
		int dir = clockwise ? 1 : -1;
		int[] xs = {hx, hx - dir * 7, hx - dir * 1};
		int[] ys = {hy, hy - 5, hy + 6};
		g.setColor(Color.BLACK);
		g.fillPolygon(new int[]{xs[0] + 1, xs[1] + 1, xs[2] + 1}, new int[]{ys[0] + 1, ys[1] + 1, ys[2] + 1}, 3);
		g.setColor(c);
		g.fillPolygon(xs, ys, 3);
		g.setStroke(old);
		g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, aa == null ? java.awt.RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
	}

	private static void outline(Graphics2D g, Rectangle b, Color c)
	{
		Stroke old = g.getStroke();
		g.setStroke(new BasicStroke(2f));
		g.setColor(c);
		g.drawRoundRect(b.x - 2, b.y - 2, b.width + 4, b.height + 4, 6, 6);
		g.setStroke(old);
	}

	private static void shadowed(Graphics2D g, String s, int x, int y, Color c)
	{
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
	}

	/** +1 when a clockwise click raises the dial's varbit (the geometry says so), -1 once a click proved otherwise. */
	private int sense()
	{
		Integer s = configManager.getConfiguration(SlayerSuiteConfig.GROUP, SENSE_KEY, Integer.class);
		return s == null || s >= 0 ? 1 : -1;
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked e)
	{
		int id = e.getParam1();
		for (int i = 0; i < 3; i++)
		{
			if (id == CLOCKWISE[i] || id == ANTICLOCKWISE[i])
			{
				pendingDial = i;
				pendingClockwise = id == CLOCKWISE[i];
				pendingValue = client.getVarbitValue(VARBITS[i]);
				return;
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (pendingDial < 0 || e.getVarbitId() != VARBITS[pendingDial])
		{
			return;
		}
		int delta = ((e.getValue() - pendingValue) % 4 + 4) % 4;
		pendingDial = -1;
		if (delta != 1 && delta != 3)
		{
			return;
		}
		boolean raised = delta == 1;
		int sense = (raised == pendingClockwise) ? 1 : -1;
		if (sense != sense())
		{
			configManager.setConfiguration(SlayerSuiteConfig.GROUP, SENSE_KEY, sense);
		}
	}
}
