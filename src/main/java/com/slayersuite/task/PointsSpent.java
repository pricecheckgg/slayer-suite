package com.slayersuite.task;

import lombok.Value;

/** Slayer reward points left the balance: a skip (30), a block (100) or a shop unlock. */
@Value
public class PointsSpent
{
	public enum Kind
	{
		SKIP, BLOCK, OTHER
	}

	Kind kind;
	int amount;
	/** The task that was skipped or blocked; null for an unlock. */
	String taskName;
	int balance;
}
