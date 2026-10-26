package com.slayersuite.card;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.alerts.AlertEngine;
import com.slayersuite.history.LevelReached;
import com.slayersuite.history.TaskHistory;
import net.runelite.client.game.SkillIconManager;
import com.slayersuite.task.SuperiorSpawned;
import com.slayersuite.task.KillCounted;
import com.slayersuite.history.TaskStats;
import com.slayersuite.sounds.SoundPlayer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import com.slayersuite.task.TaskFinished;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.TripMath;
import java.awt.AlphaComposite;
import java.awt.GradientPaint;
import java.awt.Paint;
import java.awt.image.BufferedImage;
import net.runelite.client.game.ItemManager;
import com.slayersuite.data.TaskRecord;
import net.runelite.client.util.ImageUtil;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** A big centred "TASK COMPLETE" card that fades in, holds and fades out. Decoration only. */
public class TaskCompleteOverlay extends Overlay
{
	private static final Color GOLD = new Color(232, 180, 60);
	private static final Color RED = new Color(235, 90, 70);
	private static final Color XP_BLUE = new Color(120, 205, 255);
	private static final Color GREEN = new Color(110, 230, 150);
	private static final Color ORANGE = new Color(255, 152, 31);
	private static final Color LABEL = new Color(165, 165, 165);
	private static final long COUNT_MS = 1200;
	private static final long POP_MS = 260;
	private static final Color BAND = new Color(0, 0, 0, 170);
	private static final long IN_MS = 350;
	private static final long OUT_MS = 650;

	private final Client client;
	private final SlayerSuiteConfig config;
	private final TaskHistory history;
	private final TaskTracker tracker;
	private final ItemManager itemManager;
	private volatile BufferedImage icon;
	private volatile int iconFor = -1;

	private volatile long shownAt;
	private volatile long durationMs;
	private volatile String title = "";
	private volatile String subtitle = "";
	private volatile String detail = "";
	private volatile Color accent = GOLD;
	/** The counters under the task name: kills, time, xp, loot, points, streak. Numbers count up. */
	private volatile List<Stat> stats = Collections.emptyList();
	/** Picked-up drops of the assignment: sprite with its quantity, and the value under it. */
	private volatile List<Object[]> loot = Collections.emptyList();
	/** Drops from your task kills that were left on the floor. */
	private volatile List<Object[]> missed = Collections.emptyList();
	private static final Color MISSED = new Color(225, 135, 135);
	private static final String MISSED_LABEL = "LEFT BEHIND";
	private static final int LOOT_SLOT = 58;
	private static final int LOOT_ICON_W = 54;
	private static final int LOOT_ICON_H = 48;
	private static final String LOOT_LABEL = "PICKED UP";
	private volatile String ribbon = "";
	private volatile boolean sparkles;
	private float[] sparkX;
	private float[] sparkY;
	private float[] sparkV;
	private final SoundPlayer sounds;
	private final SkillIconManager skillIcons;
	private final com.slayersuite.task.SlayerMode mode;

	// last-kills countdown
	private volatile long digitShownAt;
	private volatile int digit;

	@Inject
	public TaskCompleteOverlay(Client client, SlayerSuiteConfig config, TaskHistory history, TaskTracker tracker, ItemManager itemManager, SoundPlayer sounds, SkillIconManager skillIcons, com.slayersuite.task.SlayerMode mode)
	{
		this.skillIcons = skillIcons;
		this.mode = mode;
		this.sounds = sounds;
		this.itemManager = itemManager;
		this.client = client;
		this.config = config;
		this.history = history;
		this.tracker = tracker;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(PRIORITY_HIGHEST);
	}

	/** One counter on the completion card: a coloured value with a small label under it. */
	private static final class Stat
	{
		final String label;
		final Color color;
		final long value;
		final String fixed;
		final boolean plus;
		final boolean shortForm;

		Stat(String label, Color color, long value, boolean plus, boolean shortForm)
		{
			this.label = label;
			this.color = color;
			this.value = value;
			this.fixed = null;
			this.plus = plus;
			this.shortForm = shortForm;
		}

