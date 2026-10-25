package com.slayersuite.advisor;

import lombok.Value;

/** One way to reach a location and whether this account can use it. */
@Value
public class TravelOption
{
	String label;
	int hops;
	boolean usable;
	/** "owned", "in bank", "not owned", "needs Ancient spellbook", "fairy rings locked"... */
	String status;
	/** Where it lands you, or null when unknown. */
	int[] landing;
}
