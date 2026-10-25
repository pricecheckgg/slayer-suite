package com.slayersuite.task;

import lombok.Value;

/** The current task vanished with kills left, no points spent and the streak intact: it was put into task storage. */
@Value
public class TaskStored
{
	String name;
	int remaining;
}
