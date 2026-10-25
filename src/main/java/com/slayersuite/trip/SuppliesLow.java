package com.slayersuite.trip;

import lombok.Value;

/** Posted once per trip when the forecast says you run dry before the task ends. */
@Value
public class SuppliesLow
{
	long killsUntilDry;
	long shortfall;
	String resource;
}
