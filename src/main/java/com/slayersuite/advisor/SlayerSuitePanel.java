package com.slayersuite.advisor;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.LocationRecord;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.history.TaskHistory;
import com.slayersuite.history.TaskStats;
import com.slayersuite.readiness.Missing;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.task.TaskTracker;
import com.slayersuite.trip.TripMath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.Border;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.materialtabs.MaterialTab;
import net.runelite.client.ui.components.materialtabs.MaterialTabGroup;
import net.runelite.api.Experience;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * The side panel. Four blocks top to bottom: the task (progress, prayer, what you
 * still need), where to go (filter chips and one card per spot with a Go button),
 * getting there (numbered ways, each marked by what your account can use), and
 * your history per task.
 */
public class SlayerSuitePanel extends PluginPanel
{
	private static final Color BG = ColorScheme.DARK_GRAY_COLOR;
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color CARD_CHOSEN = new Color(44, 54, 60);
	private static final Color ACCENT = ColorScheme.BRAND_ORANGE;
	private static final Color OK = new Color(110, 200, 110);
	private static final Color WARN = new Color(235, 185, 70);
	private static final Color BAD = new Color(225, 95, 95);
	private static final Color INFO = new Color(90, 190, 230);
	private static final Color DIM = new Color(165, 165, 165);
	private static final Color TAG_BG = new Color(55, 55, 55);
	private static final Color TILE_BG = new Color(36, 36, 36);
	private static final Color PRAY_MELEE = new Color(230, 115, 110);
	private static final Color MISSED = new Color(225, 135, 135);
	/** Text width that fits the panel; measured from the real viewport at each rebuild. */
	private static volatile int WRAP = PluginPanel.PANEL_WIDTH - 60;

	/** RuneLite's own scroll pane around this panel, found once it exists. */
	private JScrollPane scroll()
	{
		java.awt.Container c = getParent();
		while (c != null)
		{
			if (c instanceof JScrollPane)
			{
				return (JScrollPane) c;
			}
			c = c.getParent();
		}
		return null;
	}

	private final Advisor advisor;
	private final TaskTracker tracker;
	private final TaskHistory history;
	private final ReadinessChecker readiness;
	private final SlayerDataset dataset;
	private final SlayerSuiteConfig config;
	private final ConfigManager configManager;
	private final ClientThread clientThread;
	private final HouseScanner house;
	private final PluginOverlap overlap;
	private final com.slayersuite.task.SlayerMode mode;
	private final com.slayersuite.trip.TripTracker trip;
	private final net.runelite.client.eventbus.EventBus eventBus;
	private final com.slayersuite.alerts.AlertEngine alerts;
	private final com.slayersuite.sounds.SoundPlayer sounds;
	private final net.runelite.client.game.ItemManager itemManager;

	private final JPanel content = new JPanel();
	private final JPanel lootContent = new JPanel();
	private String lastLootSignature = "";
	/** How many older levels are listed, and which of them are opened into full cards (by their time). */
	private int olderLevelsShown = 10;
	private final java.util.Set<Long> openedLevels = new java.util.HashSet<>();
	private static final int FULL_LEVEL_CARDS = 3;
	private final Timer timer;
	/** Where the user was before the last rebuild, re-applied while the wrapped text settles. */
	private int restoreValue;
	private boolean restoreBottom;
	private long restoreUntil;

	@Inject
	public SlayerSuitePanel(Advisor advisor, TaskTracker tracker, TaskHistory history, ReadinessChecker readiness, SlayerDataset dataset,
		SlayerSuiteConfig config, ConfigManager configManager, ClientThread clientThread, HouseScanner house, PluginOverlap overlap, com.slayersuite.task.SlayerMode mode, com.slayersuite.trip.TripTracker trip,
		net.runelite.client.eventbus.EventBus eventBus, com.slayersuite.alerts.AlertEngine alerts, com.slayersuite.sounds.SoundPlayer sounds, net.runelite.client.game.ItemManager itemManager)
	{
		super(true);
		this.itemManager = itemManager;
		this.eventBus = eventBus;
		this.alerts = alerts;
		this.sounds = sounds;
		this.trip = trip;
		this.mode = mode;
		this.house = house;
		this.overlap = overlap;
		this.advisor = advisor;
		this.tracker = tracker;
		this.history = history;
		this.readiness = readiness;
		this.dataset = dataset;
		this.config = config;
		this.configManager = configManager;
		this.clientThread = clientThread;
		setLayout(new BorderLayout());
		setBackground(BG);
		setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setBackground(BG);
		lootContent.setLayout(new BoxLayout(lootContent, BoxLayout.Y_AXIS));
		lootContent.setBackground(BG);
		JPanel display = new JPanel(new BorderLayout());
		display.setOpaque(false);
		MaterialTabGroup tabs = new MaterialTabGroup(display);
		tabs.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		MaterialTab taskTab = new MaterialTab("Task", tabs, content);
		MaterialTab lootTab = new MaterialTab("Loot", tabs, lootContent);
		taskTab.setOnSelectEvent(() ->
		{
			SwingUtilities.invokeLater(() -> rebuild(true));
			return true;
		});
		lootTab.setOnSelectEvent(() ->
		{
			rebuildLoot(true);
			return true;
		});
		tabs.addTab(taskTab);
		tabs.addTab(lootTab);
		tabs.select(taskTab);
		JPanel top = new JPanel(new BorderLayout());
		top.setOpaque(false);
		top.add(tabs, BorderLayout.NORTH);
		top.add(display, BorderLayout.CENTER);
		add(top, BorderLayout.NORTH);
		// Wrapped text areas report their final height a layout pass or two after a rebuild; each time the
		// content grows inside the restore window, put the scrollbar back where the user had it.
		java.awt.event.ComponentAdapter keepPlace = new java.awt.event.ComponentAdapter()
		{
			@Override
			public void componentResized(java.awt.event.ComponentEvent e)
			{
				if (System.currentTimeMillis() < restoreUntil)
				{
					applyRestore();
				}
			}
		};
		content.addComponentListener(keepPlace);
		lootContent.addComponentListener(keepPlace);
		timer = new Timer(4000, e -> rebuild());
		timer.setRepeats(true);
		rebuild(true);
	}

	@Override
	public void onActivate()
	{
		rebuild(true);
		timer.start();
	}

	@Override
	public void onDeactivate()
	{
		timer.stop();
	}

	public void refreshLater()
	{
		SwingUtilities.invokeLater(this::rebuild);
	}

	// ================================================================ layout

	private String lastSignature = "";

	/** Everything the panel shows, flattened; when it has not changed the panel is left alone (no flicker, no scroll jump). */
	/** Puts the scrollbar back: at the very bottom if the user was there, else at the saved value. */
	private void applyRestore()
	{
		JScrollPane sp = scroll();
		if (sp == null)
		{
			return;
		}
		javax.swing.JScrollBar bar = sp.getVerticalScrollBar();
		int target = restoreBottom ? bar.getMaximum() - bar.getVisibleAmount() : restoreValue;
		if (bar.getValue() != target)
		{
			bar.setValue(Math.max(0, target));
		}
	}

