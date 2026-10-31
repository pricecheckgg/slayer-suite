package com.slayersuite.card;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.advisor.Advisor;
import com.slayersuite.alerts.AlertEngine;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.history.SuperiorMath;
import com.slayersuite.history.TaskHistory;
import com.slayersuite.history.TaskStats;
import com.slayersuite.readiness.Missing;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.task.GameTaskTable;
import com.slayersuite.task.TaskModifier;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.TripMath;
import com.slayersuite.trip.TripTracker;
import java.awt.Color;
import com.slayersuite.ui.Palette;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.BackgroundComponent;

/**
 * The one task card, drawn as four short blocks: the header (icon, name, kills left, bar),
 * the numbers (ETA, rate, points, streak as tiles), how to fight it (style, superior,
 * finishing item), where you are or where to go, and a gear checklist.
 */
public class TaskCardOverlay extends Overlay
{
	private static final Color OK = Palette.OK;
	private static final Color MISSING = Palette.BAD;
	private static final Color RECOMMENDED = Palette.WARN;
	private static final Color DIM = Palette.DIM;
	private static final Color CYAN = new Color(80, 220, 255);
	private static final Color BAR = new Color(90, 160, 90);
	private static final Color BAR_BG = new Color(60, 60, 60);
	private static final Color TILE = Palette.TILE;
	private static final Color RULE = Palette.RULE;
	private static final Color ACCENT = ColorScheme.BRAND_ORANGE;
	private static final int PAD = 6;
	private static final int DEFAULT_WIDTH = 260;

	private final TaskTracker tracker;
	private final ReadinessChecker readiness;
	private final AlertEngine alerts;
	private final SlayerDataset dataset;
	private final SlayerSuiteConfig config;
	private final TaskHistory history;
	private final TripTracker trip;
	private final Advisor advisor;
	private final com.slayersuite.task.SlayerMode mode;
	private final javax.inject.Provider<com.slayersuite.advisor.PathOverlay> pathOverlay;
	private final net.runelite.client.eventbus.EventBus eventBus;
	private final ItemManager itemManager;
	private BufferedImage itemIcon;
	private int itemIconFor = -1;

	private final Font std = FontManager.getRunescapeFont();
	private final Font small = FontManager.getRunescapeSmallFont();
	private final Font bold = FontManager.getRunescapeBoldFont();

