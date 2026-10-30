package com.slayersuite.task;

import lombok.Value;

/** The superior is no longer standing: killed, or it left before you got to it. */
@Value
public class SuperiorGone
{
	String name;
	boolean dead;
}
