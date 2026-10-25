package com.slayersuite.task;

import lombok.Getter;

/** Posted once when an assignment is completed. */
@Getter
public final class TaskFinished
{
	private final String name;
	/** Slayer points awarded for the task; 0 when unknown. */
	private final int points;
	/** Streak after this task; 0 when unknown. */
	private final int streak;
	/** Fired by the ::ss test command: show everything, record nothing. */
	private final boolean test;

	public TaskFinished(String name, int points, int streak)
	{
		this(name, points, streak, false);
	}

	public TaskFinished(String name, int points, int streak, boolean test)
	{
		this.name = name;
		this.points = points;
		this.streak = streak;
		this.test = test;
	}
}
