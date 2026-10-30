package com.slayersuite.readiness;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.task.TaskTracker;
import java.awt.Color;
import com.slayersuite.ui.Palette;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** The "you have everything for this task" list, shown while the bank is open. */
public class ReadinessOverlay extends OverlayPanel
{
	private static final Color OK = Palette.OK;
	private static final Color MISSING = Palette.BAD;
	private static final Color RECOMMENDED = Palette.WARN;
	private static final Color DIM = Palette.DIM;

	private final ReadinessChecker checker;
	private final TaskTracker tracker;
	private final SlayerDataset dataset;
	private final SlayerSuiteConfig config;
	private final com.slayersuite.task.SlayerMode mode;
	private final com.slayersuite.history.TaskHistory history;
	private final com.slayersuite.trip.TripTracker trip;
	private final net.runelite.api.Client client;

	@Inject
	public ReadinessOverlay(ReadinessChecker checker, TaskTracker tracker, SlayerDataset dataset, SlayerSuiteConfig config, com.slayersuite.task.SlayerMode mode,
		com.slayersuite.history.TaskHistory history, com.slayersuite.trip.TripTracker trip, net.runelite.api.Client client)
	{
		this.history = history;
		this.trip = trip;
		this.client = client;
		this.mode = mode;
		this.checker = checker;
		this.tracker = tracker;
		this.dataset = dataset;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(PRIORITY_HIGH);
		panelComponent.setPreferredSize(new Dimension(230, 0));
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.readinessOverlay() || !checker.isBankOpen() || !tracker.hasTask() || (!mode.active() && !mode.helmWithYou()))
		{
			return null;
		}
		TaskRecord rec = tracker.getRecord();
		String title = rec != null ? rec.title() : tracker.getTaskName();
		boolean ready = checker.isReady();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text((ready ? "Ready: " : "Not ready: ") + title)
			.color(ready ? OK : MISSING)
			.build());

		if (rec == null)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("No dataset entry for this task yet.").leftColor(DIM).build());
		}
		else
		{
			ItemGroups items = dataset.getItems();
			for (ItemRule rule : rec.allRules())
			{
				if (!rule.isRequired() || rule.getAnyOf() == null || !items.anySatisfied(rule.getAnyOf(), checker.heldNames()))
				{
					continue;
				}
				String heldName = items.heldName(rule.getAnyOf(), checker.heldNames());
				panelComponent.getChildren().add(LineComponent.builder()
					.left(heldName == null ? items.describe(rule.getAnyOf()) : heldName)
					.leftColor(OK)
					.right("ok")
					.rightColor(OK)
					.build());
			}
		}
		for (Missing m : checker.getMissing())
		{
			Color c = m.isRequired() ? MISSING : RECOMMENDED;
			panelComponent.getChildren().add(LineComponent.builder()
				.left((m.isRequired() ? "Missing: " : "Bring: ") + m.getLabel())
				.leftColor(c)
				.right(m.isInBank() ? "in bank" : "")
				.rightColor(DIM)
				.build());
			if (m.getWhy() != null && !m.getWhy().isEmpty())
			{
				panelComponent.getChildren().add(LineComponent.builder().left("  " + m.getWhy()).leftColor(DIM).build());
			}
		}
		packingList();
		return super.render(g);
	}

	/** What to take for the kills left, from your own rates on this task. */
	private void packingList()
	{
		int remaining = tracker.getRemaining();
		com.slayersuite.history.TaskStats stats = history.getCurrent();
		if (remaining <= 0)
		{
			return;
		}
		boolean session = trip.getSessionKills() >= 5;
		double fpk = session ? trip.foodPerKill() : stats == null ? 0 : stats.getFoodPerKill();
		double ppk = session ? trip.prayerPerKill() : stats == null ? 0 : stats.getPrayerPerKill();
		double dpk = session ? trip.dosesPerKill() : stats == null ? 0 : stats.getDosesPerKill();
		double antifireMin = 0;
		if (trip.taskNeedsAntifire() && tracker.effectiveKph() > 0)
		{
			antifireMin = remaining / tracker.effectiveKph() * 60.0;
		}
		if (fpk <= 0 && ppk <= 0 && dpk <= 0 && antifireMin <= 0)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("Pack: no rates for this task yet").leftColor(DIM).build());
			return;
		}
		int level = client.getGameState() == net.runelite.api.GameState.LOGGED_IN ? client.getRealSkillLevel(net.runelite.api.Skill.PRAYER) : 1;
		com.slayersuite.trip.PackingMath.Pack pack = com.slayersuite.trip.PackingMath.pack(remaining, fpk, ppk, dpk,
			com.slayersuite.trip.TripTracker.prayerPerDose("prayer potion", level), antifireMin, 6, 0.15);
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Pack for " + remaining + " kills").leftColor(Color.WHITE)
			.right(pack.getTrips() <= 1 ? "one trip" : pack.getTrips() + " trips").rightColor(pack.getTrips() <= 1 ? OK : RECOMMENDED).build());
		if (pack.getFood() > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("  Food").leftColor(DIM).right(pack.getFood() + " pieces").rightColor(Color.WHITE).build());
		}
		if (pack.getPrayerDoses() > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("  Prayer").leftColor(DIM).right(pack.getPrayerDoses() + " doses (" + pack.getPrayerPotions() + " potions)").rightColor(Color.WHITE).build());
		}
		if (pack.getOtherDoses() > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("  Other potions").leftColor(DIM).right(pack.getOtherDoses() + " doses (" + pack.getOtherPotions() + ")").rightColor(Color.WHITE).build());
		}
		if (pack.getAntifireDoses() > 0)
		{
			panelComponent.getChildren().add(LineComponent.builder().left("  Antifire").leftColor(DIM).right(pack.getAntifireDoses() + " doses").rightColor(Color.WHITE).build());
		}
		panelComponent.getChildren().add(LineComponent.builder().left("  15% spare, from " + (session ? "this session" : "your history")).leftColor(DIM).build());
	}
}
