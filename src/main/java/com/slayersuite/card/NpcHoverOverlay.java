package com.slayersuite.card;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.MonsterRecord;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.guard.AttackGuard;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.task.TaskTracker;
import java.awt.Color;
import com.slayersuite.ui.Palette;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Hover a task monster: a solid card beside the cursor, drawn above everything else, with the
 * name and level, HP / max hit / attack style tiles, then weakness, Slayer xp, the finishing
 * item and any poison or cannon warning.
 */
public class NpcHoverOverlay extends Overlay
{
	private static final Color BG = new Color(18, 18, 18, 238);
	private static final Color BORDER = new Color(255, 255, 255, 45);
	private static final Color TILE = new Color(255, 255, 255, 16);
	private static final Color RULE = new Color(255, 255, 255, 30);
	private static final Color DIM = Palette.DIM;
	private static final Color OK = Palette.OK;
	private static final Color WARN = Palette.WARN;
	private static final Color BAD = Palette.BAD;
	private static final Color INFO = Palette.INFO;
	private static final Color GOLD = Palette.GOLD;
	private static final int PAD = 7;
	private static final int WIDTH = 236;

	private final Client client;
	private final TaskTracker tracker;
	private final AttackGuard guard;
	private final SlayerDataset dataset;
	private final ReadinessChecker readiness;
	private final SlayerSuiteConfig config;
	private final com.slayersuite.task.SlayerMode mode;

	private final Font std = FontManager.getRunescapeFont();
	private final Font small = FontManager.getRunescapeSmallFont();
	private final Font bold = FontManager.getRunescapeBoldFont();

	@Inject
	public NpcHoverOverlay(Client client, TaskTracker tracker, AttackGuard guard, SlayerDataset dataset, ReadinessChecker readiness, SlayerSuiteConfig config, com.slayersuite.task.SlayerMode mode)
	{
		this.mode = mode;
		this.client = client;
		this.tracker = tracker;
		this.guard = guard;
		this.dataset = dataset;
		this.readiness = readiness;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(PRIORITY_HIGHEST);
	}