	private String signature()
	{
		StringBuilder sb = new StringBuilder();
		TaskRecord rec = tracker.getRecord();
		sb.append(tracker.getTaskName()).append('|').append(tracker.getRemaining()).append('|').append(tracker.getInitial()).append('|')
			.append(tracker.etaMillis() / 60_000L).append('|').append(Math.round(tracker.effectiveKph())).append('|')
			.append(tracker.getSlayerPoints()).append('|').append(tracker.getTaskStreak()).append('|').append(tracker.getMortimerTasks()).append('|')
			.append(mode.describe()).append('|').append(Math.round(tracker.rollChance() * 100)).append('|')
			.append(rec == null ? "" : rec.getKey()).append('|').append(readiness.getMissing().size()).append('|').append(readiness.heldNames().size()).append('|');
		com.slayersuite.trip.TripTracker.Projection proj = trip.getProjection();
		if (proj != null)
		{
			sb.append(proj.getKillsUntilDry()).append('|').append(proj.getShortfall()).append('|').append(proj.getLines()).append('|');
		}
		for (ScoredLocation s : advisor.filtered())
		{
			sb.append(s.getLocation().getName()).append(':').append(s.getScore()).append(':').append(s.getTravel()).append(',');
		}
		LocationRecord dest = advisor.getDestination();
		sb.append('|').append(dest == null ? "" : dest.getName()).append('|').append(advisor.isArrived()).append('|')
			.append(advisor.cachedDistance() / 25).append('|').append(advisor.getNextStep()).append('|');
		for (TravelOption o : advisor.cachedTravelOptions(dest == null && advisor.best() != null ? advisor.best().getLocation() : dest))
		{
			sb.append(o.getLabel()).append(':').append(o.getStatus()).append(',');
		}
		sb.append('|').append(house.summary()).append('|').append(readiness.anythingRemembered()).append('|').append(overlap.active()).append('|')
			.append(history.getAssignmentKills()).append('|').append(history.getSessionKills()).append('|').append(history.getAdvice()).append('|')
			.append(config.filterCannon()).append(config.filterMulti()).append(config.filterSafespot()).append(config.filterNoWilderness()).append(config.hideLocked()).append(config.autoDestination());
		return sb.toString();
	}

	private void rebuild()
	{
		rebuild(false);
	}

	private void rebuild(boolean force)
	{
		if (lootContent.isShowing())
		{
			rebuildLoot(force);
			return;
		}
		String sig = signature();
		if (!force && sig.equals(lastSignature))
		{
			return;
		}
		lastSignature = sig;
		JScrollPane scroll = scroll();
		int scrollValue = 0;
		boolean atBottom = false;
		if (scroll != null)
		{
			javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
			scrollValue = bar.getValue();
			atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
		}
		if (getWidth() > 60)
		{
			WRAP = getWidth() - 36;
		}
		content.removeAll();
		TaskRecord rec = tracker.getRecord();
		List<String> clash = overlap.active();
		if (!clash.isEmpty() && config.overlapNotice())
		{
			JPanel warn = card(null, false);
			warn.setLayout(new BoxLayout(warn, BoxLayout.Y_AXIS));
			warn.add(wrap("Overlapping plugins are on. Turn these off to avoid doubled overlays and alerts:", WARN));
			for (String c : clash)
			{
				warn.add(wrap("- " + c, DIM));
			}
			content.add(warn);
			content.add(gap(8));
		}
		content.add(taskCard(rec));
		if (tracker.hasTask())
		{
			com.slayersuite.trip.TripTracker.Projection proj = trip.getProjection();
			if (proj != null && proj.getKillsUntilDry() >= 0 && proj.getShortfall() > 0)
			{
				content.add(gap(10));
				content.add(title("Supplies this trip"));
				content.add(suppliesCard(proj));
			}
			content.add(gap(10));
			content.add(title("Where to go"));
			content.add(filterChips());
			content.add(gap(4));
			if (tracker.getTaskLocation() != null && !tracker.getTaskLocation().isEmpty())
			{
				content.add(wrap("Konar's task: " + tracker.getTaskLocation() + " only.", DIM));
				content.add(gap(4));
			}
			List<ScoredLocation> list = advisor.filtered();
			if (list.isEmpty())
			{
				content.add(card(wrap(rec == null ? "No dataset entry for this task."
					: advisor.ranked().isEmpty() ? "The wiki has no location data for this task yet, so there is nothing to rank or pin."
					: "Nothing matches the filters above.", DIM), false));
			}
			int shown = 0;
			for (ScoredLocation s : list)
			{
				if (shown == 12)
				{
					content.add(wrap((list.size() - 12) + " more spots hidden; tighten the filters.", DIM));
					break;
				}
				content.add(spotCard(s, shown == 0));
				content.add(gap(4));
				shown++;
			}
			if (config.hideLocked())
			{
				List<String> locked = new ArrayList<>();
				for (ScoredLocation s : advisor.ranked())
				{
					if (s.isLocked())
					{
						String why = "";
						for (String r : s.getReasons())
						{
							if (r.startsWith("needs"))
							{
								why = r;
								break;
							}
						}
						locked.add(s.getLocation().getName() + (why.isEmpty() ? "" : " (" + why + ")"));
					}
				}
				if (!locked.isEmpty())
				{
					content.add(wrap("Locked for you: " + String.join("; ", locked), DIM));
				}
			}
			LocationRecord dest = advisor.getDestination();
			if (dest == null && advisor.best() != null)
			{
				dest = advisor.best().getLocation();
			}
			if (dest != null)
			{
				content.add(gap(8));
				content.add(title("Getting there"));
				content.add(travelCard(dest));
			}
		}
		content.add(gap(10));
		content.add(title("Your house and teleports"));
		JPanel hc = card(null, false);
		hc.setLayout(new BoxLayout(hc, BoxLayout.Y_AXIS));
		hc.add(wrap(house.summary(), house.scanned() ? Color.WHITE : DIM));
		hc.add(wrap(readiness.anythingRemembered() ? "Jewellery and tablets: remembered from your bank." : "Jewellery and tablets: open your bank once so I can see them.", readiness.anythingRemembered() ? DIM : WARN));
		content.add(hc);
		content.add(gap(10));
		content.add(title("Your history"));
		content.add(historyCard());
		content.add(gap(10));
		content.add(title("Try the displays"));
		content.add(testCard());
		content.revalidate();
		content.repaint();
		if (scroll != null)
		{
			restoreValue = scrollValue;
			restoreBottom = atBottom;
			restoreUntil = System.currentTimeMillis() + 800;
			SwingUtilities.invokeLater(() ->
			{
				applyRestore();
				SwingUtilities.invokeLater(this::applyRestore);
			});
		}
	}

	// ---------------------------------------------------------------- loot page

