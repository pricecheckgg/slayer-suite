package com.slayersuite.trip;

/** Pure arithmetic for the supply forecast, kept separate so it can be unit-tested. */
public final class TripMath
{
	private TripMath()
	{
	}

	public static double perKill(double total, long kills)
	{
		return kills <= 0 ? 0 : total / kills;
	}

	/** How many more kills the supply lasts; -1 when the resource is not being consumed. */
	public static long killsUntilDry(double count, double perKill)
	{
		if (perKill <= 0)
		{
			return -1;
		}
		return (long) Math.floor(count / perKill);
	}

	/** Exponential moving average; a zero old value adopts the sample outright. */
	public static double ema(double old, double sample, double alpha)
	{
		if (sample < 0)
		{
			return old;
		}
		if (old <= 0)
		{
			return sample;
		}
		return old + alpha * (sample - old);
	}

	/** Smallest non-negative value, or -1 when none applies. */
	public static long minPositive(long... values)
	{
		long best = -1;
		for (long v : values)
		{
			if (v >= 0 && (best < 0 || v < best))
			{
				best = v;
			}
		}
		return best;
	}

	public static String formatK(long n)
	{
		if (Math.abs(n) >= 1_000_000)
		{
			return String.format("%.1fm", n / 1_000_000.0);
		}
		if (Math.abs(n) >= 10_000)
		{
			return (n / 1000) + "k";
		}
		if (Math.abs(n) >= 1_000)
		{
			return String.format("%.1fk", n / 1000.0);
		}
		return Long.toString(n);
	}
}
