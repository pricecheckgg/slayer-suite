package com.slayersuite.card;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.advisor.Advisor;
import com.slayersuite.advisor.ScoredLocation;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.MonsterRecord;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.history.TaskHistory;
import com.slayersuite.history.TaskStats;
import com.slayersuite.readiness.ReadinessChecker;
import com.slayersuite.trip.TripMath;
import java.awt.Color;
import com.slayersuite.ui.Palette;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Beside the Slayer Task Choice screen: what each offered task means for this
 * account (your own rates, xp per kill, items you lack, best spot) and
 * which one to pick. Reads the screen's text; never clicks anything.
 */
public class TaskChoiceOverlay extends OverlayPanel
{
	private static final Color OK = Palette.OK;
	private static final Color MISSING = Palette.BAD;
	private static final Color WARN = Palette.WARN;
	private static final Color DIM = Palette.DIM;
	private static final Color GOLD = Palette.GOLD;

	private static class Choice
	{
		String name;
		TaskRecord rec;
		String amount = "";
		String extra = "";
		TaskStats stats;
		double score;
		String why = "";
	}

	private final Client client;
	private final SlayerDataset dataset;
	private final TaskHistory history;
	private final Advisor advisor;
	private final ReadinessChecker readiness;
	private final SlayerSuiteConfig config;

	private boolean open;
	private List<Choice> choices = new ArrayList<>();
	private int rescanTick;