	private void rebuildLoot(boolean force)
	{
		TaskHistory.LootSummary sum = history.lootSummary();
		StringBuilder sb = new StringBuilder();
		sb.append(sum.getSlayerXp()).append('|').append(sum.getSinceKills()).append('|').append(sum.getSinceMs() / 60_000L).append('|').append(sum.getSinceValue()).append('|')
			.append(sum.getLifeKills()).append('|').append(sum.getLifeValue()).append('|').append(sum.getSlayerLevels().size()).append('|').append(sum.getRecentItems().size()).append('|').append(sum.getSinceItems().size())
			.append('|').append(sum.getSinceMissedValue()).append('|').append(sum.getLifeMissedValue()).append('|').append(tracker.getTaskName()).append('|').append(history.getAssignmentKills()).append('|')
			.append(history.getAssignmentGp()).append('|').append(history.getAssignmentMissedValue()).append('|').append(olderLevelsShown).append('|').append(openedLevels);
		String sig = sb.toString();
		if (!force && sig.equals(lastLootSignature))
		{
			return;
		}
		lastLootSignature = sig;
		JScrollPane scroll = scroll();
		int scrollValue = 0;
		boolean atBottom = false;
		if (scroll != null)
		{
			javax.swing.JScrollBar bar = scroll.getVerticalScrollBar();
			scrollValue = bar.getValue();
			atBottom = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
		}
		if (getWidth() > 60)
		{
			WRAP = getWidth() - 36;
		}
		lootContent.removeAll();
		lootContent.add(title("Slayer level"));
		lootContent.add(levelCard(sum));
		if (tracker.hasTask())
		{
			lootContent.add(gap(10));
			lootContent.add(title("This task"));
			lootContent.add(thisTaskCard(sum.getNames()));
		}
		if (sum.isSinceKnown())
		{
			lootContent.add(gap(10));
			lootContent.add(titleWithNote("This level", sum.getSinceAt() > 0 ? "since " + when(sum.getSinceAt()) : ""));
			lootContent.add(thisLevelCard(sum));
		}
		List<TaskHistory.LevelRecord> levels = sum.getSlayerLevels();
		if (!levels.isEmpty())
		{
			lootContent.add(gap(10));
			lootContent.add(titleWithNote("Levels", levels.size() + (levels.size() == 1 ? " reached" : " reached")));
			int i = 0;
			for (; i < levels.size() && i < FULL_LEVEL_CARDS; i++)
			{
				lootContent.add(levelRecordCard(levels.get(i), sum.getNames()));
				lootContent.add(gap(4));
			}
			int older = levels.size() - i;
			if (older > 0)
			{
				lootContent.add(label("OLDER", FontManager.getRunescapeSmallFont(), DIM));
				lootContent.add(gap(3));
				int end = Math.min(levels.size(), i + olderLevelsShown);
				for (; i < end; i++)
				{
					TaskHistory.LevelRecord r = levels.get(i);
					if (openedLevels.contains(r.at))
					{
						lootContent.add(levelRecordCard(r, sum.getNames()));
					}
					else
					{
						lootContent.add(compactLevelRow(r));
					}
					lootContent.add(gap(3));
				}
				if (i < levels.size())
				{
					JPanel row = new JPanel(new GridLayout(1, 2, 4, 0));
					row.setOpaque(false);
					row.setAlignmentX(LEFT_ALIGNMENT);
					row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
					JButton more = button("Show 10 more");
					more.addActionListener(e ->
					{
						olderLevelsShown += 10;
						rebuildLoot(true);
					});
					JButton all = button("Show all " + levels.size());
					all.addActionListener(e ->
					{
						olderLevelsShown = levels.size();
						rebuildLoot(true);
					});
					row.add(more);
					row.add(all);
					lootContent.add(row);
				}
			}
		}
		lootContent.add(gap(10));
		lootContent.add(title("All slaying"));
		lootContent.add(allTimeCard(sum));
		lootContent.revalidate();
		lootContent.repaint();
		if (scroll != null)
		{
			restoreValue = scrollValue;
			restoreBottom = atBottom;
			restoreUntil = System.currentTimeMillis() + 800;
			SwingUtilities.invokeLater(() ->
			{
				applyRestore();
				SwingUtilities.invokeLater(this::applyRestore);
			});
		}
	}

	/** A section title with a dim note on the right. */
	private static JComponent titleWithNote(String text, String note)
	{
		JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		row.add(title(text), BorderLayout.WEST);
		if (note != null && !note.isEmpty())
		{
			JLabel n = label(note, FontManager.getRunescapeSmallFont(), DIM);
			n.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
			row.add(n, BorderLayout.EAST);
		}
		return row;
	}

