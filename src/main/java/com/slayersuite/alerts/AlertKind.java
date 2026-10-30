package com.slayersuite.alerts;

import lombok.Getter;

/** The priority table. Higher priority wins the banner; sticky alerts stay while their condition holds. */
@Getter
public enum AlertKind
{
	LOW_HP(100, Severity.CRITICAL, true, 0),
	VENOM(95, Severity.CRITICAL, true, 0),
	POISON(90, Severity.WARNING, true, 0),
	LOW_PRAYER(80, Severity.WARNING, true, 0),
	OFF_TASK(75, Severity.WARNING, false, 8_000),
	MISSING_ITEMS(70, Severity.WARNING, true, 0),
	LEFT_BANK(68, Severity.WARNING, false, 20_000),
	STREAK_RESET(66, Severity.WARNING, false, 15_000),
	BOOST_DROPPED(60, Severity.WARNING, false, 20_000),
	SUPPLIES_LOW(58, Severity.WARNING, false, 25_000),
	FLOOR_LOOT(57, Severity.WARNING, false, 20_000),
	CANNON(55, Severity.WARNING, false, 20_000),
	SUPERIOR(50, Severity.INFO, false, 12_000),
	AGGRESSION(45, Severity.WARNING, false, 12_000),
	OUT_OF_COMBAT(40, Severity.WARNING, true, 0),
	NEARLY_DONE(30, Severity.INFO, false, 15_000),
	TASK_DONE(20, Severity.INFO, false, 20_000);

	private final int priority;
	private final Severity severity;
	private final boolean sticky;
	private final long ttlMs;

	AlertKind(int priority, Severity severity, boolean sticky, long ttlMs)
	{
		this.priority = priority;
		this.severity = severity;
		this.sticky = sticky;
		this.ttlMs = ttlMs;
	}
}