		Stat(String label, Color color, String fixed)
		{
			this.label = label;
			this.color = color;
			this.value = 0;
			this.fixed = fixed;
			this.plus = false;
			this.shortForm = false;
		}

		String text(float k)
		{
			if (fixed != null)
			{
				return fixed;
			}
			long v = Math.round(value * k);
			String t = shortForm ? TripMath.formatK(v) : Long.toString(v);
			return plus ? "+" + t : t;
		}
	}

	private void loadIcon()
	{
		TaskRecord rec = tracker.getRecord();
		Integer id = rec == null ? null : rec.getIconItemId();
		if (id == null)
		{
			icon = null;
			iconFor = -1;
			return;
		}
		if (iconFor == id && icon != null)
		{
			return;
		}
		try
		{
			BufferedImage raw = itemManager.getImage(id, 1, false);
			icon = raw == null ? null : ImageUtil.resizeImage(raw, raw.getWidth() * 2, raw.getHeight() * 2);
			iconFor = id;
		}
		catch (RuntimeException e)
		{
			icon = null;
		}
	}

	public void show(String title, String subtitle, String detail, Color accent, long durationMs)
	{
		this.title = title;
		this.subtitle = subtitle == null ? "" : subtitle;
		this.detail = detail == null ? "" : detail;
		this.accent = accent;
		this.durationMs = durationMs;
		this.shownAt = System.currentTimeMillis();
	}

	@Subscribe
	public void onTaskFinished(TaskFinished e)
	{
		if (!config.completeBanner())
		{
			return;
		}
		loadIcon();
		long kills = Math.max(history.getAssignmentKills(), history.getSessionKills());
		long xp = Math.max(history.getAssignmentXp(), history.getSessionXp());
		long gp = Math.max(history.getAssignmentGp(), history.getSessionGp());
		long time = Math.max(history.getAssignmentMs(), tracker.timeOnTaskMillis());
		if (e.isTest() && kills == 0)
		{
			// the test button before any kill on this assignment: sample numbers so the whole card shows
			TaskRecord rec = tracker.getRecord();
			kills = tracker.getInitial() > 0 ? tracker.getInitial() : 150;
			double perKill = 300;
			if (rec != null && !rec.getMonsters().isEmpty() && rec.getMonsters().get(0).getSlayerXp() != null)
			{
				perKill = rec.getMonsters().get(0).getSlayerXp();
			}
			xp = Math.round(kills * perKill);
			gp = kills * 1800L;
			if (time < 60_000L)
			{
				time = 38 * 60_000L;
			}
		}
		List<Stat> list = new ArrayList<>();
		if (kills > 0)
		{
			list.add(new Stat("KILLS", Color.WHITE, kills, false, false));
		}
		if (time > 60_000L)
		{
			list.add(new Stat("TIME", Color.WHITE, TaskTracker.formatDuration(time)));
		}
		if (xp > 0)
		{
			list.add(new Stat("XP", XP_BLUE, xp, true, true));
		}
		if (gp > 0)
		{
			list.add(new Stat("LOOT", GOLD, gp, true, true));
		}
		if (e.getPoints() > 0)
		{
			list.add(new Stat("POINTS", GREEN, e.getPoints(), true, false));
		}
		if (e.getStreak() > 0)
		{
			list.add(new Stat("STREAK", ORANGE, e.getStreak(), false, false));
		}
		long missedValue = history.getAssignmentMissedValue();
		if (missedValue > 0)
		{
			list.add(new Stat("MISSED", MISSED, missedValue, false, true));
		}
		stats = list;
		loot = sprites(history.topLoot(8));
		missed = sprites(history.topMissed(6));
		ribbon = "";
		if (config.cardSparkles())
		{
			TaskStats st = history.getCurrent();
			if (st != null && st.hasRate() && time > 120_000L && kills >= 10)
			{
				double rate = kills * 3_600_000.0 / time;
				if (rate > st.killsPerHour() * 1.1)
				{
					ribbon = "NEW BEST RATE  " + Math.round(rate) + " kills/h";
				}
			}
			int s = e.getStreak();
			if (s > 0 && (s == 10 || s == 25 || s % 50 == 0))
			{
				ribbon = ribbon.isEmpty() ? "STREAK " + s : ribbon + "   STREAK " + s;
			}
		}
		sparkles = config.cardSparkles();
		seedSparkles();
		show("TASK COMPLETE", AlertEngine.pretty(e.getName()), e.isTest() ? "" : history.assignmentDiagnostics(), GOLD, config.completeBannerSeconds() * 1000L);
	}

