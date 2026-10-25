package com.slayersuite.readiness;

import lombok.Value;

/** One unsatisfied readiness rule. */
@Value
public class Missing
{
	String label;
	String why;
	boolean required;
	/** The item was seen in the bank the last time it was open. */
	boolean inBank;
}