	@Inject
	public TaskChoiceOverlay(Client client, SlayerDataset dataset, TaskHistory history, Advisor advisor, ReadinessChecker readiness, SlayerSuiteConfig config)
	{
		this.client = client;
		this.dataset = dataset;
		this.history = history;
		this.advisor = advisor;
		this.readiness = readiness;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setPriority(PRIORITY_HIGH);
		panelComponent.setPreferredSize(new Dimension(260, 0));
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.SLAYER_TASK_CHOICE)
		{
			open = true;
			choices = new ArrayList<>();
			rescanTick = 0;
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.SLAYER_TASK_CHOICE)
		{
			open = false;
			choices = new ArrayList<>();
		}
	}

	@Subscribe
	public void onGameTick(GameTick t)
	{
		if (!open || !config.taskChoiceOverlay())
		{
			return;
		}
		// the texts arrive a tick or two after the interface loads; rescan a few times, then on changes only
		if (choices.isEmpty() || rescanTick++ % 5 == 0)
		{
			scan();
		}
	}

	/** The visible frame of the interface (for its bounds), falling back to the whole-screen container. */
	private Widget root()
	{
		Widget w = client.getWidget(InterfaceID.SlayerTaskChoice.FRAME);
		if (w == null || w.isHidden())
		{
			w = client.getWidget(InterfaceID.SlayerTaskChoice.UNIVERSE);
		}
		return w;
	}

	/** The whole-screen container, so the text scan sees every child. */
	private Widget universe()
	{
		Widget w = client.getWidget(InterfaceID.SlayerTaskChoice.UNIVERSE);
		return w != null ? w : client.getWidget(InterfaceID.SLAYER_TASK_CHOICE, 0);
	}

	private void scan()
	{
		List<String> texts = new ArrayList<>();
		Widget universe = universe();
		if (universe != null)
		{
			collect(universe, texts, 0);
		}
		List<Choice> found = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		Choice cur = null;
		for (String raw : texts)
		{
			String text = raw.replaceAll("<br\\s*/?>", " ").replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
			if (text.isEmpty() || text.equalsIgnoreCase("Slayer Task Choice"))
			{
				continue;
			}
			if (text.startsWith("Amount"))
			{
				if (cur != null)
				{
					cur.amount = text.replace("Amount:", "").trim();
				}
				continue;
			}
			if (text.contains("point") || text.contains("Assigned") || text.contains("assigned"))
			{
				if (cur != null && cur.extra.isEmpty())
				{
					cur.extra = text;
				}
				continue;
			}
			TaskRecord rec = dataset.find(text);
			if (rec != null && text.length() < 40 && seen.add(rec.getKey()))
			{
				cur = new Choice();
				cur.name = text;
				cur.rec = rec;
				found.add(cur);
			}
		}
		if (found.isEmpty())
		{
			return;
		}
		for (Choice c : found)
		{
			c.stats = history.statsFor(c.rec.getKey());
			c.score = score(c);
		}
		choices = found;
	}

	private static void collect(Widget w, List<String> out, int depth)
	{
		if (w == null || depth > 8)
		{
			return;
		}
		String t = w.getText();
		if (t != null && !t.isEmpty())
		{
			out.add(t);
		}
		for (Widget[] group : new Widget[][]{w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (group == null)
			{
				continue;
			}
			for (Widget c : group)
			{
				collect(c, out, depth + 1);
			}
		}
	}

	/** Higher is better: your own xp/h when known, else wiki xp per kill, minus penalties for things you lack. */
	private double score(Choice c)
	{
		double s;
		String why;
		if (c.stats != null && c.stats.hasRate())
		{
			s = c.stats.xpPerHour();
			why = TripMath.formatK(Math.round(s)) + " xp/h for you";
		}
		else
		{
			MonsterRecord m = c.rec.primaryMonster();
			double perKill = m != null && m.getSlayerXp() != null ? m.getSlayerXp() : 0;
			s = perKill * 60; // rough stand-in rate so it is comparable with history
			why = perKill > 0 ? fmt(perKill) + " xp per kill" : "no numbers yet";
		}
		if (!itemsOwned(c.rec))
		{
			s *= 0.5;
			why += ", missing items";
		}
		ScoredLocation best = bestSpot(c.rec);
		if (best == null || best.isLocked())
		{
			s *= 0.6;
			why += best == null ? "" : ", best spot locked";
		}
		c.why = why;
		return s;
	}

	private boolean itemsOwned(TaskRecord rec)
	{
		ItemGroups items = dataset.getItems();
		Set<String> owned = new HashSet<>(readiness.heldNames());
		owned.addAll(readiness.bankNames());
		for (ItemRule r : rec.allRules())
		{
			if (r.isRequired() && r.getAnyOf() != null && !r.getAnyOf().isEmpty() && !items.anySatisfied(r.getAnyOf(), owned))
			{
				return false;
			}
		}
		return true;
	}

	private ScoredLocation bestSpot(TaskRecord rec)
	{
		for (ScoredLocation s : advisor.rankFor(rec))
		{
			if (!s.isLocked())
			{
				return s;
			}
		}
		List<ScoredLocation> all = advisor.rankFor(rec);
		return all.isEmpty() ? null : all.get(0);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!open || !config.taskChoiceOverlay() || choices.isEmpty())
		{
			return null;
		}
		Widget root = root();
		if (root == null || root.isHidden())
		{
			return null;
		}
		Rectangle b = root.getBounds();
		int x = b.x + b.width + 10;
		if (x + 260 > client.getCanvasWidth())
		{
			x = Math.max(0, b.x - 270);
		}
		panelComponent.setPreferredLocation(new Point(x, Math.max(0, b.y)));

		Choice best = null;
		for (Choice c : choices)
		{
			if (best == null || c.score > best.score)
			{
				best = c;
			}
		}
		panelComponent.getChildren().add(TitleComponent.builder().text("Which task?").color(GOLD).build());
		ItemGroups items = dataset.getItems();
		Set<String> owned = new HashSet<>(readiness.heldNames());
		owned.addAll(readiness.bankNames());
		for (Choice c : choices)
		{
			boolean pick = c == best && choices.size() > 1;
			panelComponent.getChildren().add(LineComponent.builder()
				.left(c.rec.title()).leftColor(pick ? OK : Color.WHITE)
				.right(pick ? "pick this" : "").rightColor(OK).build());
			if (!c.amount.isEmpty() || !c.extra.isEmpty())
			{
				line("  " + c.amount + (c.extra.isEmpty() ? "" : "   " + c.extra), DIM);
			}
			if (c.stats != null && c.stats.hasRate())
			{
				line("  You: " + Math.round(c.stats.killsPerHour()) + "/h, " + TripMath.formatK(Math.round(c.stats.xpPerHour())) + " xp/h, "
					+ TripMath.formatK(Math.round(c.stats.gpPerHour())) + " gp/h (" + c.stats.getTasksFinished() + " done"
					+ (c.stats.getPointSamples() > 0 ? ", avg +" + Math.round(c.stats.avgPoints()) + " pts" : "") + ")", Color.WHITE);
			}
			else
			{
				MonsterRecord m = c.rec.primaryMonster();
				line("  No history. Wiki: " + (m != null && m.getSlayerXp() != null ? fmt(m.getSlayerXp()) + " xp/kill" : "?")
					+ (c.rec.getSlayerLevel() != null && c.rec.getSlayerLevel() > 1 ? ", lvl " + c.rec.getSlayerLevel() : ""), DIM);
			}
			for (ItemRule r : c.rec.allRules())
			{
				if (!r.isRequired() || r.getAnyOf() == null || r.getAnyOf().isEmpty())
				{
					continue;
				}
				boolean have = items.anySatisfied(r.getAnyOf(), owned);
				line("  " + (have ? "Have " : "Lack ") + items.describe(r.getAnyOf()), have ? OK : MISSING);
			}
			ScoredLocation spot = bestSpot(c.rec);
			if (spot != null)
			{
				String flags = spot.getReasons().isEmpty() ? "" : " (" + String.join(", ", spot.getReasons()) + ")";
				line("  Spot: " + spot.getLocation().getName() + flags, spot.isLocked() ? MISSING : spot.isWilderness() ? WARN : Color.WHITE);
			}
			else if (c.rec.getLocations().isEmpty())
			{
				line("  Spot: no wiki location data", DIM);
			}
			line("  " + c.why, pick ? OK : DIM);
		}
		return super.render(g);
	}

	private void line(String text, Color color)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(text).leftColor(color).build());
	}

	private static String fmt(double d)
	{
		return d == Math.rint(d) ? Long.toString((long) d) : String.valueOf(d);
	}
}
