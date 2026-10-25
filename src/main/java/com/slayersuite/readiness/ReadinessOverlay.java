package com.slayersuite.readiness;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.data.ItemGroups;
import com.slayersuite.data.ItemRule;
import com.slayersuite.data.SlayerDataset;
import com.slayersuite.data.TaskRecord;
import com.slayersuite.task.TaskTracker;
import java.awt.Color;
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
	private static final Color OK = new Color(120, 220, 120);
	private static final Color MISSING = new Color(255, 90, 90);
	private static final Color RECOMMENDED = new Color(255, 200, 80);
	private static final Color DIM = new Color(170, 170, 170);

	private final ReadinessChecker checker;
	private final TaskTracker tracker;
	private final SlayerDataset dataset;
	private final SlayerSuiteConfig config;
	private final com.slayersuite.task.SlayerMode mode;

	@Inject
	public ReadinessOverlay(ReadinessChecker checker, TaskTracker tracker, SlayerDataset dataset, SlayerSuiteConfig config, com.slayersuite.task.SlayerMode mode)
	{
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
		return super.render(g);
	}
}
