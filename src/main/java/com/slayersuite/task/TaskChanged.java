package com.slayersuite.task;

import lombok.Value;

/** Posted when the assignment name changes (null newName = no task). */
@Value
public class TaskChanged
{
	String oldName;
	String newName;
}
