package com.slayersuite.task;

import lombok.Value;

/** Posted once when a brand-new assignment begins (not on login into a task already underway). */
@Value
public class AssignmentStarted
{
	String name;
	int amount;
}
