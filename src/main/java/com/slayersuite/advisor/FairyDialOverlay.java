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
			Widget button = client.getWidget(turn[1] > 0 ? CLOCKWISE[i] : ANTICLOCKWISE[i]);
			if (button == null || button.isHidden())
			{
				continue;
			}
			Rectangle b = button.getBounds();
			outline(g, b, accent);
			String n = turn[0] + "x";
			int tx = b.x + (b.width - fm.stringWidth(n)) / 2;
			int ty = turn[1] > 0 ? b.y - 4 : b.y + b.height + fm.getAscent() + 2;
			shadowed(g, n, tx, ty, accent);
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
