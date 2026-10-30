package com.slayersuite.card;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.ui.Palette;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

/**
 * Makes a superior impossible to miss: its outline and tile pulse red with SUPERIOR over its head
 * until you attack it, then settle to amber; when it is off screen an arrow at the edge of the view
 * points at it with the distance; a dot pulses on the minimap the whole time it stands.
 */
public class SuperiorOverlay extends Overlay
{
	private static final Color OUTLINE = new Color(0, 0, 0, 180);

	private final Client client;
	private final TaskTracker tracker;
	private final SlayerSuiteConfig config;
	private final ModelOutlineRenderer outlines;

	@Inject
	public SuperiorOverlay(Client client, TaskTracker tracker, SlayerSuiteConfig config, ModelOutlineRenderer outlines)
	{
		this.client = client;
		this.tracker = tracker;
		this.config = config;
		this.outlines = outlines;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGH);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.superiorHighlight())
		{
			return null;
		}
		NPC s = tracker.getSuperior();
		Player me = client.getLocalPlayer();
		if (s == null || me == null)
		{
			return null;
		}
		long now = System.currentTimeMillis();
		boolean engaged = tracker.isSuperiorEngaged();
		float pulse = engaged ? 1f : 0.6f + 0.4f * (float) Math.abs(Math.sin(now / 160.0));
		Color base = engaged ? Palette.SUPERIOR_CALM : Palette.SUPERIOR;
		Color c = new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.round(255 * pulse));
		Color fill = new Color(base.getRed(), base.getGreen(), base.getBlue(), Math.round(40 * pulse));
		String name = tracker.getSuperiorName() == null ? "Superior" : tracker.getSuperiorName();
		WorldPoint mine = me.getWorldLocation();
		WorldPoint theirs = s.getWorldLocation();
		int tiles = mine != null && theirs != null && mine.getPlane() == theirs.getPlane() ? mine.distanceTo(theirs) : -1;

		Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Rectangle view = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(), client.getViewportWidth(), client.getViewportHeight());
		Shape oldClip = g.getClip();
		Stroke oldStroke = g.getStroke();
		g.setClip(view);

		LocalPoint lp = s.getLocalLocation();
		NPCComposition comp = s.getTransformedComposition();
		int size = comp == null ? 1 : Math.max(1, comp.getSize());
		Polygon tile = lp == null ? null : Perspective.getCanvasTileAreaPoly(client, lp, size);
		if (tile != null)
		{
			g.setColor(fill);
			g.fill(tile);
			g.setColor(c);
			g.setStroke(new BasicStroke(2f));
			g.draw(tile);
		}
		// the creature's own silhouette, feathered into a glow, rather than a hull boxing its wings
		outlines.drawOutline(s, engaged ? 2 : 3, c, engaged ? 2 : 6);
		int plane = theirs == null ? 0 : theirs.getPlane();
		// well above the head, clear of the name tags other plugins draw there
		Point above = lp == null ? null : Perspective.localToCanvas(client, lp, plane, s.getLogicalHeight() + 110);
		boolean onScreen = above != null && view.contains(above.getX(), above.getY());
		if (onScreen)
		{
			Font big = FontManager.getRunescapeBoldFont().deriveFont(engaged ? 16f : 18f + 2f * pulse);
			g.setFont(big);
			FontMetrics fb = g.getFontMetrics();
			String head = "SUPERIOR";
			shadowed(g, head, above.getX() - fb.stringWidth(head) / 2, above.getY() - fb.getHeight(), c);
			g.setFont(FontManager.getRunescapeBoldFont());
			FontMetrics fm = g.getFontMetrics();
			String line = engaged ? name : name + "  -  attack it";
			shadowed(g, line, above.getX() - fm.stringWidth(line) / 2, above.getY(), Color.WHITE);
		}
		g.setClip(oldClip);

		if (!onScreen)
		{
			edgeArrow(g, view, me, s, lp, c, name, tiles);
		}
		// the minimap: a dot that pulses while it stands
		Point mm = lp == null ? null : Perspective.localToMinimap(client, lp);
		if (mm != null)
		{
			int r = engaged ? 4 : 4 + Math.round(3 * pulse);
			g.setColor(c);
			g.fillOval(mm.getX() - r, mm.getY() - r, 2 * r, 2 * r);
			g.setColor(OUTLINE);
			g.setStroke(new BasicStroke(1.5f));
			g.drawOval(mm.getX() - r, mm.getY() - r, 2 * r, 2 * r);
		}
		g.setStroke(oldStroke);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa == null ? RenderingHints.VALUE_ANTIALIAS_DEFAULT : aa);
		return null;
	}

	/** An arrow just inside the edge of the view, pointing where the superior is, with its name and distance. */
	private void edgeArrow(Graphics2D g, Rectangle view, Player me, NPC s, LocalPoint lp, Color c, String name, int tiles)
	{
		double ang;
		WorldPoint w = s.getWorldLocation();
		Point on = lp == null ? null : Perspective.localToCanvas(client, lp, w == null ? 0 : w.getPlane(), 0);
		int cx = view.x + view.width / 2;
		int cy = view.y + view.height / 2;
		if (on != null)
		{
			ang = Math.atan2(on.getY() - cy, on.getX() - cx);
		}
		else
		{
			// behind the camera: the minimap turns with the camera, so its direction is the screen's
			LocalPoint here = me.getLocalLocation();
			Point a = here == null ? null : Perspective.localToMinimap(client, here, 2000);
			Point b = lp == null ? null : Perspective.localToMinimap(client, lp, 2000);
			if (a == null || b == null)
			{
				return;
			}
			ang = Math.atan2(b.getY() - a.getY(), b.getX() - a.getX());
		}
		int inset = 46;
		double hx = (view.width / 2.0 - inset) / Math.max(1e-6, Math.abs(Math.cos(ang)));
		double hy = (view.height / 2.0 - inset) / Math.max(1e-6, Math.abs(Math.sin(ang)));
		double reach = Math.min(hx, hy);
		int tx = (int) Math.round(cx + Math.cos(ang) * reach);
		int ty = (int) Math.round(cy + Math.sin(ang) * reach);
		int sz = 16;
		Polygon tri = new Polygon();
		tri.addPoint((int) Math.round(tx + Math.cos(ang) * sz), (int) Math.round(ty + Math.sin(ang) * sz));
		tri.addPoint((int) Math.round(tx + Math.cos(ang + 2.5) * sz), (int) Math.round(ty + Math.sin(ang + 2.5) * sz));
		tri.addPoint((int) Math.round(tx + Math.cos(ang - 2.5) * sz), (int) Math.round(ty + Math.sin(ang - 2.5) * sz));
		g.setColor(c);
		g.fillPolygon(tri);
		g.setColor(OUTLINE);
		g.setStroke(new BasicStroke(2f));
		g.drawPolygon(tri);
		g.setFont(FontManager.getRunescapeBoldFont());
		FontMetrics fm = g.getFontMetrics();
		String label = "SUPERIOR  " + name + (tiles > 0 ? "  " + tiles + " tiles" : "");
		int lx = tx - fm.stringWidth(label) / 2;
		int ly = ty - sz - 6;
		lx = Math.max(view.x + 4, Math.min(view.x + view.width - fm.stringWidth(label) - 4, lx));
		if (ly < view.y + fm.getAscent() + 2)
		{
			ly = ty + sz + fm.getAscent() + 6;
		}
		shadowed(g, label, lx, ly, c);
	}

	private static void shadowed(Graphics2D g, String s, int x, int y, Color c)
	{
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
	}
}