	/** The current assignment: kills, time, loot and what was left, with the drops. */
	private JPanel thisTaskCard(Map<Integer, String> names)
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		TaskRecord rec = tracker.getRecord();
		p.add(wrap(rec != null ? rec.title() : cap(tracker.getTaskName()), Color.WHITE, FontManager.getRunescapeBoldFont()));
		p.add(gap(4));
		List<String[]> cells = new ArrayList<>();
		cells.add(new String[]{"kills", Long.toString(history.getAssignmentKills())});
		cells.add(new String[]{"time", history.getAssignmentMs() > 60_000L ? TaskTracker.formatDuration(history.getAssignmentMs()) : "--"});
		cells.add(new String[]{"loot", TripMath.formatK(history.getAssignmentGp())});
		cells.add(new String[]{"left", TripMath.formatK(history.getAssignmentMissedValue())});
		p.add(tiles(cells));
		List<TaskHistory.LootItem> picked = history.topLoot(15);
		List<TaskHistory.LootItem> left = history.topMissed(10);
		if (picked.isEmpty() && left.isEmpty())
		{
			p.add(gap(4));
			p.add(label("no drops picked up yet", FontManager.getRunescapeSmallFont(), DIM));
			return p;
		}
		if (!picked.isEmpty())
		{
			p.add(gap(6));
			p.add(label("PICKED UP", FontManager.getRunescapeSmallFont(), DIM));
			p.add(gap(3));
			p.add(itemGrid(picked, names, false));
		}
		if (!left.isEmpty())
		{
			p.add(gap(6));
			p.add(label("LEFT BEHIND", FontManager.getRunescapeSmallFont(), MISSED));
			p.add(gap(3));
			p.add(itemGrid(left, names, true));
		}
		return p;
	}

	/** One older level as a single line, with a button that opens it into the full card. */
	private JPanel compactLevelRow(TaskHistory.LevelRecord r)
	{
		JPanel p = card(null, false);
		p.setLayout(new BorderLayout(6, 0));
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		String lvl = r.level > Experience.MAX_REAL_LEVEL ? "Virtual " + r.level : "Level " + r.level;
		text.add(label(lvl + "   " + when(r.at), FontManager.getRunescapeBoldFont(), Color.WHITE));
		text.add(label(r.kills + " kills   " + (r.activeMs > 60_000L ? TaskTracker.formatDuration(r.activeMs) : "--") + "   " + TripMath.formatK(r.lootValue) + " loot"
			+ (r.missedValue > 0 ? "   " + TripMath.formatK(r.missedValue) + " left" : ""), FontManager.getRunescapeSmallFont(), DIM));
		p.add(text, BorderLayout.CENTER);
		JButton open = button("View");
		open.addActionListener(e ->
		{
			openedLevels.add(r.at);
			rebuildLoot(true);
		});
		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
		east.setOpaque(false);
		east.add(open);
		p.add(east, BorderLayout.EAST);
		return p;
	}

	/** Level, virtual level past 99, the xp bar to the next one, and the 200m state. */
	private JPanel levelCard(TaskHistory.LootSummary sum)
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		int xp = sum.getSlayerXp();
		if (xp < 0)
		{
			p.add(label("log in to see your level", FontManager.getRunescapeSmallFont(), DIM));
			return p;
		}
		int level = Experience.getLevelForXp(xp);
		boolean maxed = xp >= Experience.MAX_SKILL_XP;
		int real = Math.min(Experience.MAX_REAL_LEVEL, level);
		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		left.setOpaque(false);
		left.add(label(Integer.toString(real), FontManager.getRunescapeBoldFont().deriveFont(24f), Color.WHITE));
		left.add(label("Slayer", FontManager.getRunescapeFont(), DIM));
		head.add(left, BorderLayout.WEST);
		JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 6));
		right.setOpaque(false);
		if (maxed)
		{
			right.add(tag("200m xp", new Color(232, 180, 60)));
		}
		else if (level > Experience.MAX_REAL_LEVEL)
		{
			right.add(tag("virtual " + level, INFO));
		}
		head.add(right, BorderLayout.EAST);
		p.add(head);
		p.add(gap(4));
		JProgressBar bar = new JProgressBar(0, 1000);
		bar.setStringPainted(true);
		bar.setFont(FontManager.getRunescapeSmallFont());
		bar.setForeground(maxed ? new Color(232, 180, 60) : new Color(80, 150, 80));
		bar.setBackground(new Color(50, 50, 50));
		bar.setBorder(BorderFactory.createEmptyBorder());
		bar.setPreferredSize(new Dimension(WRAP + 10, 18));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		bar.setAlignmentX(LEFT_ALIGNMENT);
		if (maxed)
		{
			bar.setValue(1000);
			bar.setString("200,000,000 xp, maxed");
		}
		else
		{
			int base = Experience.getXpForLevel(level);
			int target = level >= Experience.MAX_VIRT_LEVEL ? Experience.MAX_SKILL_XP : Experience.getXpForLevel(level + 1);
			double k = (xp - base) / (double) Math.max(1, target - base);
			bar.setValue((int) Math.round(Math.max(0, Math.min(1, k)) * 1000));
			bar.setString(TripMath.formatK(xp - base) + " / " + TripMath.formatK(target - base) + " to " + (level >= Experience.MAX_VIRT_LEVEL ? "200m" : Integer.toString(level + 1)));
		}
		p.add(bar);
		p.add(gap(4));
		p.add(wrap(String.format("%,d", xp) + " xp" + (level > Experience.MAX_REAL_LEVEL && !maxed ? ", " + TripMath.formatK(Experience.MAX_SKILL_XP - xp) + " to 200m" : ""), DIM));
		return p;
	}

	/** Since the last Slayer level: kills, time, loot and the drops picked up. */
	private JPanel thisLevelCard(TaskHistory.LootSummary sum)
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		List<String[]> cells = new ArrayList<>();
		cells.add(new String[]{"kills", Long.toString(sum.getSinceKills())});
		cells.add(new String[]{"time", sum.getSinceMs() > 60_000L ? TaskTracker.formatDuration(sum.getSinceMs()) : "--"});
		cells.add(new String[]{"loot", TripMath.formatK(sum.getSinceValue())});
		cells.add(new String[]{"left", TripMath.formatK(sum.getSinceMissedValue())});
		p.add(tiles(cells));
		p.add(gap(6));
		if (sum.getSinceItems().isEmpty())
		{
			p.add(label("no drops picked up yet", FontManager.getRunescapeSmallFont(), DIM));
		}
		else
		{
			p.add(label("PICKED UP", FontManager.getRunescapeSmallFont(), DIM));
			p.add(gap(3));
			p.add(itemGrid(sum.getSinceItems(), sum.getNames(), false));
		}
		if (!sum.getSinceMissed().isEmpty())
		{
			p.add(gap(6));
			p.add(label("LEFT BEHIND", FontManager.getRunescapeSmallFont(), MISSED));
			p.add(gap(3));
			p.add(itemGrid(sum.getSinceMissed(), sum.getNames(), true));
		}
		return p;
	}

	private JPanel levelRecordCard(TaskHistory.LevelRecord r, Map<Integer, String> names)
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		String lvl = r.level > Experience.MAX_REAL_LEVEL ? "Virtual " + r.level : "Level " + r.level;
		head.add(label(lvl, FontManager.getRunescapeBoldFont(), Color.WHITE), BorderLayout.WEST);
		JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		east.setOpaque(false);
		east.add(label(when(r.at), FontManager.getRunescapeSmallFont(), DIM));
		if (openedLevels.contains(r.at))
		{
			JButton hide = button("Hide");
			hide.addActionListener(e ->
			{
				openedLevels.remove(r.at);
				rebuildLoot(true);
			});
			east.add(hide);
		}
		head.add(east, BorderLayout.EAST);
		p.add(head);
		if (r.kills == 0 && r.activeMs == 0 && r.lootValue == 0 && (r.items == null || r.items.isEmpty()))
		{
			p.add(label("counting started here", FontManager.getRunescapeSmallFont(), DIM));
			return p;
		}
		p.add(gap(4));
		List<String[]> cells = new ArrayList<>();
		cells.add(new String[]{"kills", Long.toString(r.kills)});
		cells.add(new String[]{"time", r.activeMs > 60_000L ? TaskTracker.formatDuration(r.activeMs) : "--"});
		cells.add(new String[]{"loot", TripMath.formatK(r.lootValue)});
		cells.add(new String[]{"left", TripMath.formatK(r.missedValue)});
		p.add(tiles(cells));
		List<TaskHistory.LootItem> items = toItems(r.items);
		if (!items.isEmpty())
		{
			p.add(gap(6));
			p.add(label("PICKED UP", FontManager.getRunescapeSmallFont(), DIM));
			p.add(gap(3));
			p.add(itemGrid(items, names, false));
		}
		List<TaskHistory.LootItem> left = toItems(r.missed);
		if (!left.isEmpty())
		{
			p.add(gap(6));
			p.add(label("LEFT BEHIND", FontManager.getRunescapeSmallFont(), MISSED));
			p.add(gap(3));
			p.add(itemGrid(left, names, true));
		}
		return p;
	}

	private static List<TaskHistory.LootItem> toItems(List<long[]> raw)
	{
		List<TaskHistory.LootItem> items = new ArrayList<>();
		if (raw == null)
		{
			return items;
		}
		for (long[] it : raw)
		{
			if (it != null && it.length == 3)
			{
				items.add(new TaskHistory.LootItem((int) it[0], it[1], it[2]));
			}
		}
		return items;
	}

	private JPanel allTimeCard(TaskHistory.LootSummary sum)
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		List<String[]> cells = new ArrayList<>();
		cells.add(new String[]{"kills", Long.toString(sum.getLifeKills())});
		cells.add(new String[]{"time", sum.getLifeMs() > 60_000L ? TaskTracker.formatDuration(sum.getLifeMs()) : "--"});
		cells.add(new String[]{"loot", TripMath.formatK(sum.getLifeValue())});
		cells.add(new String[]{"left", TripMath.formatK(sum.getLifeMissedValue())});
		p.add(tiles(cells));
		p.add(gap(6));
		if (sum.getRecentItems().isEmpty())
		{
			p.add(label("no drops picked up yet", FontManager.getRunescapeSmallFont(), DIM));
		}
		else
		{
			p.add(label("RECENT DROPS, BEST FIRST", FontManager.getRunescapeSmallFont(), DIM));
			p.add(gap(3));
			p.add(itemGrid(sum.getRecentItems(), sum.getNames(), false));
		}
		if (!sum.getRecentMissed().isEmpty())
		{
			p.add(gap(6));
			p.add(label("LEFT BEHIND RECENTLY", FontManager.getRunescapeSmallFont(), MISSED));
			p.add(gap(3));
			p.add(itemGrid(sum.getRecentMissed(), sum.getNames(), true));
		}
		return p;
	}

	/** Item sprites with their stack size, the value under each, five to a row. */
	private JPanel itemGrid(List<TaskHistory.LootItem> items, Map<Integer, String> names, boolean leftBehind)
	{
		JPanel g = new JPanel(new GridLayout(0, 5, 4, 4));
		g.setOpaque(false);
		g.setAlignmentX(LEFT_ALIGNMENT);
		g.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		for (TaskHistory.LootItem li : items)
		{
			JPanel cell = new JPanel();
			cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
			cell.setBackground(TILE_BG);
			cell.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
			JLabel ic = new JLabel();
			ic.setAlignmentX(CENTER_ALIGNMENT);
			ic.setHorizontalAlignment(SwingConstants.CENTER);
			try
			{
				int q = (int) Math.min(li.getQuantity(), Integer.MAX_VALUE);
				net.runelite.client.util.AsyncBufferedImage img = itemManager.getImage(li.getId(), q, q > 1);
				if (img != null)
				{
					img.addTo(ic);
				}
			}
			catch (RuntimeException ignored)
			{
				// no sprite
			}
			JLabel v = new JLabel(TripMath.formatK(li.getValue()), SwingConstants.CENTER);
			v.setFont(FontManager.getRunescapeSmallFont());
			v.setForeground(leftBehind ? MISSED : new Color(232, 180, 60));
			v.setAlignmentX(CENTER_ALIGNMENT);
			String name = names.get(li.getId());
			cell.setToolTipText((name == null ? "Item " + li.getId() : name) + (li.getQuantity() > 1 ? " x " + String.format("%,d", li.getQuantity()) : "") + ", " + String.format("%,d", li.getValue()) + " gp" + (leftBehind ? ", left on the floor" : ""));
			cell.add(ic);
			cell.add(v);
			g.add(cell);
		}
		return g;
	}

	/** "today 14:02", "yesterday 09:10" or "12 Oct". */
	private static String when(long at)
	{
		if (at <= 0)
		{
			return "";
		}
		ZoneId zone = ZoneId.systemDefault();
		LocalDate day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
		LocalDate today = LocalDate.now(zone);
		String time = Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"));
		if (day.equals(today))
		{
			return "today " + time;
		}
		if (day.equals(today.minusDays(1)))
		{
			return "yesterday " + time;
		}
		return Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM"));
	}

	// ---------------------------------------------------------------- task card

	private JPanel taskCard(TaskRecord rec)
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		if (!tracker.hasTask())
		{
			p.add(label("No slayer task", FontManager.getRunescapeBoldFont(), Color.WHITE));
			p.add(wrap("Get an assignment and this panel fills in: where to go for your account, how to get there, and your history.", DIM));
			return p;
		}
		String name = rec != null ? rec.title() : cap(tracker.getTaskName());
		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setOpaque(false);
		head.setAlignmentX(LEFT_ALIGNMENT);
		if (rec != null && rec.getIconItemId() != null)
		{
			JLabel iconLabel = new JLabel();
			try
			{
				net.runelite.client.util.AsyncBufferedImage img = itemManager.getImage(rec.getIconItemId(), 1, false);
				if (img != null)
				{
					img.addTo(iconLabel);
				}
			}
			catch (RuntimeException ignored)
			{
				// no icon
			}
			head.add(iconLabel, BorderLayout.WEST);
		}
		head.add(wrap(name, Color.WHITE, FontManager.getRunescapeBoldFont().deriveFont(16f)), BorderLayout.CENTER);
		p.add(head);
		// slaying mode as a small tag plus the reason, not a sentence
		String desc = mode.describe();
		String reason = desc.contains("(") && desc.endsWith(")") ? desc.substring(desc.indexOf('(') + 1, desc.length() - 1) : desc.replace("Slaying mode: ", "");
		JPanel modeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		modeRow.setOpaque(false);
		modeRow.setAlignmentX(LEFT_ALIGNMENT);
		modeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		modeRow.add(tag(mode.active() ? "slaying" : "dormant", mode.active() ? OK : DIM));
		modeRow.add(label(reason, FontManager.getRunescapeSmallFont(), DIM));
		p.add(modeRow);
		p.add(gap(6));

		int remaining = tracker.getRemaining();
		int initial = Math.max(tracker.getInitial(), remaining);
		JProgressBar bar = new JProgressBar(0, Math.max(initial, 1));
		bar.setValue(Math.max(0, initial - remaining));
		bar.setStringPainted(true);
		bar.setString(remaining + " left of " + initial);
		bar.setFont(FontManager.getRunescapeSmallFont());
		bar.setForeground(new Color(80, 150, 80));
		bar.setBackground(new Color(50, 50, 50));
		bar.setBorder(BorderFactory.createEmptyBorder());
		bar.setPreferredSize(new Dimension(WRAP + 10, 18));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
		bar.setAlignmentX(LEFT_ALIGNMENT);
		p.add(bar);
		p.add(gap(6));

		// the four numbers that matter, as tiles
		long eta = tracker.etaMillis();
		List<String[]> cells = new ArrayList<>();
		cells.add(new String[]{tracker.isEtaFromHistory() ? "eta (hist)" : "eta", eta < 0 ? "--" : TaskTracker.formatDuration(eta)});
		cells.add(new String[]{"rate", tracker.effectiveKph() > 0 ? Math.round(tracker.effectiveKph()) + "/h" : "--"});
		if (tracker.getSlayerPoints() > 0)
		{
			cells.add(new String[]{"points", String.format("%,d", tracker.getSlayerPoints())});
			cells.add(new String[]{"streak", Integer.toString(tracker.getTaskStreak())});
		}
		p.add(tiles(cells));
		p.add(gap(6));

		// how to fight it: aligned label / value rows, prayer coloured like its icon
		if (rec != null)
		{
			String pray = rec.getPrayer();
			Color prayColor = pray == null ? DIM : pray.contains("Magic") ? INFO : pray.contains("Missiles") ? OK : pray.contains("Melee") ? PRAY_MELEE : Color.WHITE;
			p.add(kv("Pray", pray == null ? "any protection prayer" : pray, prayColor));
			if (rec.getStyle() != null)
			{
				p.add(kv("Style", rec.getStyle(), Color.WHITE));
			}
			if (!rec.getSuperiors().isEmpty())
			{
				p.add(kv("Superior", rec.getSuperiors().get(0), Color.WHITE));
			}
			if (rec.getSlayerLevel() != null && rec.getSlayerLevel() > 1)
			{
				p.add(kv("Needs", rec.getSlayerLevel() + " Slayer" + (rec.getOtherRequirements() != null ? ", " + rec.getOtherRequirements() : ""), DIM));
			}
		}
		if (tracker.getGameTask() != null && tracker.rollChance() > 0)
		{
			p.add(kv("Rolled", Math.round(tracker.rollChance() * 100) + "% by " + com.slayersuite.task.GameTaskTable.masterName(tracker.getGameTask().getMasterId()), DIM));
		}

		// gear checklist: a dot per item, status on the right
		List<Missing> missing = readiness.getMissing();
		if (rec != null)
		{
			ItemGroups items = dataset.getItems();
			boolean any = false;
			for (ItemRule rule : rec.allRules())
			{
				if (rule.getAnyOf() == null || rule.getAnyOf().isEmpty())
				{
					continue;
				}
				if (!any)
				{
					p.add(gap(6));
					any = true;
				}
				boolean have = items.anySatisfied(rule.getAnyOf(), readiness.heldNames());
				String held = items.heldName(rule.getAnyOf(), readiness.heldNames());
				String txt = have ? (held == null ? items.describe(rule.getAnyOf()) : held) : items.describe(rule.getAnyOf());
				Color c = have ? OK : rule.isRequired() ? BAD : WARN;
				p.add(dotRow(c, txt, have ? "have" : rule.isRequired() ? "missing" : "bring", false));
			}
			for (Missing m : missing)
			{
				if (m.getLabel().startsWith("Food") || m.getLabel().startsWith("Prayer") || m.getLabel().startsWith("Cannon") || m.getLabel().startsWith("Leave") || m.getLabel().startsWith("Dwarf"))
				{
					if (!any)
					{
						p.add(gap(6));
						any = true;
					}
					p.add(dotRow(m.isRequired() ? BAD : WARN, m.getLabel(), m.isRequired() ? "missing" : "bring", false));
				}
			}
			com.slayersuite.trip.TripTracker.Projection proj = trip.getProjection();
			if (proj != null && proj.getKillsUntilDry() >= 0 && proj.getShortfall() == 0)
			{
				if (!any)
				{
					p.add(gap(6));
				}
				p.add(dotRow(OK, "Supplies last the task", "ok", false));
			}
		}
		return p;
	}

	// ---------------------------------------------------------------- supplies

	private JPanel suppliesCard(com.slayersuite.trip.TripTracker.Projection proj)
	{
		JPanel sc = card(null, false);
		sc.setLayout(new BoxLayout(sc, BoxLayout.Y_AXIS));
		int remaining = tracker.getRemaining();
		if (proj.getKillsUntilDry() >= 0)
		{
			boolean shortfall = proj.getShortfall() > 0;
			sc.add(wrap(shortfall ? "Bank after this trip" : "Enough for the task", shortfall ? WARN : OK, FontManager.getRunescapeBoldFont()));
			boolean plural = proj.getLimitingResource().endsWith("s");
			sc.add(wrap(shortfall ? cap(proj.getLimitingResource()) + (plural ? " run out " : " runs out ") + proj.getShortfall() + " kills early"
				: cap(proj.getLimitingResource()) + (plural ? " go first, after ~" : " goes first, after ~") + proj.getKillsUntilDry() + " kills", DIM));
			sc.add(gap(8));
		}
		for (com.slayersuite.trip.TripTracker.Resource r : proj.getResources())
		{
			long lasts = r.getLastsKills();
			Color c = lasts < 0 ? DIM : lasts >= remaining ? OK : WARN;
			JPanel row = new JPanel(new BorderLayout(6, 0));
			row.setOpaque(false);
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));
			row.add(label(r.getName(), FontManager.getRunescapeBoldFont(), Color.WHITE), BorderLayout.WEST);
			row.add(label(lasts < 0 ? "" : "~" + lasts + " kills", FontManager.getRunescapeBoldFont(), c), BorderLayout.EAST);
			sc.add(row);
			sc.add(wrap(r.getHave() + "   " + r.getRate(), DIM));
			if (lasts >= 0 && remaining > 0)
			{
				sc.add(gap(2));
				sc.add(miniBar(Math.min(1.0, lasts / (double) remaining), c));
			}
			sc.add(gap(6));
		}
		if (proj.isFromHistory())
		{
			sc.add(label("rates from your history until five kills this session", FontManager.getRunescapeSmallFont(), DIM));
		}
		return sc;
	}

	private static JComponent factLine(String k, String v, Color c)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		JLabel key = label(k, FontManager.getRunescapeSmallFont(), DIM);
		key.setVerticalAlignment(SwingConstants.TOP);
		row.add(key, BorderLayout.WEST);
		row.add(wrap(v, c), BorderLayout.CENTER);
		return row;
	}

	private void fact(JPanel grid, String k, String v, Color c)
	{
		JPanel cell = new JPanel(new BorderLayout(4, 0));
		cell.setOpaque(false);
		JLabel key = label(k, FontManager.getRunescapeSmallFont(), DIM);
		JLabel val = label(v, FontManager.getRunescapeSmallFont(), c);
		cell.add(key, BorderLayout.WEST);
		cell.add(val, BorderLayout.CENTER);
		grid.add(cell);
	}

	// ---------------------------------------------------------------- filters

	private JPanel filterChips()
	{
		JPanel p = new JPanel(new GridLayout(3, 2, 4, 4));
		p.setBackground(BG);
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		p.add(chip("Cannon", "filterCannon", config.filterCannon()));
		p.add(chip("Multi", "filterMulti", config.filterMulti()));
		p.add(chip("Safespot", "filterSafespot", config.filterSafespot()));
		p.add(chip("No wild", "filterNoWilderness", config.filterNoWilderness()));
		p.add(chip("Hide locked", "hideLocked", config.hideLocked()));
		p.add(chip("Auto pin", "autoDestination", config.autoDestination()));
		return p;
	}

	private JToggleButton chip(String text, String key, boolean on)
	{
		JToggleButton b = new JToggleButton(text, on);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.setMargin(new Insets(2, 4, 2, 4));
		b.setBorder(BorderFactory.createLineBorder(on ? ACCENT : TAG_BG, on ? 2 : 1));
		b.setBackground(CARD);
		b.setForeground(on ? ACCENT : DIM);
		b.setOpaque(true);
		b.setContentAreaFilled(true);
		b.addItemListener(e ->
		{
			boolean sel = b.isSelected();
			b.setForeground(sel ? ACCENT : DIM);
			b.setBorder(BorderFactory.createLineBorder(sel ? ACCENT : TAG_BG, sel ? 2 : 1));
			configManager.setConfiguration(SlayerSuiteConfig.GROUP, key, sel);
		});
		return b;
	}

	// ---------------------------------------------------------------- spot cards

	private JPanel spotCard(ScoredLocation s, boolean first)
	{
		LocationRecord l = s.getLocation();
		boolean chosen = Advisor.sameLocation(l, advisor.getDestination());
		boolean here = Advisor.sameLocation(l, tracker.getCurrentLocation());
		JPanel p = card(null, chosen);
		p.setLayout(new BorderLayout(6, 0));

		JPanel lines = new JPanel();
		lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
		lines.setOpaque(false);

		JPanel titleRow = tagRow();
		lines.add(wrap(l.getName(), s.isLocked() ? BAD : Color.WHITE, FontManager.getRunescapeBoldFont()));
		if (here)
		{
			titleRow.add(tag("you are here", OK));
		}
		else if (chosen)
		{
			titleRow.add(tag("pinned", INFO));
		}
		else if (first && !s.isLocked())
		{
			titleRow.add(tag("best for you", ACCENT));
		}
		if (s.isLocked())
		{
			titleRow.add(tag("locked", BAD));
		}
		if (s.isWilderness())
		{
			titleRow.add(tag("Wilderness", WARN));
		}
		lines.add(titleRow);

		JPanel flags = tagRow();
		TaskRecord rec = tracker.getRecord();
		if (Boolean.TRUE.equals(l.getCannon()))
		{
			flags.add(tag(rec != null && rec.cannonImmune() ? "cannon: immune" : "cannon", rec != null && rec.cannonImmune() ? WARN : DIM));
		}
		else if (Boolean.FALSE.equals(l.getCannon()))
		{
			flags.add(tag("no cannon", DIM));
		}
		if (l.getMulticombat() != null)
		{
			flags.add(tag(l.getMulticombat() ? "multi" : "single", DIM));
		}
		if (Boolean.TRUE.equals(l.getSafespot()))
		{
			flags.add(tag("safespot", DIM));
		}
		if (l.getSpawnCount() > 0)
		{
			flags.add(tag(l.getSpawnCount() + " spawns", DIM));
		}
		if (flags.getComponentCount() > 0)
		{
			lines.add(flags);
		}

		String travel = s.getTravel() != null ? "Via " + s.getTravel() : "No teleport you own";
		for (String r : s.getReasons())
		{
			if (r.startsWith("needs"))
			{
				travel = cap(r);
			}
		}
		lines.add(wrap(travel, s.isLocked() ? BAD : s.getTravel() != null ? OK : DIM));
		p.add(lines, BorderLayout.CENTER);

		if (l.hasCoordinates() && !here)
		{
			JButton go = button(chosen ? "Unpin" : "Go");
			go.addActionListener(e -> clientThread.invokeLater(() -> {
				if (chosen)
				{
					advisor.clearDestination();
				}
				else
				{
					advisor.setDestination(l);
				}
			}));
			JPanel east = new JPanel(new BorderLayout());
			east.setOpaque(false);
			east.add(go, BorderLayout.NORTH);
			p.add(east, BorderLayout.EAST);
		}
		return p;
	}

	// ---------------------------------------------------------------- travel card

	private JPanel travelCard(LocationRecord dest)
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.add(wrap(dest.getName(), Color.WHITE, FontManager.getRunescapeBoldFont()));
		boolean pinned = Advisor.sameLocation(dest, advisor.getDestination());
		if (advisor.isArrived())
		{
			p.add(gap(4));
			p.add(dotRow(OK, "You are there", null, false));
		}
		else
		{
			int dist = advisor.cachedDistance();
			if (dist >= 0 && pinned)
			{
				p.add(kv("Distance", TripMath.formatK(dist) + " tiles " + compassWord(advisor.cachedDirection()) + ", pinned on the map", DIM));
			}
		}
		String step = advisor.getNextStep();
		if (step != null && !step.isEmpty() && pinned && !advisor.isArrived())
		{
			p.add(gap(6));
			p.add(callout("Next", step));
		}
		List<TravelOption> options = advisor.cachedTravelOptions(dest);
		p.add(gap(8));
		if (options.isEmpty())
		{
			p.add(wrap("No travel notes for this spot yet.", DIM));
		}
		else
		{
			p.add(label("WAYS THERE", FontManager.getRunescapeSmallFont(), DIM));
			p.add(gap(2));
		}
		List<String> unknown = new ArrayList<>();
		for (TravelOption o : options)
		{
			if (o.getStatus().startsWith("not seen yet") || o.getStatus().startsWith("house not scanned"))
			{
				unknown.add(o.getLabel().replace("House: ", ""));
				continue;
			}
			String hops = o.getHops() <= 1 ? "lands you there" : o.getHops() == 2 ? "short run" : "long run";
			Color c = !o.isUsable() ? DIM : o.getStatus().startsWith("needs") || o.getStatus().contains("no quick") ? WARN : OK;
			p.add(optionRow(c, o.getLabel(), hops, shortStatus(o.getStatus())));
		}
		if (!unknown.isEmpty())
		{
			boolean bank = !readiness.anythingRemembered();
			boolean houseUnknown = !house.scanned();
			String where = bank && houseUnknown ? "open your bank and stand in your house once" : bank ? "open your bank once" : "stand in your house once";
			p.add(gap(2));
			p.add(dotRow(WARN, "Not checked: " + String.join(", ", unknown), null, false));
			p.add(wrap("    " + cap(where) + " and these fill in.", DIM));
		}
		p.add(gap(6));
		if (overlap.hasPlugin("shortestpath."))
		{
			p.add(dotRow(OK, "Shortest Path plugin found: the pin is handed to it for a door-to-door route.", null, true));
		}
		else
		{
			p.add(dotRow(INFO, "Optional: install the Shortest Path plugin from the Plugin Hub and this pin becomes a door-to-door route with doors, stairs and shortcuts. Without it you get the arrow, the floor path and the steps above.", null, true));
		}
		if (advisor.getDestination() != null)
		{
			p.add(gap(6));
			JButton clear = button("Clear pin");
			clear.addActionListener(e -> clientThread.invokeLater(advisor::clearDestination));
			clear.setAlignmentX(LEFT_ALIGNMENT);
			p.add(clear);
		}
		return p;
	}

	/** The travel status as a two or three word tag. */
	private static String shortStatus(String status)
	{
		if (status == null)
		{
			return "";
		}
		if (status.startsWith("seen in your bank"))
		{
			return "in bank";
		}
		if (status.contains("no quick way"))
		{
			return "no quick way home";
		}
		if (status.startsWith("fairy rings locked"))
		{
			return "locked";
		}
		if (status.startsWith("needs the "))
		{
			return status.substring("needs the ".length());
		}
		return status;
	}

	// ---------------------------------------------------------------- history

	private JPanel historyCard()
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		List<TaskStats> all = history.all();
		TaskStats cur = history.snapshot();
		if (cur != null)
		{
			all.removeIf(t -> t.getKey() != null && t.getKey().equals(cur.getKey()));
			all.add(0, cur);
		}
		all.sort((a, b) -> Long.compare(b.getLastSeen(), a.getLastSeen()));
		if (tracker.getSlayerPoints() > 0)
		{
			int gained = tracker.getPointsAtSessionStart() > 0 ? tracker.getSlayerPoints() - tracker.getPointsAtSessionStart() : 0;
			String pts = String.format("%,d", tracker.getSlayerPoints()) + " Slayer points, streak " + tracker.getTaskStreak()
				+ (tracker.getMortimerTasks() >= 0 ? ", " + tracker.getMortimerTasks() + " Mortimer tasks" : "")
				+ (gained != 0 ? ", " + (gained > 0 ? "+" : "") + gained + " this session" : "");
			p.add(wrap(pts, Color.WHITE, FontManager.getRunescapeBoldFont()));
			TaskHistory.LootSummary sum = history.lootSummary();
			long spent = sum.getSkipPoints() + sum.getBlockPoints() + sum.getOtherPoints();
			if (spent > 0)
			{
				List<String> parts = new ArrayList<>();
				if (sum.getSkips() > 0)
				{
					parts.add(sum.getSkipPoints() + " on " + sum.getSkips() + (sum.getSkips() == 1 ? " skip" : " skips"));
				}
				if (sum.getBlocks() > 0)
				{
					parts.add(sum.getBlockPoints() + " on " + sum.getBlocks() + (sum.getBlocks() == 1 ? " block" : " blocks"));
				}
				if (sum.getOtherPoints() > 0)
				{
					parts.add(sum.getOtherPoints() + " at the shop");
				}
				p.add(wrap("Spent " + String.join(", ", parts), DIM));
			}
			if (tracker.isStorageUnlocked())
			{
				p.add(wrap("Task storage unlocked: a stored task keeps its kills, xp and loot here.", DIM));
			}
			p.add(gap(4));
		}
		if (all.isEmpty())
		{
			p.add(label("nothing recorded yet", FontManager.getRunescapeSmallFont(), DIM));
			return p;
		}
		int shown = 0;
		for (TaskStats t : all)
		{
			if (shown++ == 10)
			{
				p.add(wrap((all.size() - 10) + " more tasks recorded.", DIM));
				break;
			}
			boolean isCur = cur != null && t.getKey() != null && t.getKey().equals(cur.getKey());
			JPanel row = new JPanel();
			row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
			row.setOpaque(false);
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setBorder(BorderFactory.createEmptyBorder(2, 0, 3, 0));
			row.add(wrap((t.getName() == null ? t.getKey() : t.getName()) + "  " + t.getTasksFinished() + " done" + (isCur ? ", current" : ""), isCur ? OK : Color.WHITE, FontManager.getRunescapeBoldFont()));
			String stats = t.hasRate()
				? Math.round(t.killsPerHour()) + " kills/h   " + TripMath.formatK(Math.round(t.xpPerHour())) + " xp/h   " + TripMath.formatK(Math.round(t.gpPerHour())) + " gp/h"
				: t.getKills() + " kills so far, no rate yet";
			row.add(wrap(stats, DIM));
			String pts = t.getPointSamples() > 0
				? "+" + Math.round(t.avgPoints()) + " points per task (" + t.getPointSamples() + " recorded)"
				: "points per task: not recorded yet";
			if (t.getSkips() > 0 || t.getBlocks() > 0)
			{
				pts += "   skipped " + t.getSkips() + "x" + (t.getBlocks() > 0 ? ", blocked" : "") + ", " + t.getPointsSpent() + " pts";
			}
			row.add(wrap(pts, t.getPointSamples() > 0 ? WARN : DIM));
			p.add(row);
		}
		return p;
	}

	private JPanel testCard()
	{
		JPanel p = card(null, false);
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.add(wrap("Fires the real display without touching your history.", DIM));
		JPanel row = new JPanel(new GridLayout(3, 2, 4, 4));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		String task = tracker.hasTask() ? tracker.getTaskName() : "Test task";
		JButton done = button("Task complete");
		done.addActionListener(e -> clientThread.invokeLater(() -> eventBus.post(new com.slayersuite.task.TaskFinished(task, 20, 12, true))));
		JButton sup = button("Superior");
		sup.addActionListener(e -> clientThread.invokeLater(() -> eventBus.post(new com.slayersuite.task.SuperiorSpawned(task))));
		JButton alert = button("Critical alert");
		alert.addActionListener(e -> clientThread.invokeLater(alerts::testAlert));
		JButton sound = button("Sound only");
		sound.addActionListener(e -> sounds.play(com.slayersuite.sounds.SoundPlayer.Sound.TASK_DONE, config.soundVolume()));
		JButton level = button("Level up");
		level.addActionListener(e -> clientThread.invokeLater(history::testLevelUp));
		row.add(done);
		row.add(sup);
		row.add(level);
		row.add(alert);
		row.add(sound);
		p.add(gap(4));
		p.add(row);
		return p;
	}

	// ================================================================ widgets

	// ---------------------------------------------------------------- widgets

	/** A row of small tiles: big value, tiny label under it. */
	private static JPanel tiles(List<String[]> cells)
	{
		JPanel g = new JPanel(new GridLayout(1, Math.max(1, cells.size()), 4, 0));
		g.setOpaque(false);
		g.setAlignmentX(LEFT_ALIGNMENT);
		g.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
		for (String[] c : cells)
		{
			JPanel t = new JPanel();
			t.setLayout(new BoxLayout(t, BoxLayout.Y_AXIS));
			t.setBackground(TILE_BG);
			t.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
			JLabel val = new JLabel(c[1], SwingConstants.CENTER);
			val.setFont(FontManager.getRunescapeBoldFont());
			val.setForeground(Color.WHITE);
			val.setAlignmentX(CENTER_ALIGNMENT);
			JLabel key = new JLabel(c[0].toUpperCase(), SwingConstants.CENTER);
			key.setFont(FontManager.getRunescapeSmallFont());
			key.setForeground(DIM);
			key.setAlignmentX(CENTER_ALIGNMENT);
			t.add(val);
			t.add(key);
			g.add(t);
		}
		return g;
	}

	/** Dim label in a fixed column, value beside it, so rows line up. */
	private static JComponent kv(String k, String v, Color c)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		JLabel key = label(k, FontManager.getRunescapeSmallFont(), DIM);
		key.setPreferredSize(new Dimension(58, 16));
		key.setVerticalAlignment(SwingConstants.TOP);
		row.add(key, BorderLayout.WEST);
		row.add(wrap(v, c), BorderLayout.CENTER);
		return row;
	}

	/** A coloured dot, the text, and an optional status word on the right in the same colour. */
	private static JComponent dotRow(Color c, String text, String status, boolean dimText)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		JLabel d = new JLabel(new Dot(c));
		d.setVerticalAlignment(SwingConstants.TOP);
		d.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		row.add(d, BorderLayout.WEST);
		row.add(wrap(text, dimText ? DIM : Color.WHITE), BorderLayout.CENTER);
		if (status != null && !status.isEmpty())
		{
			JLabel st = label(status, FontManager.getRunescapeSmallFont(), c);
			st.setVerticalAlignment(SwingConstants.TOP);
			row.add(st, BorderLayout.EAST);
		}
		return row;
	}

	/** One way to travel: dot, name with the run length under it, status tag on the right. */
	private static JComponent optionRow(Color c, String name, String hops, String status)
	{
		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(LEFT_ALIGNMENT);
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		row.setBorder(BorderFactory.createEmptyBorder(0, 0, 3, 0));
		JLabel d = new JLabel(new Dot(c));
		d.setVerticalAlignment(SwingConstants.TOP);
		d.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		row.add(d, BorderLayout.WEST);
		JPanel mid = new JPanel();
		mid.setLayout(new BoxLayout(mid, BoxLayout.Y_AXIS));
		mid.setOpaque(false);
		mid.add(wrap(name, c == DIM ? DIM : Color.WHITE));
		mid.add(label(hops, FontManager.getRunescapeSmallFont(), DIM));
		row.add(mid, BorderLayout.CENTER);
		if (status != null && !status.isEmpty())
		{
			JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			east.setOpaque(false);
			east.add(tag(status, c));
			row.add(east, BorderLayout.EAST);
		}
		return row;
	}

	/** The one thing to do now: accent bar, small kicker, bold text. */
	private static JPanel callout(String kicker, String text)
	{
		JPanel c = new JPanel();
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		c.setBackground(CARD_CHOSEN);
		c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 3, 0, 0, ACCENT), BorderFactory.createEmptyBorder(5, 8, 6, 8)));
		c.setAlignmentX(LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		c.add(label(kicker.toUpperCase(), FontManager.getRunescapeSmallFont(), ACCENT));
		c.add(wrap(text, Color.WHITE, FontManager.getRunescapeBoldFont()));
		return c;
	}

	/** A four pixel bar: how far the supplies reach against the kills left. */
	private static JComponent miniBar(double ratio, Color c)
	{
		JProgressBar b = new JProgressBar(0, 1000);
		b.setValue((int) Math.round(Math.max(0, Math.min(1, ratio)) * 1000));
		b.setStringPainted(false);
		b.setBorderPainted(false);
		b.setForeground(c);
		b.setBackground(new Color(50, 50, 50));
		b.setBorder(BorderFactory.createEmptyBorder());
		b.setPreferredSize(new Dimension(WRAP, 4));
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
		b.setAlignmentX(LEFT_ALIGNMENT);
		return b;
	}

	/** An eight pixel filled circle. */
	private static final class Dot implements Icon
	{
		private final Color color;

		Dot(Color color)
		{
			this.color = color;
		}

		@Override
		public void paintIcon(Component c, Graphics g, int x, int y)
		{
			Graphics2D g2 = (Graphics2D) g.create();
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setColor(color);
			g2.fillOval(x, y, 8, 8);
			g2.dispose();
		}

		@Override
		public int getIconWidth()
		{
			return 8;
		}

		@Override
		public int getIconHeight()
		{
			return 8;
		}
	}

	private static JPanel card(JComponent child, boolean chosen)
	{
		JPanel p = new JPanel();
		p.setBackground(chosen ? CARD_CHOSEN : CARD);
		Border inner = BorderFactory.createEmptyBorder(7, 8, 7, 8);
		Border outer = chosen ? BorderFactory.createMatteBorder(0, 3, 0, 0, ACCENT) : BorderFactory.createMatteBorder(0, 3, 0, 0, CARD);
		p.setBorder(BorderFactory.createCompoundBorder(outer, inner));
		p.setAlignmentX(LEFT_ALIGNMENT);
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		if (child != null)
		{
			p.setLayout(new BorderLayout());
			p.add(child, BorderLayout.CENTER);
		}
		return p;
	}

	private static JLabel title(String text)
	{
		JLabel l = new JLabel(text.toUpperCase());
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(ACCENT);
		l.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private static JLabel label(String text, Font font, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(font);
		l.setForeground(color);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private static JComponent wrap(String text, Color color)
	{
		return wrap(text, color, FontManager.getRunescapeSmallFont());
	}

	/** Text that wraps to whatever width the layout gives it; no measuring, so it can never clip. */
	private static JComponent wrap(String text, Color color, Font font)
	{
		javax.swing.JTextArea a = new javax.swing.JTextArea(text == null ? "" : text);
		a.setLineWrap(true);
		a.setWrapStyleWord(true);
		a.setEditable(false);
		a.setFocusable(false);
		a.setOpaque(false);
		a.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		a.setFont(font);
		a.setForeground(color);
		a.setAlignmentX(LEFT_ALIGNMENT);
		a.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		return a;
	}

	private static JPanel tagRow()
	{
		JPanel p = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 2));
		p.setOpaque(false);
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	/** FlowLayout that reports a preferred height for its wrapped rows, so BoxLayout gives it room. */
	private static class WrapLayout extends FlowLayout
	{
		WrapLayout(int align, int hgap, int vgap)
		{
			super(align, hgap, vgap);
		}

		@Override
		public Dimension preferredLayoutSize(java.awt.Container target)
		{
			int width = target.getWidth() > 0 ? target.getWidth() : WRAP + 20;
			int x = getHgap();
			int y = getVgap();
			int rowH = 0;
			for (Component c : target.getComponents())
			{
				if (!c.isVisible())
				{
					continue;
				}
				Dimension d = c.getPreferredSize();
				if (x + d.width > width && x > getHgap())
				{
					x = getHgap();
					y += rowH + getVgap();
					rowH = 0;
				}
				x += d.width + getHgap();
				rowH = Math.max(rowH, d.height);
			}
			return new Dimension(width, y + rowH + getVgap());
		}

		@Override
		public Dimension minimumLayoutSize(java.awt.Container target)
		{
			return preferredLayoutSize(target);
		}
	}

	private static JLabel tag(String text, Color color)
	{
		JLabel l = new JLabel(text);
		l.setFont(FontManager.getRunescapeSmallFont());
		l.setForeground(color);
		l.setOpaque(true);
		l.setBackground(TAG_BG);
		l.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(color.darker()), BorderFactory.createEmptyBorder(0, 4, 0, 4)));
		l.setHorizontalAlignment(SwingConstants.CENTER);
		return l;
	}

	private static JButton button(String text)
	{
		JButton b = new JButton(text);
		b.setFont(FontManager.getRunescapeSmallFont());
		b.setFocusPainted(false);
		b.setMargin(new Insets(2, 8, 2, 8));
		b.setBackground(TAG_BG);
		b.setForeground(Color.WHITE);
		return b;
	}

	private static Component gap(int h)
	{
		return Box.createRigidArea(new Dimension(0, h));
	}

	private static String escape(String s)
	{
		return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String cap(String s)
	{
		return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	private static String compassWord(String c)
	{
		switch (c == null ? "" : c)
		{
			case "N": return "north";
			case "NE": return "north-east";
			case "E": return "east";
			case "SE": return "south-east";
			case "S": return "south";
			case "SW": return "south-west";
			case "W": return "west";
			case "NW": return "north-west";
			default: return "";
		}
	}

}
