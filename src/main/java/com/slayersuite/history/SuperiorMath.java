package com.slayersuite.history;

import java.util.Locale;

/**
 * The superior unique table as the wiki gives it, with Mortimer's boost applied. Every superior
 * rolls each of two tables once: one holds the imbued heart (1/8) and the two battlestaves (7/16
 * each), the other the eternal gem (1/8). Pure, so it can be tested without a client.
 */
public final class SuperiorMath
{
	private SuperiorMath()
	{
	}

	/** One in this many superiors rolls a given table: 200 - (req + 55)^2 / 125, so 1/90.5 at 62 Slayer and 1/20 at 95. */
	public static double tableDenominator(int slayerReq)
	{
		int req = Math.max(1, Math.min(99, slayerReq));
		return 200 - Math.pow(req + 55, 2) / 125.0;
	}

	/** Chance per superior of hitting a given table, raised by Mortimer's boost in percent (0 for none). */
	public static double tableChance(int slayerReq, int boostPercent)
	{
		double p = (1 + Math.max(0, boostPercent) / 100.0) / tableDenominator(slayerReq);
		return Math.min(1, p);
	}

	/** Chance per superior of any unique: heart, gem or a battlestaff, nine eighths of one table. */
	public static double anyUnique(int slayerReq, int boostPercent)
	{
		return Math.min(1, tableChance(slayerReq, boostPercent) * 9 / 8);
	}

	/** Chance per superior of the imbued heart, and equally of the eternal gem: an eighth of a table each. */
	public static double heartOrGem(int slayerReq, int boostPercent)
	{
		return tableChance(slayerReq, boostPercent) / 8;
	}

	/** "1/198" from a probability, one decimal under ten; "--" for nothing. */
	public static String odds(double p)
	{
		if (p <= 0)
		{
			return "--";
		}
		double d = 1 / p;
		if (d < 10)
		{
			return "1/" + String.format(Locale.ROOT, "%.1f", d);
		}
		return "1/" + Math.round(d);
	}
}