	/** Everything the card says about one monster, gathered once per frame. */
	private static final class Info
	{
		String name;
		int level;
		String tag;
		Color tagColor;
		List<String[]> tiles = new ArrayList<>();
		String maxHitNote;
		List<String[]> rows = new ArrayList<>();
		List<Object[]> warnings = new ArrayList<>();
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.hoverInfo() || !tracker.hasTask() || client.isMenuOpen() || !mode.active())
		{
			return null;
		}
		NPC npc = hovered();
		if (npc == null)
		{
			return null;
		}
		TaskRecord rec = tracker.getRecord();
		boolean target = guard.isTaskTarget(npc);
		NPCComposition comp = npc.getTransformedComposition();
		String name = comp == null ? npc.getName() : comp.getName();
		if (name == null)
		{
			return null;
		}
		MonsterRecord m = rec == null ? null : rec.monsterNamed(name);
		if (!target && m == null)
		{
			return null;
		}
		Point mouse = client.getMouseCanvasPosition();
		if (mouse == null || mouse.getX() < 0 || mouse.getY() < 0)
		{
			return null;
		}
		Info info = gather(rec, m, name, comp, target);
		int h = layout(g, false, info);
		// below and right of the cursor, under the one-line hover text the game or the Mouse Tooltips
		// plugin draws there; never over the game's own top-left action text either
		int x = mouse.getX() + 14;
		int y = mouse.getY() + 42;
		if (x + WIDTH > client.getCanvasWidth() - 4)
		{
			x = Math.max(0, mouse.getX() - WIDTH - 10);
		}
		if (y + h > client.getCanvasHeight() - 4)
		{
			y = Math.max(0, mouse.getY() - h - 12);
		}
		if (y < 30 && x < 520)
		{
			y = 30;
		}
		g.translate(x, y);
		g.setColor(BG);
		g.fillRect(0, 0, WIDTH, h);
		g.setColor(BORDER);
		g.drawRect(0, 0, WIDTH - 1, h - 1);
		layout(g, true, info);
		g.translate(-x, -y);
		return null;
	}

	private Info gather(TaskRecord rec, MonsterRecord m, String name, NPCComposition comp, boolean target)
	{
		Info i = new Info();
		i.name = name;
		i.level = comp != null ? comp.getCombatLevel() : (m != null && m.getCombat() != null ? m.getCombat() : 0);
		if (!target)
		{
			i.tag = "NOT YOUR TASK";
			i.tagColor = BAD;
		}
		else if (m != null && m.isSuperior())
		{
			i.tag = "SUPERIOR";
			i.tagColor = GOLD;
		}
		else
		{
			i.tag = "YOUR TASK";
			i.tagColor = OK;
		}
		if (m != null)
		{
			if (m.getHitpoints() != null)
			{
				i.tiles.add(new String[]{m.getHitpoints() + (m.getHitpointsMax() != null ? "-" + m.getHitpointsMax() : ""), "HP"});
			}
			if (m.getMaxHit() != null && !m.getMaxHit().isEmpty())
			{
				String mh = m.getMaxHit();
				int paren = mh.indexOf(" (");
				if (paren > 0 && mh.endsWith(")"))
				{
					i.maxHitNote = "Max hit " + mh.substring(paren + 2, mh.length() - 1);
					mh = mh.substring(0, paren);
				}
				i.tiles.add(new String[]{mh, "MAX HIT"});
			}
			if (!m.getAttackStyles().isEmpty())
			{
				List<String> st = new ArrayList<>();
				for (String s : m.getAttackStyles())
				{
					st.add(cap(s));
				}
				i.tiles.add(new String[]{String.join("/", st), st.size() > 1 ? "ATTACKS" : "ATTACK"});
			}
		}
		if (m != null)
		{
			if (m.getElementalWeakness() != null && !m.getElementalWeakness().isEmpty())
			{
				i.rows.add(new String[]{"Weak to", cap(m.getElementalWeakness()) + " spells", "info"});
			}
			if (m.getSlayerXp() != null)
			{
				i.rows.add(new String[]{"Slayer xp", fmt(m.getSlayerXp()) + (m.getSlayerXpMax() != null && !m.getSlayerXpMax().equals(m.getSlayerXp()) ? "-" + fmt(m.getSlayerXpMax()) : ""), ""});
			}
		}
		if (rec != null)
		{
			ItemRule fin = rec.getFinishingItem();
			if (fin != null && fin.getAnyOf() != null)
			{
				ItemGroups items = dataset.getItems();
				boolean have = items.anySatisfied(fin.getAnyOf(), readiness.heldNames());
				i.rows.add(new String[]{"Finish", items.describe(fin.getAnyOf()) + (fin.getThreshold() != null ? " at " + fin.getThreshold() + " HP" : "") + (have ? "" : ", not carried"), have ? "ok" : "bad"});
			}
		}
		if (m != null)
		{
			if (m.isVenomous())
			{
				i.warnings.add(new Object[]{BAD, "Venomous", readiness.hasGroup("antivenom") ? "anti-venom carried" : "no anti-venom"});
			}
			else if (m.isPoisonous())
			{
				i.warnings.add(new Object[]{BAD, "Poisons", readiness.hasGroup("antipoison") ? "antipoison carried" : "no antipoison"});
			}
			if (m.isCannonImmune())
			{
				i.warnings.add(new Object[]{WARN, "Immune to cannon", ""});
			}
		}
		return i;
	}

	private int layout(Graphics2D g, boolean draw, Info i)
	{
		int x0 = PAD;
		int cw = WIDTH - 2 * PAD;
		int y = PAD;
		FontMetrics fb = g.getFontMetrics(bold);
		FontMetrics fn = g.getFontMetrics(std);
		FontMetrics fs = g.getFontMetrics(small);

		// name and level
		String lvl = i.level > 0 ? "lvl " + i.level : "";
		int lw = fs.stringWidth(lvl);
		text(g, draw, bold, fit(fb, i.name, cw - lw - 8), x0, y + fb.getAscent(), Color.WHITE);
		if (!lvl.isEmpty())
		{
			text(g, draw, small, lvl, x0 + cw - lw, y + fb.getAscent(), DIM);
		}
		y += fb.getHeight();
		// tag chip
		int tw = fs.stringWidth(i.tag) + 8;
		int th = fs.getHeight() + 1;
		if (draw)
		{
			g.setColor(new Color(i.tagColor.getRed(), i.tagColor.getGreen(), i.tagColor.getBlue(), 40));
			g.fillRect(x0, y, tw, th);
			g.setColor(new Color(i.tagColor.getRed(), i.tagColor.getGreen(), i.tagColor.getBlue(), 150));
			g.drawRect(x0, y, tw - 1, th - 1);
		}
		text(g, draw, small, i.tag, x0 + 4, y + fs.getAscent(), i.tagColor);
		y += th + 6;

		// tiles
		if (!i.tiles.isEmpty())
		{
			int n = i.tiles.size();
			int colW = (cw - 2 * (n - 1)) / n;
			int h = fb.getHeight() + fs.getHeight();
			for (int k = 0; k < n; k++)
			{
				int x = x0 + k * (colW + 2);
				if (draw)
				{
					g.setColor(TILE);
					g.fillRect(x, y, colW, h);
				}
				String v = fit(fb, i.tiles.get(k)[0], colW - 4);
				String l = fit(fs, i.tiles.get(k)[1], colW - 4);
				text(g, draw, bold, v, x + (colW - fb.stringWidth(v)) / 2, y + 1 + fb.getAscent(), Color.WHITE);
				text(g, draw, small, l, x + (colW - fs.stringWidth(l)) / 2, y + fb.getHeight() + fs.getAscent() - 2, DIM);
			}
			y += h + 3;
			if (i.maxHitNote != null)
			{
				text(g, draw, small, fit(fs, i.maxHitNote, cw), x0, y + fs.getAscent(), DIM);
				y += fs.getHeight() + 1;
			}
		}

		// rows
		if (!i.rows.isEmpty())
		{
			y = rule(g, draw, x0, y, cw);
			int labelW = 0;
			for (String[] r : i.rows)
			{
				labelW = Math.max(labelW, fs.stringWidth(r[0]));
			}
			labelW += 8;
			for (String[] r : i.rows)
			{
				Color c = "info".equals(r[2]) ? INFO : "ok".equals(r[2]) ? OK : "bad".equals(r[2]) ? BAD : Color.WHITE;
				text(g, draw, small, r[0], x0, y + fn.getAscent(), DIM);
				text(g, draw, std, fit(fn, r[1], cw - labelW), x0 + labelW, y + fn.getAscent(), c);
				y += fn.getHeight();
			}
			y += 1;
		}

		// warnings
		if (!i.warnings.isEmpty())
		{
			y = rule(g, draw, x0, y, cw);
			for (Object[] wn : i.warnings)
			{
				Color c = (Color) wn[0];
				String right = (String) wn[2];
				int rw = right.isEmpty() ? 0 : fs.stringWidth(right);
				if (draw)
				{
					g.setColor(c);
					g.fillOval(x0, y + (fn.getHeight() - 7) / 2, 7, 7);
				}
				text(g, draw, std, fit(fn, (String) wn[1], cw - 12 - rw - 6), x0 + 12, y + fn.getAscent(), c);
				if (rw > 0)
				{
					text(g, draw, small, right, x0 + cw - rw, y + fn.getAscent(), right.startsWith("no ") ? c : DIM);
				}
				y += fn.getHeight();
			}
			y += 1;
		}
		return y + PAD - 2;
	}

	private NPC hovered()
	{
		MenuEntry[] entries = client.getMenu().getMenuEntries();
		if (entries == null)
		{
			return null;
		}
		for (int i = entries.length - 1; i >= 0; i--)
		{
			NPC npc = entries[i].getNpc();
			if (npc != null)
			{
				return npc;
			}
		}
		return null;
	}

	private int rule(Graphics2D g, boolean draw, int x0, int y, int cw)
	{
		if (draw)
		{
			g.setColor(RULE);
			g.fillRect(x0, y + 1, cw, 1);
		}
		return y + 5;
	}

	private static void text(Graphics2D g, boolean draw, Font f, String s, int x, int y, Color c)
	{
		if (!draw || s == null || s.isEmpty())
		{
			return;
		}
		g.setFont(f);
		g.setColor(Color.BLACK);
		g.drawString(s, x + 1, y + 1);
		g.setColor(c);
		g.drawString(s, x, y);
	}

	private static String fit(FontMetrics fm, String s, int max)
	{
		if (s == null)
		{
			return "";
		}
		if (fm.stringWidth(s) <= max)
		{
			return s;
		}
		String t = s;
		while (t.length() > 1 && fm.stringWidth(t + "..") > max)
		{
			t = t.substring(0, t.length() - 1);
		}
		return t.trim() + "..";
	}

	private static String fmt(double d)
	{
		return d == Math.rint(d) ? Long.toString((long) d) : String.valueOf(d);
	}

	private static String cap(String s)
	{
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}
}