	@Inject
	public TaskCardOverlay(TaskTracker tracker, ReadinessChecker readiness, AlertEngine alerts, SlayerDataset dataset, SlayerSuiteConfig config, TaskHistory history, TripTracker trip, Advisor advisor, com.slayersuite.task.SlayerMode mode, javax.inject.Provider<com.slayersuite.advisor.PathOverlay> pathOverlay, net.runelite.client.eventbus.EventBus eventBus, ItemManager itemManager)
	{
		this.eventBus = eventBus;
		this.pathOverlay = pathOverlay;
		this.mode = mode;
		this.advisor = advisor;
		this.tracker = tracker;
		this.readiness = readiness;
		this.alerts = alerts;
		this.dataset = dataset;
		this.config = config;
		this.history = history;
		this.trip = trip;
		this.itemManager = itemManager;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_MED);
		setResizable(true);
		setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
		// Alt + right-click the card to try the displays without finishing a task.
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Test", "task complete card and sound", e ->
			eventBus.post(new com.slayersuite.task.TaskFinished(tracker.hasTask() ? tracker.getTaskName() : "Test task", 20, 12, true)));
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Test", "superior card and sound", e ->
			tracker.testSuperior());
		addMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY, "Test", "critical alert", e -> alerts.testAlert());
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.showCard())
		{
			return null;
		}
		if (!mode.active() && !config.cardWhenDormant() && !readiness.isBankOpen())
		{
			return null;
		}
		if (!tracker.hasTask() && !config.cardWithoutTask() && !advisor.loopActive())
		{
			return null;
		}
		Dimension pref = getPreferredSize();
		int w = pref != null && pref.width >= 180 ? pref.width : DEFAULT_WIDTH;
		int h = layout(g, false, w);
		BackgroundComponent bg = new BackgroundComponent();
		bg.setRectangle(new Rectangle(0, 0, w, h));
		bg.render(g);
		// a hairline in the chevron colour, so the card and the route read as one thing
		Color accent = config.chevronColour().resolve(config.customChevronColour());
		g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200));
		g.fillRect(1, 1, w - 2, 2);
		layout(g, true, w);
		return new Dimension(w, h);
	}

	/** Lays the card out top to bottom; with draw=false it only measures. Returns the height. */
	private int layout(Graphics2D g, boolean draw, int w)
	{
		int x0 = PAD;
		int cw = w - 2 * PAD;
		int y = PAD;
		FontMetrics fb = g.getFontMetrics(bold);
		FontMetrics fs = g.getFontMetrics(small);
		FontMetrics fn = g.getFontMetrics(std);

		if (!tracker.hasTask())
		{
			if (advisor.loopActive())
			{
				return loopCard(g, draw, x0, y, cw, fb, fn, fs);
			}
			String t = "No slayer task";
			text(g, draw, small, t, x0 + (cw - fs.stringWidth(t)) / 2, y + fs.getAscent(), DIM);
			return y + fs.getHeight() + PAD;
		}

		TaskRecord rec = tracker.getRecord();
		boolean compact = config.compactCard();
		int remaining = tracker.getRemaining();
		int initial = Math.max(tracker.getInitial(), remaining);

		// ---- header: icon, name, kills left, bar
		BufferedImage icon = itemIcon(rec);
		int iconW = icon == null ? 0 : 22;
		int iconH = 20;
		String left = remaining + " left";
		String of = initial > 0 ? "  of " + initial : "";
		int rightW = fb.stringWidth(left) + fs.stringWidth(of);
		String title = rec != null ? rec.title() : AlertEngine.pretty(tracker.getTaskName());
		if (tracker.isFromChat())
		{
			title += " *";
		}
		title = fit(fb, title, cw - iconW - (iconW > 0 ? 4 : 0) - rightW - 8);
		int lineH = Math.max(fb.getHeight(), iconH);
		int base = y + (lineH + fb.getAscent() - fb.getDescent()) / 2;
		if (icon != null && draw)
		{
			g.drawImage(icon, x0, y + (lineH - iconH) / 2, iconW, iconH, null);
		}
		text(g, draw, bold, title, x0 + iconW + (iconW > 0 ? 4 : 0), base, Color.WHITE);
		int rx = x0 + cw - rightW;
		text(g, draw, bold, left, rx, base, Color.WHITE);
		text(g, draw, small, of, rx + fb.stringWidth(left), base, DIM);
		y += lineH + 4;
		if (draw)
		{
			g.setColor(BAR_BG);
			g.fillRect(x0, y, cw, 5);
			g.setColor(BAR);
			g.fillRect(x0, y, initial > 0 ? (int) Math.round(cw * (initial - remaining) / (double) initial) : 0, 5);
		}
		y += 5 + 6;

		// ---- the numbers, as tiles
		List<String[]> tiles = new ArrayList<>();
		if (config.cardShowEta())
		{
			long eta = tracker.etaMillis();
			double kph = tracker.effectiveKph();
			String done = eta < 0 ? "" : "  " + LocalTime.now().plusSeconds(eta / 1000).format(DateTimeFormatter.ofPattern("HH:mm"));
			tiles.add(new String[]{eta < 0 ? "--" : TaskTracker.formatDuration(eta), "ETA" + done});
			tiles.add(new String[]{kph > 0 ? Long.toString(Math.round(kph)) : "--", tracker.isEtaFromHistory() ? "KILLS/H  HIST" : "KILLS/H"});
		}
		if (tracker.getSlayerPoints() > 0)
		{
			tiles.add(new String[]{String.format("%,d", tracker.getSlayerPoints()), "POINTS"});
			tiles.add(new String[]{Integer.toString(tracker.getTaskStreak()), "STREAK"});
		}
		if (!tiles.isEmpty())
		{
			y += tiles(g, draw, x0, y, cw, tiles, fb, fs);
			y += 4;
		}
		if (compact)
		{
			y = checklist(g, draw, x0, y, cw, rec, true, fn, fs);
			return y + PAD - 2;
		}

		// ---- how to fight it
		List<Cell> fight = new ArrayList<>();
		if (rec != null)
		{
			if (config.cardShowPrayer() && rec.getStyle() != null)
			{
				fight.add(new Cell("STYLE", rec.getStyle(), Color.WHITE, null));
			}
			ItemRule fin = rec.getFinishingItem();
			if (fin != null && fin.getAnyOf() != null)
			{
				boolean have = dataset.getItems().anySatisfied(fin.getAnyOf(), readiness.heldNames());
				fight.add(new Cell("FINISH" + (fin.getThreshold() != null ? "  AT " + fin.getThreshold() + " HP" : ""), dataset.getItems().describe(fin.getAnyOf()), have ? OK : MISSING, null));
			}
			if (!rec.getSuperiors().isEmpty())
			{
				fight.add(new Cell("SUPERIOR", rec.getSuperiors().get(0), Color.WHITE, null));
			}
		}
		TaskModifier mod = tracker.getModifier();
		if (mod.present())
		{
			fight.add(new Cell("BONUS", mod.label(), bonusColour(mod), null));
		}
		if (!fight.isEmpty())
		{
			y = rule(g, draw, x0, y, cw);
			y += cells(g, draw, x0, y, cw, fight, fn, fs);
			y += 2;
		}

		// ---- the drops you are waiting on
		if (config.cardShowOdds())
		{
			List<TaskHistory.DryOdds> odds = history.dryOdds(2, 1.0 / 64);
			// Mortimer's superior boost: the unique table per superior, boosted and plain
			boolean boosted = mod.getKind() == TaskModifier.Kind.SUPERIOR_CHANCE && rec != null && rec.getSlayerLevel() != null && !rec.getSuperiors().isEmpty();
			if (!odds.isEmpty() || boosted)
			{
				y = rule(g, draw, x0, y, cw);
				if (!odds.isEmpty())
				{
					y = oddsRows(g, draw, x0, y, cw, odds, fn, fs);
				}
				if (boosted)
				{
					y = superiorRows(g, draw, x0, y, cw, rec.getSlayerLevel(), mod.getValue(), fn, fs);
				}
				y += 2;
			}
		}

		// ---- where you are, or where to go
		if (config.cardShowLocation())
		{
			// measure first so an empty block draws no rule
			int probe = rule(g, false, x0, y, cw);
			if (place(g, false, x0, probe, cw, rec, fn, fs) > probe)
			{
				y = rule(g, draw, x0, y, cw);
				y = place(g, draw, x0, y, cw, rec, fn, fs);
			}
		}

		// ---- gear checklist
		y = checklist(g, draw, x0, y, cw, rec, false, fn, fs);

		// ---- small print
		List<String[]> foot = new ArrayList<>();
		if (config.cardShowSupplies())
		{
			if (history.getAssignmentXp() > 0 || history.getAssignmentGp() > 0)
			{
				foot.add(new String[]{"This task  " + TripMath.formatK(history.getAssignmentXp()) + " xp,  " + TripMath.formatK(history.getAssignmentGp()) + " gp"
					+ (history.getAssignmentMs() > 60_000L ? ",  " + TaskTracker.formatDuration(history.getAssignmentMs()) : ""), null});
			}
			TaskStats stats = history.getCurrent();
			if (stats != null && stats.hasRate() && history.getSessionKills() < 3)
			{
				foot.add(new String[]{"Your usual  " + TripMath.formatK(Math.round(stats.xpPerHour())) + " xp/h,  " + TripMath.formatK(Math.round(stats.gpPerHour())) + " gp/h", null});
			}
			if (history.getAdvice() != null)
			{
				foot.add(new String[]{history.getAdvice(), history.getAdvice().startsWith("Low") ? "warn" : "ok"});
			}
			if (config.floorLoot() && history.getFloorValue() > 0)
			{
				long floorLeft = history.getFloorSoonestAt() - System.currentTimeMillis();
				foot.add(new String[]{"On the floor  " + TripMath.formatK(history.getFloorValue()) + " gp" + (floorLeft > 0 ? ",  " + com.slayersuite.history.FloorLootMath.countdown(floorLeft) + " left" : ""),
					floorLeft > 0 && floorLeft <= com.slayersuite.history.FloorLootMath.URGENT_MS ? "warn" : null});
			}
		}
		GameTaskTable.GameTask gt = tracker.getGameTask();
		if (gt != null && tracker.rollChance() > 0)
		{
			foot.add(new String[]{GameTaskTable.masterName(gt.getMasterId()) + " rolls this " + Math.round(tracker.rollChance() * 100) + "% of the time", null});
		}
		if (rec == null)
		{
			foot.add(new String[]{"No dataset entry yet", null});
		}
		if (alerts.isPaused())
		{
			foot.add(new String[]{"Alerts paused", "warn"});
		}
		if (!foot.isEmpty())
		{
			y = rule(g, draw, x0, y, cw);
			for (String[] f : foot)
			{
				Color c = "warn".equals(f[1]) ? RECOMMENDED : "ok".equals(f[1]) ? OK : DIM;
				text(g, draw, small, fit(fs, f[0], cw), x0, y + fs.getAscent(), c);
				y += fs.getHeight();
			}
		}
		return y + PAD - 2;
	}

	// ---------------------------------------------------------------- blocks

	/** Between tasks: where the loop guide is taking you, the next move and the distance. */
	private int loopCard(Graphics2D g, boolean draw, int x0, int y, int cw, FontMetrics fb, FontMetrics fn, FontMetrics fs)
	{
		text(g, draw, small, "BETWEEN TASKS", x0, y + fs.getAscent(), DIM);
		y += fs.getHeight() + 2;
		for (String line : wrap(fb, advisor.loopText(), cw))
		{
			text(g, draw, bold, line, x0, y + fb.getAscent(), Color.WHITE);
			y += fb.getHeight();
		}
		if (advisor.isArrived())
		{
			text(g, draw, std, "You are there", x0, y + fn.getAscent(), OK);
			y += fn.getHeight();
		}
		else
		{
			String step = advisor.getNextStep();
			if (step != null && !step.isEmpty())
			{
				y += 2;
				for (String line : wrap(fs, step, cw))
				{
					text(g, draw, small, line, x0, y + fs.getAscent(), DIM);
					y += fs.getHeight();
				}
			}
			int dist = advisor.cachedDistance();
			if (dist >= 0)
			{
				text(g, draw, small, TripMath.formatK(dist) + " tiles " + advisor.cachedDirection(), x0, y + fs.getAscent(), DIM);
				y += fs.getHeight();
			}
		}
		return y + PAD - 2;
	}

	private int place(Graphics2D g, boolean draw, int x0, int y, int cw, TaskRecord rec, FontMetrics fn, FontMetrics fs)
	{
		LocationRecord loc = tracker.getCurrentLocation();
		LocationRecord dest = advisor.getDestination();
		boolean guiding = dest != null && !advisor.isArrived();
		if (guiding)
		{
			// a spot is pinned and you are not at its spawns yet: the route comes first, wherever you stand
			int dist = advisor.cachedDistance();
			y += dotRow(g, draw, x0, y, cw, CYAN, dest.getName(), Color.WHITE, dist >= 0 ? TripMath.formatK(dist) + " " + advisor.cachedDirection() : "", DIM, fn, fs);
			String step = advisor.getNextStep();
			if (step != null && !step.isEmpty())
			{
				y += callout(g, draw, x0 + 12, y, cw - 12, "NEXT", step, fn, fs);
			}
			String via = pathOverlay.get().entranceLabel();
			if (via != null)
			{
				int tiles = pathOverlay.get().lineTiles();
				text(g, draw, small, fit(fs, via + (tiles > 0 ? ", " + tiles + " tiles, marked on the floor" : ", marked on the floor"), cw - 12), x0 + 12, y + fs.getAscent(), CYAN);
				y += fs.getHeight();
			}
			else if (pathOverlay.get().blocked())
			{
				text(g, draw, small, fit(fs, "The chevrons stop short here: follow the arrow past it", cw - 12), x0 + 12, y + fs.getAscent(), RECOMMENDED);
				y += fs.getHeight();
			}
			if (loc != null && !Advisor.sameLocation(loc, dest))
			{
				text(g, draw, small, fit(fs, "You are at " + loc.getName(), cw - 12), x0 + 12, y + fs.getAscent(), DIM);
				y += fs.getHeight();
			}
			return y + 2;
		}
		if (loc != null)
		{
			List<String> flags = new ArrayList<>();
			if (rec != null && rec.cannonImmune())
			{
				flags.add("cannon immune");
			}
			else if (loc.getCannon() != null)
			{
				flags.add(loc.getCannon() ? "cannon" : "no cannon");
			}
			if (loc.getMulticombat() != null)
			{
				flags.add(loc.getMulticombat() ? "multi" : "single");
			}
			if (Boolean.TRUE.equals(loc.getSafespot()))
			{
				flags.add("safespot");
			}
			y += dotRow(g, draw, x0, y, cw, OK, loc.getName(), Color.WHITE, "you are here", DIM, fn, fs);
			if (!flags.isEmpty())
			{
				y += chips(g, draw, x0 + 12, y, cw - 12, flags, fs);
			}
			return y + 2;
		}
		if (tracker.getTaskLocation() != null && !tracker.getTaskLocation().isEmpty())
		{
			return y + dotRow(g, draw, x0, y, cw, CYAN, "Go to " + tracker.getTaskLocation(), Color.WHITE, "", DIM, fn, fs) + 2;
		}
		if (rec != null && !rec.getLocations().isEmpty())
		{
			return y + dotRow(g, draw, x0, y, cw, DIM, "Not in a task area", DIM, firstLocationNames(rec), DIM, fn, fs) + 2;
		}
		return y;
	}

	private int checklist(Graphics2D g, boolean draw, int x0, int y, int cw, TaskRecord rec, boolean compact, FontMetrics fn, FontMetrics fs)
	{
		List<Object[]> rows = new ArrayList<>();
		if (rec != null && !compact)
		{
			ItemGroups items = dataset.getItems();
			for (ItemRule rule : rec.getRequiredItems())
			{
				if (!rule.isRequired() || rule.getAnyOf() == null)
				{
					continue;
				}
				if (items.anySatisfied(rule.getAnyOf(), readiness.heldNames()))
				{
					String held = items.heldName(rule.getAnyOf(), readiness.heldNames());
					rows.add(new Object[]{OK, held == null ? items.describe(rule.getAnyOf()) : held, "ok"});
				}
			}
		}
		for (Missing m : readiness.getMissing())
		{
			if (!m.isRequired() && compact)
			{
				continue;
			}
			String status = m.isInBank() && readiness.isBankOpen() ? "in bank" : m.isRequired() ? "missing" : "bring";
			rows.add(new Object[]{m.isRequired() ? MISSING : RECOMMENDED, m.getLabel(), status});
		}
		if (rows.isEmpty())
		{
			return y;
		}
		y = rule(g, draw, x0, y, cw);
		int shown = 0;
		for (Object[] r : rows)
		{
			if (shown == 6)
			{
				text(g, draw, small, "+" + (rows.size() - 6) + " more", x0 + 12, y + fs.getAscent(), DIM);
				y += fs.getHeight();
				break;
			}
			Color c = (Color) r[0];
			y += dotRow(g, draw, x0, y, cw, c, (String) r[1], c == OK ? Color.WHITE : c, (String) r[2], c, fn, fs);
			shown++;
		}
		return y + 2;
	}

	// ---------------------------------------------------------------- widgets

	/** One labelled value for the fight row. */
	private static final class Cell
	{
		final String label;
		final String value;
		final Color color;
		final BufferedImage icon;

		Cell(String label, String value, Color color, BufferedImage icon)
		{
			this.label = label;
			this.value = value;
			this.color = color;
			this.icon = icon;
		}
	}

	/** Equal-width tiles: bold value over a small dim label. */
	private int tiles(Graphics2D g, boolean draw, int x0, int y, int cw, List<String[]> cells, FontMetrics fb, FontMetrics fs)
	{
		int n = cells.size();
		int colW = (cw - 2 * (n - 1)) / n;
		int h = fb.getHeight() + fs.getHeight();
		for (int i = 0; i < n; i++)
		{
			int x = x0 + i * (colW + 2);
			if (draw)
			{
				g.setColor(TILE);
				g.fillRect(x, y, colW, h);
			}
			String v = fit(fb, cells.get(i)[0], colW - 4);
			String l = fit(fs, cells.get(i)[1], colW - 4);
			text(g, draw, bold, v, x + (colW - fb.stringWidth(v)) / 2, y + 1 + fb.getAscent(), Color.WHITE);
			text(g, draw, small, l, x + (colW - fs.stringWidth(l)) / 2, y + fb.getHeight() + fs.getAscent() - 2, DIM);
		}
		return h;
	}

	/** Content-sized cells, label over value, flowing left to right and wrapping. */
	private int cells(Graphics2D g, boolean draw, int x0, int y, int cw, List<Cell> cells, FontMetrics fn, FontMetrics fs)
	{
		int gap = 14;
		int rowH = fs.getHeight() + fn.getHeight() - 2;
		int x = x0;
		int rows = 1;
		for (Cell c : cells)
		{
			int iconW = c.icon == null ? 0 : 16;
			int wv = fn.stringWidth(c.value) + iconW;
			int cellW = Math.min(cw, Math.max(wv, fs.stringWidth(c.label)));
			if (x > x0 && x + cellW > x0 + cw)
			{
				x = x0;
				y += rowH + 2;
				rows++;
			}
			text(g, draw, small, c.label, x, y + fs.getAscent(), DIM);
			int vy = y + fs.getHeight() - 2;
			if (c.icon != null && draw)
			{
				g.drawImage(c.icon, x, vy + (fn.getHeight() - 14) / 2, 14, 14, null);
			}
			text(g, draw, std, fit(fn, c.value, cw - iconW), x + iconW, vy + fn.getAscent(), c.color);
			x += cellW + gap;
		}
		return rowH * rows + 2 * (rows - 1);
	}

	/** Gold for points, violet for the superior table, blue for xp, amber for clues, white for a longer or shorter task. */
	private static Color bonusColour(TaskModifier mod)
	{
		switch (mod.getKind())
		{
			case POINTS: return Palette.GOLD;
			case SUPERIOR_CHANCE: return Palette.SUPERIOR_GLOW;
			case XP_BOOST: return Palette.INFO;
			case CLUES: return RECOMMENDED;
			default: return Color.WHITE;
		}
	}

	/** Two lines for a boosted superior table: the heart (or gem) and any unique, per superior, with the plain rate in small print. */
	private int superiorRows(Graphics2D g, boolean draw, int x0, int y, int cw, int req, int boost, FontMetrics fn, FontMetrics fs)
	{
		String[][] rows = {
			{"Imbued heart or gem", SuperiorMath.odds(SuperiorMath.heartOrGem(req, boost)), "plain " + SuperiorMath.odds(SuperiorMath.heartOrGem(req, 0))},
			{"Any unique per superior", SuperiorMath.odds(SuperiorMath.anyUnique(req, boost)), "plain " + SuperiorMath.odds(SuperiorMath.anyUnique(req, 0))},
		};
		for (String[] r : rows)
		{
			int base = y + fn.getAscent();
			int rateW = fn.stringWidth(r[1]);
			int plainW = fs.stringWidth(r[2]);
			String name = fit(fn, r[0], Math.max(40, cw - rateW - 8 - plainW - 10));
			text(g, draw, std, name, x0, base, Color.WHITE);
			text(g, draw, std, r[1], x0 + cw - rateW, base, Palette.SUPERIOR_GLOW);
			text(g, draw, small, r[2], x0 + cw - rateW - 8 - plainW, base, DIM);
			y += fn.getHeight();
		}
		return y;
	}

	/** One line per rare drop: the item and its rate on the left, your kills since the last one and the chance you would have had it by now on the right. */
	private int oddsRows(Graphics2D g, boolean draw, int x0, int y, int cw, List<TaskHistory.DryOdds> odds, FontMetrics fn, FontMetrics fs)
	{
		for (TaskHistory.DryOdds o : odds)
		{
			String pct = Math.round(o.chanceByNow() * 100) + "%";
			String since = o.getSeen() > 0 ? o.getSeen() + " seen,  " + o.getKillsSince() + " since" : o.getKillsSince() + " dry";
			String rate = o.getRarity();
			int pctW = fn.stringWidth(pct);
			int sinceW = fs.stringWidth(since);
			int rateW = fs.stringWidth(rate);
			int nameMax = cw - pctW - 8 - sinceW - 10 - rateW - 6;
			String name = fit(fn, o.getName(), Math.max(40, nameMax));
			int base = y + fn.getAscent();
			text(g, draw, std, name, x0, base, Color.WHITE);
			text(g, draw, small, rate, x0 + fn.stringWidth(name) + 6, base, DIM);
			Color pc = o.chanceByNow() >= 0.9 ? RECOMMENDED : Color.WHITE;
			text(g, draw, std, pct, x0 + cw - pctW, base, pc);
			text(g, draw, small, since, x0 + cw - pctW - 8 - sinceW, base, DIM);
			y += fn.getHeight();
		}
		return y;
	}

	/** A coloured dot, text on the left, a status word on the right. */
	private int dotRow(Graphics2D g, boolean draw, int x0, int y, int cw, Color dot, String left, Color leftColor, String right, Color rightColor, FontMetrics fn, FontMetrics fs)
	{
		int h = fn.getHeight();
		if (draw)
		{
			g.setColor(dot);
			g.fillOval(x0, y + (h - 7) / 2, 7, 7);
		}
		int rw = right == null || right.isEmpty() ? 0 : fs.stringWidth(right);
		text(g, draw, std, fit(fn, left, cw - 12 - rw - (rw > 0 ? 8 : 0)), x0 + 12, y + fn.getAscent(), leftColor);
		if (rw > 0)
		{
			text(g, draw, small, right, x0 + cw - rw, y + fn.getAscent(), rightColor);
		}
		return h;
	}

	/** Small outlined tags in a row that wraps. */
	private int chips(Graphics2D g, boolean draw, int x0, int y, int cw, List<String> labels, FontMetrics fs)
	{
		int h = fs.getHeight() + 2;
		int x = x0;
		int rows = 1;
		for (String s : labels)
		{
			int w = fs.stringWidth(s) + 8;
			if (x > x0 && x + w > x0 + cw)
			{
				x = x0;
				y += h + 3;
				rows++;
			}
			if (draw)
			{
				g.setColor(new Color(0, 0, 0, 60));
				g.fillRect(x, y, w, h);
				g.setColor(new Color(170, 170, 170, 110));
				g.drawRect(x, y, w - 1, h - 1);
			}
			text(g, draw, small, s, x + 4, y + 1 + fs.getAscent(), DIM);
			x += w + 4;
		}
		return (h + 3) * rows;
	}

	/** An accent bar, a small kicker and the step text. */
	private int callout(Graphics2D g, boolean draw, int x0, int y, int cw, String kicker, String body, FontMetrics fn, FontMetrics fs)
	{
		List<String> lines = wrap(fn, body, cw - 8);
		int h = fs.getHeight() + lines.size() * fn.getHeight() + 2;
		if (draw)
		{
			g.setColor(new Color(0, 0, 0, 50));
			g.fillRect(x0, y, cw, h);
			g.setColor(ACCENT);
			g.fillRect(x0, y, 2, h);
		}
		text(g, draw, small, kicker, x0 + 6, y + 1 + fs.getAscent(), ACCENT);
		int ly = y + fs.getHeight();
		for (String l : lines)
		{
			text(g, draw, std, l, x0 + 6, ly + fn.getAscent(), Color.WHITE);
			ly += fn.getHeight();
		}
		return h + 3;
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

	private static List<String> wrap(FontMetrics fm, String s, int max)
	{
		List<String> out = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : s.split(" "))
		{
			String next = line.length() == 0 ? word : line + " " + word;
			if (fm.stringWidth(next) > max && line.length() > 0)
			{
				out.add(line.toString());
				line = new StringBuilder(word);
			}
			else
			{
				line = new StringBuilder(next);
			}
		}
		if (line.length() > 0)
		{
			out.add(line.toString());
		}
		return out;
	}

	// ---------------------------------------------------------------- icons and words

	private BufferedImage itemIcon(TaskRecord rec)
	{
		Integer id = rec == null ? null : rec.getIconItemId();
		if (id == null)
		{
			return null;
		}
		if (itemIconFor != id || itemIcon == null)
		{
			try
			{
				itemIcon = itemManager.getImage(id, 1, false);
				itemIconFor = id;
			}
			catch (RuntimeException e)
			{
				itemIcon = null;
			}
		}
		return itemIcon;
	}

	private static String firstLocationNames(TaskRecord rec)
	{
		List<String> names = new ArrayList<>();
		for (LocationRecord l : rec.getLocations())
		{
			if (names.size() == 2)
			{
				break;
			}
			names.add(l.getName());
		}
		return String.join(" / ", names);
	}
}