	/** Item sprites with their stack size, each paired with its value text. */
	private List<Object[]> sprites(List<TaskHistory.LootItem> items)
	{
		List<Object[]> icons = new ArrayList<>();
		for (TaskHistory.LootItem li : items)
		{
			try
			{
				int q = (int) Math.min(li.getQuantity(), Integer.MAX_VALUE);
				BufferedImage img = itemManager.getImage(li.getId(), q, q > 1);
				if (img != null)
				{
					icons.add(new Object[]{img, TripMath.formatK(li.getValue())});
				}
			}
			catch (RuntimeException ex)
			{
				// no sprite for this item
			}
		}
		return icons;
	}

	private void seedSparkles()
	{
		Random r = new Random(System.nanoTime());
		int n = 28;
		sparkX = new float[n];
		sparkY = new float[n];
		sparkV = new float[n];
		for (int i = 0; i < n; i++)
		{
			sparkX[i] = r.nextFloat();
			sparkY[i] = r.nextFloat();
			sparkV[i] = 0.4f + r.nextFloat() * 0.8f;
		}
	}

	@Subscribe
	public void onLevelReached(LevelReached e)
	{
		if (!config.levelCard() || (!e.isTest() && !mode.active() && e.getSkill() != net.runelite.api.Skill.SLAYER))
		{
			return;
		}
		try
		{
			BufferedImage raw = skillIcons.getSkillImage(e.getSkill(), false);
			icon = raw == null ? null : ImageUtil.resizeImage(raw, raw.getWidth() * 2, raw.getHeight() * 2);
			iconFor = -1;
		}
		catch (RuntimeException ex)
		{
			icon = null;
		}
		List<Stat> list = new ArrayList<>();
		if (!e.isFirstSeen())
		{
			if (e.getKills() > 0)
			{
				list.add(new Stat("KILLS", Color.WHITE, e.getKills(), false, false));
			}
			if (e.getActiveMs() > 60_000L)
			{
				list.add(new Stat("TIME", Color.WHITE, TaskTracker.formatDuration(e.getActiveMs())));
			}
			if (e.getLootValue() > 0)
			{
				list.add(new Stat("LOOT", GOLD, e.getLootValue(), true, true));
			}
		}
		if (e.getXpToNext() > 0)
		{
			list.add(new Stat("TO " + (e.getLevel() + 1), XP_BLUE, e.getXpToNext(), false, true));
		}
		if (e.getMissedValue() > 0)
		{
			list.add(new Stat("MISSED", MISSED, e.getMissedValue(), false, true));
		}
		stats = list;
		loot = sprites(e.getLoot());
		missed = sprites(e.getMissed());
		ribbon = e.getLevel() == 99 ? "SKILL MASTERED" : e.getLevel() % 10 == 0 ? "MILESTONE" : "";
		sparkles = config.cardSparkles();
		seedSparkles();
		String skill = e.getSkill().getName();
		show("LEVEL UP", skill + " " + e.getLevel(), e.isFirstSeen() && !e.isTest() ? "Counting kills and drops from here for the next level" : "", XP_BLUE, config.completeBannerSeconds() * 1000L);
		if (e.isTest() && config.soundLevelUp())
		{
			sounds.play(SoundPlayer.Sound.LEVEL_UP, config.soundVolume());
		}
	}

	@Subscribe
	public void onSuperiorSpawned(SuperiorSpawned e)
	{
		if (!config.superiorBanner())
		{
			return;
		}
		loadIcon();
		String name = tracker.getRecord() != null && !tracker.getRecord().getSuperiors().isEmpty() ? tracker.getRecord().getSuperiors().get(0) : "Superior";
		stats = Collections.emptyList();
		loot = Collections.emptyList();
		missed = Collections.emptyList();
		ribbon = "";
		sparkles = false;
		show("SUPERIOR", name, "", RED, 2200);
	}

