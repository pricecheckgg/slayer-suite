package com.slayersuite.task;

import com.slayersuite.SlayerSuiteConfig;
import com.slayersuite.readiness.ReadinessChecker;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Are you slaying right now? Everything that could get in the way elsewhere
 * (the attack guard, the generic alerts, the card, the icons, the arrow) asks
 * this first, so the plugin goes quiet by itself when you boss or skill.
 *
 * Auto rule: you have a task, and at least one of: you are in one of its
 * areas, you fought a task monster in the last few minutes, or a slayer helmet
 * or black mask is with you (worn or carried).
 */
@Singleton
public class SlayerMode
{
	private static final long RECENT_MS = 5 * 60_000L;

	private final SlayerSuiteConfig config;
	private final TaskTracker tracker;
	private final ReadinessChecker readiness;

	@Inject
	public SlayerMode(SlayerSuiteConfig config, TaskTracker tracker, ReadinessChecker readiness)
	{
		this.config = config;
		this.tracker = tracker;
		this.readiness = readiness;
	}

	public boolean active()
	{
		switch (config.slayerMode())
		{
			case ON:
				return true;
			case OFF:
				return false;
			default:
				return reason() != null;
		}
	}

	/** Why auto mode is on, or null when it is off. */
	public String reason()
	{
		if (!tracker.hasTask())
		{
			if (config.loopGuide() != com.slayersuite.advisor.LoopMode.OFF && tracker.getFinishedAt() > 0
				&& System.currentTimeMillis() - tracker.getFinishedAt() < com.slayersuite.advisor.LoopGuide.LOOP_MS)
			{
				return "between tasks";
			}
			return null;
		}
		if (tracker.isInTaskArea())
		{
			return "in a task area";
		}
		long since = System.currentTimeMillis() - tracker.getLastTargetContactAt();
		if (tracker.getLastTargetContactAt() > 0 && since < RECENT_MS)
		{
			return "fought a task monster " + (since / 60_000L) + "m ago";
		}
		if (helmWithYou())
		{
			return "slayer helmet with you";
		}
		return null;
	}

	public boolean helmWithYou()
	{
		return readiness.hasGroup("slayer_helm") || readiness.hasGroup("black_mask");
	}

	/** Short label for the panel. */
	public String describe()
	{
		switch (config.slayerMode())
		{
			case ON:
				return "Slaying mode: always on";
			case OFF:
				return "Slaying mode: off (overlays and alerts are muted)";
			default:
				String r = reason();
				if (!tracker.hasTask())
				{
					return "Slaying mode: off (no task)";
				}
				return r == null ? "Slaying mode: off (no helmet with you, not in a task area)" : "Slaying mode: on (" + r + ")";
		}
	}
}