	@Subscribe
	public void onKillCounted(KillCounted e)
	{
		int n = config.countdownLastKills();
		if (n <= 0 || e.getRemaining() <= 0 || e.getRemaining() > n)
		{
			return;
		}
		digit = e.getRemaining();
		digitShownAt = System.currentTimeMillis();
		if (config.soundCritical() || config.soundTaskDone())
		{
			sounds.play(SoundPlayer.Sound.TICK, config.soundVolume());
		}
	}

	/** The big fading digit for the last kills. */
	private void renderDigit(Graphics2D g)
	{
		long start = digitShownAt;
		if (start == 0)
		{
			return;
		}
		long t = System.currentTimeMillis() - start;
		if (t > 900)
		{
			digitShownAt = 0;
			return;
		}
		float k = t / 900f;
		float alpha = k < 0.15f ? k / 0.15f : 1f - (k - 0.15f) / 0.85f;
		float scale = 1.6f - 0.6f * ease(Math.min(1f, k * 3));
		Composite old = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
		Font f = FontManager.getRunescapeBoldFont().deriveFont(72f * scale);
		g.setFont(f);
		String s = Integer.toString(digit);
		FontMetrics fm = g.getFontMetrics();
		int x = client.getCanvasWidth() / 2 - fm.stringWidth(s) / 2;
		int y = (int) (client.getCanvasHeight() * 0.42) + fm.getAscent() / 2;
		shadowed(g, f, s, x, y, digit == 1 ? GOLD : Color.WHITE);
		g.setComposite(old);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		renderDigit(g);
		long start = shownAt;
		if (start == 0)
		{
			return null;
		}
		long t = System.currentTimeMillis() - start;
		if (t > durationMs)
		{
			shownAt = 0;
			return null;
		}
		float alpha;
		float scale = 1f;
		if (t < IN_MS)
		{
			float k = t / (float) IN_MS;
			alpha = k;
			scale = 1.25f - 0.25f * ease(k);
		}
		else if (t > durationMs - OUT_MS)
		{
			alpha = Math.max(0f, (durationMs - t) / (float) OUT_MS);
		}
		else
		{
			alpha = 1f;
		}

		int w = client.getCanvasWidth();
		int h = client.getCanvasHeight();
		int cx = w / 2;
		int cy = (int) (h * 0.30);

		Composite old = g.getComposite();
		Object aa = g.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

		Font big = FontManager.getRunescapeBoldFont().deriveFont(38f * scale);
		Font mid = FontManager.getRunescapeBoldFont().deriveFont(22f * scale);
		Font small = FontManager.getRunescapeFont().deriveFont(16f * scale);

		g.setFont(big);
		FontMetrics fb = g.getFontMetrics();
		g.setFont(mid);
		FontMetrics fm = g.getFontMetrics();
		g.setFont(small);
		FontMetrics fs = g.getFontMetrics();

		List<Stat> chips = stats;
		Font val = FontManager.getRunescapeBoldFont().deriveFont(24f * scale);
		Font lab = FontManager.getRunescapeSmallFont().deriveFont(12f * scale);
		g.setFont(val);
		FontMetrics fv = g.getFontMetrics();
		g.setFont(lab);
		FontMetrics fl = g.getFontMetrics();
		int[] chipW = new int[chips.size()];
		int rowW = 0;
		for (int i = 0; i < chips.size(); i++)
		{
			Stat st = chips.get(i);
			chipW[i] = Math.max(fv.stringWidth(st.text(1f)), fl.stringWidth(st.label)) + 26;
			rowW += chipW[i];
		}
		int rowH = chips.isEmpty() ? 0 : fv.getAscent() + fl.getHeight() + 14;
		List<Object[]> drops = loot;
		List<Object[]> leftBehind = missed;
		int rowBlockH = fl.getHeight() + 4 + LOOT_ICON_H + 8 + fl.getHeight() + 4;
		int lootW = Math.max(drops.size(), leftBehind.size()) * LOOT_SLOT;
		int lootH = (drops.isEmpty() ? 0 : rowBlockH) + (leftBehind.isEmpty() ? 0 : rowBlockH);

		BufferedImage img = icon;
		int iconW = img == null ? 0 : (int) (img.getWidth() * scale) + 24;
		int bandH = fb.getHeight() + (subtitle.isEmpty() ? 0 : fm.getHeight()) + (detail.isEmpty() ? 0 : fs.getHeight()) + rowH + lootH + 28;
		int textW = Math.max(Math.max(fb.stringWidth(title), Math.max(rowW, lootW)), Math.max(fm.stringWidth(subtitle), fs.stringWidth(detail)));
		int bandW = Math.min(textW + iconW + 140, w - 20);
		int top = cy - bandH / 2;
		int left = cx - bandW / 2;
		// a band that fades out at both ends, with the accent rules above and below
		Paint old2 = g.getPaint();
		g.setPaint(new GradientPaint(left, 0, new Color(0, 0, 0, 0), left + bandW / 2f, 0, BAND, true));
		g.fillRect(left, top, bandW, bandH);
		g.setPaint(new GradientPaint(left, 0, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0), left + bandW / 2f, 0, accent, true));
		g.fillRect(left, top, bandW, 2);
		g.fillRect(left, top + bandH - 2, bandW, 2);
		g.setPaint(old2);

		int textCx = cx + iconW / 2;
		if (img != null)
		{
			int iw = (int) (img.getWidth() * scale);
			int ih = (int) (img.getHeight() * scale);
			int ix = textCx - textW / 2 - 16 - iw;
			int iy = cy - ih / 2;
			g.drawImage(img, ix, iy, iw, ih, null);
		}
		cx = textCx;

		if (sparkles && sparkX != null)
		{
			// gold motes drifting up through the band
			for (int i = 0; i < sparkX.length; i++)
			{
				float life = ((t / 1000f) * sparkV[i] + sparkY[i]) % 1f;
				int sx = left + (int) (sparkX[i] * bandW);
				int sy = top + bandH - (int) (life * (bandH + 20));
				float a = (1f - life) * 0.9f;
				g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), (int) (255 * Math.max(0f, Math.min(1f, a)))));
				int r = i % 3 == 0 ? 3 : 2;
				g.fillOval(sx, sy, r, r);
			}
		}

		int y = top + 14 + fb.getAscent();
		if (!ribbon.isEmpty())
		{
			Font rib = FontManager.getRunescapeBoldFont().deriveFont(15f * scale);
			g.setFont(rib);
			FontMetrics fr = g.getFontMetrics();
			shadowed(g, rib, ribbon, cx - fr.stringWidth(ribbon) / 2, top - 8, new Color(110, 230, 150));
		}
		shadowed(g, big, title, cx - fb.stringWidth(title) / 2, y, accent);
		y += fb.getDescent() + 2;
		if (!subtitle.isEmpty())
		{
			y += fm.getAscent();
			shadowed(g, mid, subtitle, cx - fm.stringWidth(subtitle) / 2, y, Color.WHITE);
			y += fm.getDescent();
		}
		if (!detail.isEmpty())
		{
			y += fs.getAscent() + 2;
			shadowed(g, small, detail, cx - fs.stringWidth(detail) / 2, y, new Color(200, 200, 200));
		}
		if (!chips.isEmpty())
		{
			// counters: big coloured value, small grey label under it, thin accent rules between
			boolean animate = config.cardSparkles();
			float k = animate ? ease(Math.min(1f, Math.max(0f, (t - IN_MS) / (float) COUNT_MS))) : 1f;
			long sinceDone = t - IN_MS - COUNT_MS;
			float pop = animate && sinceDone >= 0 && sinceDone < POP_MS ? 1f + 0.22f * (1f - sinceDone / (float) POP_MS) : 1f;
			int rowLeft = cx - rowW / 2;
			int rowTop = y + 10;
			int x = rowLeft;
			for (int i = 0; i < chips.size(); i++)
			{
				Stat st = chips.get(i);
				int slotCx = x + chipW[i] / 2;
				Font f = st.fixed == null && pop != 1f ? val.deriveFont(24f * scale * pop) : val;
				g.setFont(f);
				FontMetrics fp = g.getFontMetrics();
				String text = st.text(k);
				shadowed(g, f, text, slotCx - fp.stringWidth(text) / 2, rowTop + fv.getAscent(), st.color);
				shadowed(g, lab, st.label, slotCx - fl.stringWidth(st.label) / 2, rowTop + fv.getAscent() + fl.getAscent() + 4, LABEL);
				if (i > 0)
				{
					g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 90));
					g.fillRect(x, rowTop + 2, 1, rowH - 12);
				}
				x += chipW[i];
			}
			y = rowTop + rowH;
		}
		if (!drops.isEmpty())
		{
			y = lootRow(g, drops, LOOT_LABEL, GOLD, 1f, cx, y, fl, lab);
		}
		if (!leftBehind.isEmpty())
		{
			y = lootRow(g, leftBehind, MISSED_LABEL, MISSED, 0.62f, cx, y, fl, lab);
		}

		g.setComposite(old);
		if (aa != null)
		{
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, aa);
		}
		return null;
	}

	/**
	 * A row of item tiles under a small label: dark tile, sprite scaled up crisp with its stack
	 * size, the value under it in the given colour. Returns the y below the row.
	 */
	private int lootRow(Graphics2D g, List<Object[]> items, String labelText, Color valueColor, float alpha, int cx, int y, FontMetrics fl, Font lab)
	{
		int rowW = items.size() * LOOT_SLOT;
		int rowLeft = cx - rowW / 2;
		int labelY = y + 2;
		shadowed(g, lab, labelText, cx - fl.stringWidth(labelText) / 2, labelY + fl.getAscent(), LABEL);
		int top = labelY + fl.getHeight() + 4;
		Object interp = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		Composite before = g.getComposite();
		if (alpha < 1f && before instanceof AlphaComposite)
		{
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, ((AlphaComposite) before).getAlpha() * alpha))));
		}
		for (int i = 0; i < items.size(); i++)
		{
			BufferedImage sprite = (BufferedImage) items.get(i)[0];
			String v = (String) items.get(i)[1];
			int slotX = rowLeft + i * LOOT_SLOT;
			g.setColor(new Color(0, 0, 0, 90));
			g.fillRect(slotX + 2, top, LOOT_SLOT - 4, LOOT_ICON_H + 6);
			g.setColor(new Color(valueColor.getRed(), valueColor.getGreen(), valueColor.getBlue(), 70));
			g.drawRect(slotX + 2, top, LOOT_SLOT - 5, LOOT_ICON_H + 5);
			int dw = LOOT_ICON_W;
			int dh = (int) Math.round(sprite.getHeight() * (dw / (double) Math.max(1, sprite.getWidth())));
			if (dh > LOOT_ICON_H)
			{
				dh = LOOT_ICON_H;
				dw = (int) Math.round(sprite.getWidth() * (dh / (double) Math.max(1, sprite.getHeight())));
			}
			g.drawImage(sprite, slotX + (LOOT_SLOT - dw) / 2, top + 3 + (LOOT_ICON_H - dh) / 2, dw, dh, null);
			shadowed(g, lab, v, slotX + (LOOT_SLOT - fl.stringWidth(v)) / 2, top + LOOT_ICON_H + 8 + fl.getAscent(), valueColor);
		}
		g.setComposite(before);
		if (interp != null)
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interp);
		}
		return top + LOOT_ICON_H + 8 + fl.getHeight() + 4;
	}

	private static void shadowed(Graphics2D g, Font f, String s, int x, int y, Color c)
	{
		g.setFont(f);
		g.setColor(Color.BLACK);
		// a one pixel outline plus a drop shadow reads cleanly over any cave colour
		g.drawString(s, x - 1, y);
		g.drawString(s, x + 1, y);
		g.drawString(s, x, y - 1);
		g.drawString(s, x, y + 1);
		g.drawString(s, x + 2, y + 2);
		g.setColor(c);
		g.drawString(s, x, y);
	}

	private static float ease(float k)
	{
		return 1f - (1f - k) * (1f - k);
	}
}
